import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { PDFArray, PDFDict, PDFDocument, PDFName, PDFRef, PDFString, type PDFContext, type PDFObject } from "pdf-lib";
import { describe, expect, it } from "vitest";
import { validateResult, type HtmlRendererRequest } from "../src/contracts/types.js";
import { render } from "../src/render/renderer.js";
import { svgDiagnostics } from "../src/render/svg.js";
import { extractPdfText } from "./helpers/pdfText.js";

function request(overrides: Partial<HtmlRendererRequest> = {}): HtmlRendererRequest {
  return {
    specVersion: "1.0",
    jobId: "123e4567-e89b-42d3-a456-426614174000",
    inputPath: "/work/in/source.html",
    outputDirectory: "/work/out",
    limits: { timeoutSeconds: 30, maxBytes: 2 * 1024 * 1024, maxPages: 10, maxInlineImageBytes: 512 * 1024, maxTotalInlineImageBytes: 2 * 1024 * 1024 },
    page: { format: "A4", printBackground: true },
    ...overrides,
  };
}

async function run(html: string) {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-svg-"));
  await Promise.all([
    (await import("node:fs/promises")).mkdir(path.join(root, "in")),
    (await import("node:fs/promises")).mkdir(path.join(root, "out")),
  ]);
  await writeFile(path.join(root, "in", "source.html"), html);
  const result = await render(request(), { workRoot: root });
  expect(validateResult(result), JSON.stringify(result)).toEqual([]);
  return { result, root };
}

/** Walks /StructTreeRoot (and any nested kids) collecting every StructElem dict that carries an /S name. */
function collectStructElements(context: PDFContext, node: PDFObject | undefined, out: PDFDict[] = []): PDFDict[] {
  const resolved = node instanceof PDFRef ? context.lookup(node) : node;
  if (!(resolved instanceof PDFDict)) return out;
  if (resolved.get(PDFName.of("S")) instanceof PDFName) {
    out.push(resolved);
  }
  const kids = resolved.get(PDFName.of("K"));
  const resolvedKids = kids instanceof PDFRef ? context.lookup(kids) : kids;
  if (resolvedKids instanceof PDFArray) {
    for (let i = 0; i < resolvedKids.size(); i++) {
      collectStructElements(context, resolvedKids.get(i), out);
    }
  } else if (resolvedKids instanceof PDFDict) {
    collectStructElements(context, resolvedKids, out);
  }
  return out;
}

async function figuresOf(root: string): Promise<PDFDict[]> {
  const bytes = await (await import("node:fs/promises")).readFile(path.join(root, "out", "generated.pdf"));
  const pdf = await PDFDocument.load(bytes, { updateMetadata: false });
  const structTreeRoot = pdf.catalog.lookup(PDFName.of("StructTreeRoot"), PDFDict);
  return collectStructElements(pdf.context, structTreeRoot).filter((e) => e.get(PDFName.of("S")) === PDFName.of("Figure"));
}

describe("inline svg naming (real Chromium)", () => {
  it("a titled svg becomes a Figure with its title as Alt", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>SVG title</title></head><body><h1>Report</h1><svg width="100" height="50"><title>Revenue chart</title><rect width="100" height="50" fill="#246"/></svg></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const figures = await figuresOf(root);
    expect(figures).toHaveLength(1);
    expect((figures[0]!.get(PDFName.of("Alt")) as PDFString).decodeText()).toBe("Revenue chart");
    expect(result.diagnostics.filter((d) => d.code === "HTML_SVG_NAME_MISSING")).toHaveLength(0);
  });

  it("aria-labelledby names the svg", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>SVG labelledby</title></head><body><p id="l">Sales by region</p><svg aria-labelledby="l" width="80" height="40"><rect width="80" height="40"/></svg></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const figures = await figuresOf(root);
    expect(figures).toHaveLength(1);
    expect((figures[0]!.get(PDFName.of("Alt")) as PDFString).decodeText()).toBe("Sales by region");
    expect(result.diagnostics.filter((d) => d.code === "HTML_SVG_NAME_MISSING")).toHaveLength(0);
  });

  it("an unnamed svg without text is decorative and reported", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Unnamed SVG</title></head><body><h1>Report</h1><svg width="80" height="40"><rect width="80" height="40"/></svg></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const figures = await figuresOf(root);
    expect(figures).toHaveLength(0);
    const diagnostics = result.diagnostics.filter((d) => d.code === "HTML_SVG_NAME_MISSING");
    expect(diagnostics).toEqual([{ code: "HTML_SVG_NAME_MISSING", blocking: false, line: null, element: "svg", message: expect.stringMatching(/^1 inline <svg> graphic has no accessible name/) }]);
  });

  it("an unnamed svg with its own text keeps the text and is not reported", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>SVG text</title></head><body><h1>Report</h1><svg width="80" height="40"><text x="5" y="20">Q1</text></svg></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const bytes = await (await import("node:fs/promises")).readFile(path.join(root, "out", "generated.pdf"));
    const text = await extractPdfText(bytes);
    expect(text).toContain("Q1");
    const figures = await figuresOf(root);
    expect(figures).toHaveLength(0);
    expect(result.diagnostics.filter((d) => d.code === "HTML_SVG_NAME_MISSING")).toHaveLength(0);
  });

  it("aria-hidden and role=presentation svgs are left alone", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Hidden and presentation SVG</title></head><body><h1>Report</h1><svg aria-hidden="true" width="80" height="40"><rect width="80" height="40"/></svg><svg role="presentation" width="80" height="40"><rect width="80" height="40"/></svg></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const figures = await figuresOf(root);
    expect(figures).toHaveLength(0);
    expect(result.diagnostics.filter((d) => d.code === "HTML_SVG_NAME_MISSING")).toHaveLength(0);
  });

  it("a whitespace-only title counts as no name", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Blank titled SVG</title></head><body><h1>Report</h1><svg width="80" height="40"><title>   </title><rect width="10" height="10"/></svg></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const figures = await figuresOf(root);
    expect(figures).toHaveLength(0);
    expect(result.diagnostics.filter((d) => d.code === "HTML_SVG_NAME_MISSING")).toHaveLength(1);
  });

  it("svgDiagnostics is pure", () => {
    expect(svgDiagnostics({ named: 3, unnamed: 0 })).toEqual([]);
    const diagnostics = svgDiagnostics({ named: 0, unnamed: 2 });
    expect(diagnostics[0]!.message).toMatch(/^2 inline <svg> graphics have no accessible name/);
  });
});
