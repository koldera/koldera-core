import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { PDFArray, PDFDict, PDFDocument, PDFName, PDFRef, PDFString, type PDFContext, type PDFObject } from "pdf-lib";
import { describe, expect, it } from "vitest";
import { validateResult, type HtmlRendererRequest } from "../src/contracts/types.js";
import { render } from "../src/render/renderer.js";

const PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

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
  const root = await mkdtemp(path.join(tmpdir(), "koldera-decorative-"));
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

describe("decorative images and document outline (real Chromium)", () => {
  it("marks an <img> without alt as decorative (Artifact, no Figure) and keeps a described <img> as a Figure with its alt text; headings produce /Outlines", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Decorative images</title></head><body><h1>Report</h1><p><img src="data:image/png;base64,${PNG_1PX}" width="4" height="4"></p><p><img src="data:image/png;base64,${PNG_1PX}" alt="Chart"></p></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const artifact = result.artifacts.find((a) => a.kind === "generated_pdf")!;
    const bytes = await (await import("node:fs/promises")).readFile(path.join(root, "out", "generated.pdf"));
    expect(bytes.length).toBe(artifact.bytes);

    const pdf = await PDFDocument.load(bytes, { updateMetadata: false });

    // (a) headings in the source must produce a document outline in the catalog.
    expect(pdf.catalog.get(PDFName.of("Outlines"))).toBeDefined();

    // (b) exactly one Figure structure element, carrying the described image's alt text.
    const structTreeRoot = pdf.catalog.lookup(PDFName.of("StructTreeRoot"), PDFDict);
    const elements = collectStructElements(pdf.context, structTreeRoot);
    const figures = elements.filter((e) => e.get(PDFName.of("S")) === PDFName.of("Figure"));
    expect(figures).toHaveLength(1);
    const alt = figures[0]!.get(PDFName.of("Alt"));
    expect(alt).toBeInstanceOf(PDFString);
    expect((alt as PDFString).decodeText()).toBe("Chart");
  });

  it("prints an <img> whose alt is only whitespace as decorative too: no Figure with a blank /Alt (PAC: Alternative text for Figure)", async () => {
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Blank alt</title></head><body><h1>Report</h1><p><img src="data:image/png;base64,${PNG_1PX}" alt="   " width="4" height="4"> <img src="data:image/png;base64,${PNG_1PX}" alt="	
" width="4" height="4"></p><p><img src="data:image/png;base64,${PNG_1PX}" alt="Chart"></p></body></html>`;
    const { result, root } = await run(html);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    const bytes = await (await import("node:fs/promises")).readFile(path.join(root, "out", "generated.pdf"));
    const pdf = await PDFDocument.load(bytes, { updateMetadata: false });
    const structTreeRoot = pdf.catalog.lookup(PDFName.of("StructTreeRoot"), PDFDict);
    const figures = collectStructElements(pdf.context, structTreeRoot).filter((e) => e.get(PDFName.of("S")) === PDFName.of("Figure"));
    expect(figures).toHaveLength(1);
    expect((figures[0]!.get(PDFName.of("Alt")) as PDFString).decodeText()).toBe("Chart");
    // The author gave an alt attribute, so this is not HTML_IMAGE_ALT_MISSING.
    expect(result.diagnostics.filter((d) => d.code === "HTML_IMAGE_ALT_MISSING")).toHaveLength(0);
  });
});
