import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { PDFArray, PDFDict, PDFDocument, PDFName, PDFRef, type PDFContext, type PDFObject } from "pdf-lib";
import { describe, expect, it } from "vitest";
import { type HtmlRendererRequest } from "../src/contracts/types.js";
import { render, renderVetted } from "../src/render/renderer.js";

// Chromium drops a table cell that paints nothing from the
// tag tree, so the rows of such a table have different lengths and later cells change column.

function request(): HtmlRendererRequest {
  return {
    specVersion: "1.0",
    jobId: "123e4567-e89b-42d3-a456-426614174000",
    inputPath: "/work/in/source.html",
    outputDirectory: "/work/out",
    limits: { timeoutSeconds: 30, maxBytes: 2 * 1024 * 1024, maxPages: 10, maxInlineImageBytes: 512 * 1024, maxTotalInlineImageBytes: 2 * 1024 * 1024 },
    page: { format: "A4", printBackground: true },
  };
}

async function workRoot(html: string): Promise<string> {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-cells-"));
  await mkdir(path.join(root, "in"));
  await mkdir(path.join(root, "out"));
  await writeFile(path.join(root, "in", "source.html"), html);
  return root;
}

function lookup(context: PDFContext, node: PDFObject | undefined): PDFObject | undefined {
  return node instanceof PDFRef ? context.lookup(node) : node;
}

function kids(context: PDFContext, element: PDFDict): PDFDict[] {
  const k = lookup(context, element.get(PDFName.of("K")));
  const items = k instanceof PDFArray ? k.asArray() : k ? [k] : [];
  return items.map((item) => lookup(context, item)).filter((item): item is PDFDict => item instanceof PDFDict && item.get(PDFName.of("S")) instanceof PDFName);
}

function elements(context: PDFContext, node: PDFDict, type: string, out: PDFDict[] = []): PDFDict[] {
  for (const kid of kids(context, node)) {
    if (kid.get(PDFName.of("S")) === PDFName.of(type)) out.push(kid);
    elements(context, kid, type, out);
  }
  return out;
}

const TABLE = `<table><tr><th>A</th><th>B</th><th>C</th></tr>
<tr><td></td><td>empty first</td><td>x</td></tr>
<tr><td>y</td><td> </td><td>empty middle</td></tr>
<tr><td>z</td><td>empty last</td><td></td></tr>
<tr><td>&nbsp;</td><td>no-break space</td><td>w</td></tr></table>`;

describe("empty table cells keep their place in the tag tree (real Chromium)", () => {
  it("every row of a borderless table with empty leading, middle and trailing cells has all its cells", async () => {
    const html = `<!doctype html><html lang="en"><head><title>Cells</title></head><body><h1>Cells</h1>${TABLE}</body></html>`;
    const root = await workRoot(html);
    const result = await render(request(), { workRoot: root });
    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const pdf = await PDFDocument.load(await readFile(path.join(root, "out", "generated.pdf")), { updateMetadata: false });
    const tree = pdf.catalog.lookup(PDFName.of("StructTreeRoot"), PDFDict);
    const rows = elements(pdf.context, tree, "TR");
    expect(rows.map((row) => kids(pdf.context, row).length)).toEqual([3, 3, 3, 3, 3]);
  });

  it("adds an out-of-flow no-break space to each empty cell only, so nothing moves on the page", async () => {
    const html = `<!doctype html><html lang="en"><head><title>Cells</title></head><body>${TABLE}<table><tr><td><img alt="" src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==" width="4" height="4"></td></tr></table></body></html>`;
    const root = await workRoot(html);
    let cells: string[] = [];
    let heights: number[] = [];
    const outcome = await renderVetted(html, request(), {
      workRoot: root,
      budgetMs: 30_000,
      onPageForTests: async (page) => {
        heights = await page.evaluate(() => Array.from(document.querySelectorAll("tr")).map((tr) => tr.getBoundingClientRect().height));
      },
      onPrintForTests: async (page) => {
        cells = await page.evaluate(() => Array.from(document.querySelectorAll("td")).map((td) => td.innerHTML));
        expect(await page.evaluate(() => Array.from(document.querySelectorAll("tr")).map((tr) => tr.getBoundingClientRect().height))).toEqual(heights);
      },
    });
    expect(outcome.status).toBe("completed");
    const filler = `<span style="position: absolute;">&nbsp;</span>`;
    expect(cells).toEqual([filler, "empty first", "x", "y", ` ${filler}`, "empty middle", "z", "empty last", filler, "&nbsp;", "no-break space", "w", expect.stringMatching(/^<img /)]);
  });

  it("keeps author CSS for empty cells: no filler where :empty rules or empty-cells:hide would change what is painted", async () => {
    const css = `<style>
td.gone:empty{display:none} td.tint:empty{background:#fc0} td.dash:empty::before{content:"-"}
table.hide{border-collapse:separate;empty-cells:hide} table.hide td{border:1px solid #000}
</style>`;
    const body = `<table><tr><td class="gone"></td><td>a</td></tr><tr><td class="tint"></td><td>b</td></tr><tr><td class="dash"></td><td>c</td></tr><tr><td class="plain"></td><td>d</td></tr></table>
<table class="hide"><tr><td></td><td>e</td></tr></table>`;
    const html = `<!doctype html><html lang="en"><head><title>Empty CSS</title>${css}</head><body>${body}</body></html>`;
    const root = await workRoot(html);
    const look = () =>
      Array.from(document.querySelectorAll("td")).map((td) => {
        const r = td.getBoundingClientRect();
        const s = getComputedStyle(td);
        return [r.x, r.y, r.width, r.height, s.display, s.backgroundColor, getComputedStyle(td, "::before").content].join("|");
      });
    let before: string[] = [];
    let after: string[] = [];
    let fillers: boolean[] = [];
    const outcome = await renderVetted(html, request(), {
      workRoot: root,
      budgetMs: 30_000,
      onPageForTests: async (page) => {
        before = await page.evaluate(look);
      },
      onPrintForTests: async (page) => {
        after = await page.evaluate(look);
        fillers = await page.evaluate(() => Array.from(document.querySelectorAll("td")).map((td) => td.querySelector("span") !== null));
      },
    });
    expect(outcome.status).toBe("completed");
    expect(after).toEqual(before);
    // gone, a, tint, b, dash, c, plain, d, hidden-empty, e
    expect(fillers).toEqual([false, false, false, false, false, false, true, false, false, false]);
  });

  it("sets rowspan to the span the browser lays out: clamped to the row group, and 0 meaning to its end", async () => {
    const html = `<!doctype html><html lang="en"><head><title>Spans</title></head><body>
<table><thead><tr><th rowspan="2">A</th><th>B</th></tr></thead><tbody><tr><td>b1</td><td>c1</td></tr></tbody></table>
<table><tr><th>A</th><th>B</th></tr><tr><td rowspan="0">all</td><td>x1</td></tr><tr><td>x2</td></tr><tr><td>x3</td></tr></table>
<table><tr><th rowspan="21">Side</th><th>B</th></tr><tr><td>y1</td></tr><tr><td>y2</td></tr></table>
</body></html>`;
    const root = await workRoot(html);
    let spans: number[] = [];
    let rects: string[] = [];
    let rectsBefore: string[] = [];
    const rectsOf = () => Array.from(document.querySelectorAll("td, th")).map((c) => JSON.stringify(c.getBoundingClientRect()));
    const outcome = await renderVetted(html, request(), {
      workRoot: root,
      budgetMs: 30_000,
      onPageForTests: async (page) => {
        rectsBefore = await page.evaluate(rectsOf);
      },
      onPrintForTests: async (page) => {
        spans = await page.evaluate(() => Array.from(document.querySelectorAll<HTMLTableCellElement>("[rowspan]")).map((c) => c.rowSpan));
        rects = await page.evaluate(rectsOf);
      },
    });
    expect(outcome.status).toBe("completed");
    expect(spans).toEqual([1, 3, 3]);
    expect(rects).toEqual(rectsBefore);
    const result = await render(request(), { workRoot: root });
    expect(result.status).toBe("completed");
    const pdf = await PDFDocument.load(await readFile(path.join(root, "out", "generated.pdf")), { updateMetadata: false });
    const tree = pdf.catalog.lookup(PDFName.of("StructTreeRoot"), PDFDict);
    const written = elements(pdf.context, tree, "TH").concat(elements(pdf.context, tree, "TD")).flatMap((cell) => {
      const a = lookup(pdf.context, cell.get(PDFName.of("A")));
      const items = a instanceof PDFArray ? a.asArray() : a ? [a] : [];
      return items.map((item) => lookup(pdf.context, item)).filter((d): d is PDFDict => d instanceof PDFDict).map((d) => d.get(PDFName.of("RowSpan"))?.toString()).filter((v): v is string => v !== undefined);
    });
    expect(written.map(Number).every((n) => n >= 1 && n <= 3)).toBe(true);
  });
});
