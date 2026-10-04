import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { PDFArray, PDFDict, PDFDocument, PDFName, PDFRef, PDFString, PDFHexString, type PDFContext, type PDFObject } from "pdf-lib";
import { describe, expect, it } from "vitest";
import { validateResult, type HtmlRendererRequest } from "../src/contracts/types.js";
import { render } from "../src/render/renderer.js";

// The page is served from https://render.koldera.invalid/
// with every request refused. Links into that host lead nowhere, images that did not load make
// Chromium paint their alt text as untagged content, and a missing lang became an invented en-US.

const PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

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

async function run(html: string) {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-offline-"));
  await mkdir(path.join(root, "in"));
  await mkdir(path.join(root, "out"));
  await writeFile(path.join(root, "in", "source.html"), html);
  const result = await render(request(), { workRoot: root });
  expect(validateResult(result), JSON.stringify(result)).toEqual([]);
  expect(result.status, JSON.stringify(result.error)).toBe("completed");
  const pdf = await PDFDocument.load(await readFile(path.join(root, "out", "generated.pdf")), { updateMetadata: false });
  return { result, pdf };
}

function text(value: PDFObject | undefined): string | null {
  return value instanceof PDFString || value instanceof PDFHexString ? value.decodeText() : null;
}

/** Every link annotation of the document: its URI action target, or "internal" for a destination. */
function links(pdf: PDFDocument): string[] {
  const out: string[] = [];
  for (const page of pdf.getPages()) {
    const annots = page.node.Annots();
    if (!annots) continue;
    for (let i = 0; i < annots.size(); i++) {
      const annot = pdf.context.lookup(annots.get(i));
      if (!(annot instanceof PDFDict) || annot.get(PDFName.of("Subtype")) !== PDFName.of("Link")) continue;
      const action = annot.lookup(PDFName.of("A"));
      const uri = action instanceof PDFDict ? text(action.lookup(PDFName.of("URI"))) : null;
      out.push(uri ?? "internal");
    }
  }
  return out;
}

function structElements(context: PDFContext, node: PDFObject | undefined, out: PDFDict[] = []): PDFDict[] {
  const resolved = node instanceof PDFRef ? context.lookup(node) : node;
  if (!(resolved instanceof PDFDict)) return out;
  if (resolved.get(PDFName.of("S")) instanceof PDFName) out.push(resolved);
  const kids = resolved.get(PDFName.of("K"));
  const resolvedKids = kids instanceof PDFRef ? context.lookup(kids) : kids;
  if (resolvedKids instanceof PDFArray) {
    for (let i = 0; i < resolvedKids.size(); i++) structElements(context, resolvedKids.get(i), out);
  } else if (resolvedKids instanceof PDFDict) {
    structElements(context, resolvedKids, out);
  }
  return out;
}

function catalogLang(pdf: PDFDocument): string | null {
  return text(pdf.catalog.get(PDFName.of("Lang")));
}

describe("links that lead nowhere offline (real Chromium)", () => {
  it("removes links that resolve to the render host or to a missing fragment, keeps their text, keeps external links and fragments with a target, and counts them", async () => {
    const html = `<!doctype html><html lang="en"><head><title>Links</title></head><body>
<h1 id="top">Links</h1>
<p><a href="/wiki/Relative">Relative</a> <a href="page.html">Sibling</a> <a href="https://render.koldera.invalid/other">Host</a> <a href="">Empty</a></p>
<p><a href="#top">Back to top</a> <a href="#nowhere">Missing</a></p>
<p><a href="https://example.org/">External</a> <a href="//example.net/p">Scheme-relative</a> <a href="mailto:someone@example.org">Mail</a></p>
</body></html>`;
    const { result, pdf } = await run(html);

    const found = links(pdf);
    expect(found.filter((l) => l.includes("render.koldera.invalid"))).toEqual([]);
    expect(found).toContain("https://example.org/");
    expect(found).toContain("https://example.net/p");
    expect(found).toContain("mailto:someone@example.org");
    expect(found.filter((l) => l === "internal")).toHaveLength(1);
    expect(found).toHaveLength(4);

    const removed = result.diagnostics.filter((d) => d.code === "HTML_RELATIVE_LINK_REMOVED");
    expect(removed).toHaveLength(1);
    expect(removed[0]).toMatchObject({ blocking: false, line: null, element: "a" });
    expect(removed[0]!.message).toMatch(/^5 links /);
  });

  it("reports nothing when every link works offline", async () => {
    const { result } = await run(`<!doctype html><html lang="en"><head><title>Fine</title></head><body><h1 id="a">A</h1><p><a href="#a">up</a> <a href="https://example.org/">out</a></p></body></html>`);
    expect(result.diagnostics.map((d) => d.code)).not.toContain("HTML_RELATIVE_LINK_REMOVED");
  });
});

describe("images that did not load (real Chromium)", () => {
  it("replaces an image that did not load by its alt text as ordinary text, removes one without alt text, keeps loaded images as figures, and counts them", async () => {
    const html = `<!doctype html><html lang="en"><head><title>Images</title></head><body>
<p>Before <img src="https://example.org/logo.png" alt="Logo of Example"> after.</p>
<p><img src="/static/spacer.gif" alt=""><img src="relative/photo.jpg"></p>
<p><img src="data:image/png;base64,AAAA" alt="Broken inline"></p>
<p><img src="data:image/png;base64,${PNG_1PX}" alt="Chart" width="20" height="20"></p>
</body></html>`;
    const { result, pdf } = await run(html);

    const elements = structElements(pdf.context, pdf.catalog.lookup(PDFName.of("StructTreeRoot"), PDFDict));
    const figures = elements.filter((e) => e.get(PDFName.of("S")) === PDFName.of("Figure"));
    expect(figures.map((f) => text(f.get(PDFName.of("Alt"))))).toEqual(["Chart"]);

    const notLoaded = result.diagnostics.filter((d) => d.code === "HTML_IMAGE_NOT_LOADED");
    expect(notLoaded).toHaveLength(1);
    expect(notLoaded[0]).toMatchObject({ blocking: false, line: null, element: "img" });
    expect(notLoaded[0]!.message).toMatch(/^4 images /);
  });

  it("puts the alt text into the page as text in place of the image (DOM before printing)", async () => {
    const root = await mkdtemp(path.join(tmpdir(), "koldera-offline-dom-"));
    await mkdir(path.join(root, "in"));
    await mkdir(path.join(root, "out"));
    await writeFile(path.join(root, "in", "source.html"), `<!doctype html><html lang="en"><head><title>x</title></head><body><p>A <img src="https://example.org/a.png" alt="Logo"> B</p></body></html>`);
    const { renderVetted } = await import("../src/render/renderer.js");
    const { readFile: read } = await import("node:fs/promises");
    const html = await read(path.join(root, "in", "source.html"), "utf8");
    let paragraph = "";
    const outcome = await renderVetted(html, request(), {
      workRoot: root,
      budgetMs: 30_000,
      onPrintForTests: async (page) => {
        paragraph = await page.evaluate(() => document.querySelector("p")!.innerHTML);
      },
    });
    expect(outcome.status).toBe("completed");
    expect(paragraph).toBe(`A <span style="display: inline;">Logo</span> B`);
    expect(outcome.offline).toEqual({ linksRemoved: 0, imagesNotLoaded: 1 });
  });
});

describe("images without alt text: what happened to each one (real Chromium)", () => {
  it("says 'printed as decorative' for an image that was printed and 'left out' for one that could not be loaded, per image", async () => {
    const html = `<!doctype html><html lang="en"><head><title>Alt</title></head><body>
<p><img src="https://example.org/missing.png"></p>
<p><img src="data:image/png;base64,${PNG_1PX}" width="4" height="4"></p>
<p><img src="relative/missing.png"></p>
</body></html>`;
    const { result } = await run(html);

    const alt = result.diagnostics.filter((d) => d.code === "HTML_IMAGE_ALT_MISSING").map((d) => [d.line, d.message]);
    expect(alt).toEqual([
      [2, "an <img> has no alt attribute and could not be loaded (only inline data: images are rendered); it was left out of the PDF. A person must check whether it carries meaning and, if so, embed it and add alt text to the source"],
      [3, "an <img> has no alt attribute; it was printed as decorative (an artifact, no text alternative). A person must check whether it carries meaning and, if so, add alt text to the source"],
      [4, "an <img> has no alt attribute and could not be loaded (only inline data: images are rendered); it was left out of the PDF. A person must check whether it carries meaning and, if so, embed it and add alt text to the source"],
    ]);
  });

  it("keeps the preflight wording for an image the content selection left out (it was not printed at all)", async () => {
    const root = await mkdtemp(path.join(tmpdir(), "koldera-offline-sel-"));
    await mkdir(path.join(root, "in"));
    await mkdir(path.join(root, "out"));
    await writeFile(path.join(root, "in", "source.html"), `<!doctype html><html lang="en"><head><title>Sel</title></head><body>
<nav><img src="data:image/png;base64,${PNG_1PX}"></nav>
<main><p>Text <img src="data:image/png;base64,${PNG_1PX}"></p></main>
</body></html>`);
    const result = await render({ ...request(), content: { selector: "main" } }, { workRoot: root });
    expect(result.status).toBe("completed");
    const alt = result.diagnostics.filter((d) => d.code === "HTML_IMAGE_ALT_MISSING").map((d) => [d.line, d.message]);
    expect(alt).toEqual([
      [2, "an <img> has no alt attribute; a human must decide the text alternative"],
      [3, "an <img> has no alt attribute; it was printed as decorative (an artifact, no text alternative). A person must check whether it carries meaning and, if so, add alt text to the source"],
    ]);
  });
});

describe("document language (real Chromium)", () => {
  it("writes no /Lang when the source has no lang attribute: Chromium must not invent en-US", async () => {
    const { pdf, result } = await run(`<!doctype html><html><head><title>No language</title></head><body><p>Texte sans langue déclarée.</p></body></html>`);
    expect(catalogLang(pdf)).toBeNull();
    expect(result.diagnostics.map((d) => d.code)).toContain("HTML_LANG_MISSING");
  });

  it("treats a blank lang attribute like a missing one", async () => {
    const { pdf } = await run(`<!doctype html><html lang="  "><head><title>Blank</title></head><body><p>Text.</p></body></html>`);
    expect(catalogLang(pdf)).toBeNull();
  });

  it("takes the language the source declares in <meta http-equiv=\"content-language\"> (as Chromium did), and says so", async () => {
    const { pdf, result } = await run(`<!doctype html><html><head><meta http-equiv="content-language" content=" de "><title>Meta</title></head><body lang="de"><p>Text.</p></body></html>`);
    expect(catalogLang(pdf)).toBe("de");
    expect(result.diagnostics.find((d) => d.code === "HTML_LANG_MISSING")?.message).toBe(
      'the <html> element has no lang attribute; the PDF language was taken from <meta http-equiv="content-language"> ("de"). Add lang to <html>',
    );
  });

  it("ignores a content-language pragma listing several languages (HTML: no default language) and a body lang (Chromium ignores it)", async () => {
    const several = await run(`<!doctype html><html><head><meta http-equiv="content-language" content="de, en"><title>Two</title></head><body><p>Text.</p></body></html>`);
    expect(catalogLang(several.pdf)).toBeNull();
    const body = await run(`<!doctype html><html><head><title>Body</title></head><body lang="fr"><p>Texte.</p></body></html>`);
    expect(catalogLang(body.pdf)).toBeNull();
  });

  it("keeps the source's own language", async () => {
    const { pdf } = await run(`<!doctype html><html lang="de"><head><title>Sprache</title></head><body><p>Text.</p></body></html>`);
    expect(catalogLang(pdf)).toBe("de");
  });
});
