import { createHash } from "node:crypto";
import { mkdtemp, readFile, readdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { PDFDocument, PDFName } from "pdf-lib";
import { describe, expect, it } from "vitest";
import { validateResult, type HtmlRendererRequest } from "../src/contracts/types.js";
import { render, type RenderOptions } from "../src/render/renderer.js";

const PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

const SEMANTIC = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Quarterly summary</title>
<style>body { font-family: sans-serif; margin: 2cm } table { border-collapse: collapse } th, td { border: 1px solid #444; padding: 4px }</style></head>
<body><main><h1>Quarterly summary</h1><p>Static content only, <a href="https://example.com/">with a link</a>.</p>
<h2>Figures</h2><ul><li>First</li><li>Second</li></ul>
<table><caption>Two by two</caption><thead><tr><th scope="col">Item</th><th scope="col">Value</th></tr></thead><tbody><tr><td>A</td><td>1</td></tr><tr><td>B</td><td>2</td></tr></tbody></table>
<figure><img src="data:image/png;base64,${PNG_1PX}" alt="One blue pixel" width="40" height="40"><figcaption>A pixel, enlarged</figcaption></figure>
</main></body></html>`;

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

async function workspace(html: string | Uint8Array): Promise<{ root: string; out: string }> {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-render-"));
  const inDir = path.join(root, "in");
  const out = path.join(root, "out");
  await import("node:fs/promises").then((fs) => Promise.all([fs.mkdir(inDir), fs.mkdir(out)]));
  await writeFile(path.join(inDir, "source.html"), html);
  return { root, out };
}

async function run(html: string | Uint8Array, overrides: Partial<HtmlRendererRequest> = {}, options: Partial<RenderOptions> = {}) {
  const ws = await workspace(html);
  const result = await render(request(overrides), { workRoot: ws.root, ...options });
  expect(validateResult(result), JSON.stringify(result)).toEqual([]);
  expect(result.manualReviewRequired).toBe(true);
  expect(result.taggedRequested).toBe(true);
  return { result, ws };
}

const WITH_CHROME = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Saved page</title>
<style>body { font-family: sans-serif } nav, footer { background: #eee }</style></head>
<body><header><nav aria-label="Site"><a href="#a">Home</a> <a href="#b">Products</a></nav></header>
<div class="wrap"><main><h1>The article</h1><p>Only this should be printed.</p><main>nested main stays inside</main></main><aside>Related links</aside></div>
<footer><p>Cookie banner and legal text.</p></footer></body></html>`;

describe("render with a content selection (only the selected elements are printed)", () => {
  it("keeps the outermost matches directly under <body> in source order, drops the rest and says so in an advisory diagnostic", async () => {
    let bodyChildren: string[] = [];
    let bodyText = "";
    const { result } = await run(WITH_CHROME, { content: { selector: "main" } }, {
      onPageForTests: async (page) => {
        bodyChildren = await page.evaluate(() => Array.from(document.body.children).map((e) => e.tagName.toLowerCase()));
        bodyText = await page.evaluate(() => document.body.innerText);
      },
    });
    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    expect(bodyChildren).toEqual(["main"]);
    expect(bodyText).toContain("Only this should be printed.");
    expect(bodyText).toContain("nested main stays inside");
    expect(bodyText).not.toContain("Cookie banner");
    expect(bodyText).not.toContain("Products");
    const selected = result.diagnostics.find((d) => d.code === "HTML_CONTENT_SELECTED")!;
    expect(selected.blocking).toBe(false);
    expect(selected.message).toContain("1 element");
    expect(selected.message).toContain("2 top-level"); // header and footer; the wrapper div contained the match
    expect(selected.element).toBe("main");
  });

  it("fails closed when nothing matches (CONTENT_NOT_FOUND, no artifact) and when the selector is not CSS (INVALID_INPUT)", async () => {
    const none = await run(WITH_CHROME, { content: { selector: "article.missing" } });
    expect(none.result.status).toBe("failed");
    expect(none.result.error?.code).toBe("CONTENT_NOT_FOUND");
    expect(none.result.artifacts).toEqual([]);
    expect(await readdir(none.ws.out)).toEqual([]);

    const broken = await run(WITH_CHROME, { content: { selector: "main[" } });
    expect(broken.result.status).toBe("failed");
    expect(broken.result.error?.code).toBe("INVALID_INPUT");
    expect(broken.result.error?.message).not.toContain("main[");
  });

  it("without a selection the whole document is printed and no selection diagnostic appears", async () => {
    let bodyChildren: string[] = [];
    const { result } = await run(WITH_CHROME, {}, { onPageForTests: async (page) => { bodyChildren = await page.evaluate(() => Array.from(document.body.children).map((e) => e.tagName.toLowerCase())); } });
    expect(result.status).toBe("completed");
    expect(bodyChildren).toEqual(["header", "div", "footer"]);
    expect(result.diagnostics.some((d) => d.code === "HTML_CONTENT_SELECTED")).toBe(false);
  });
});

describe("render (real Chromium, scripts off, offline)", () => {
  it("renders semantic HTML to a PDF whose catalog really carries a structure tree", async () => {
    const { result, ws } = await run(SEMANTIC);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    expect(result.error).toBeNull();
    expect(result.blockedRequests).toBe(0);
    expect(result.pages).toBe(1);
    expect(result.sourceSha256).toBe(createHash("sha256").update(SEMANTIC).digest("hex"));
    expect(result.engineVersion).toMatch(/koldera-html-renderer 0\.1\.0; playwright 1\.63\.0; chromium \d+/);
    expect(result.diagnostics).toEqual([]);

    const artifact = result.artifacts.find((a) => a.kind === "generated_pdf")!;
    expect(artifact.path).toBe("/work/out/generated.pdf");
    const bytes = await readFile(path.join(ws.out, "generated.pdf"));
    expect(bytes.length).toBe(artifact.bytes);
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(artifact.sha256);
    expect(bytes.subarray(0, 5).toString("latin1")).toBe("%PDF-");

    // Renderer-side smoke only: the PDF engine's Java probe in the server is the independent verdict.
    const pdf = await PDFDocument.load(bytes, { updateMetadata: false });
    expect(pdf.getPageCount()).toBe(1);
    expect(pdf.catalog.get(PDFName.of("StructTreeRoot"))).toBeDefined();
    expect(pdf.catalog.get(PDFName.of("MarkInfo"))).toBeDefined();
    expect(pdf.getTitle()).toBe("Quarterly summary");
  });

  it("renders a document with a script and an external image without them, reporting each and the blocked request", async () => {
    const { result, ws } = await run(SEMANTIC.replace("<main>", `<main><script>fetch('https://x')</script><img src="https://example.com/logo.png" alt="logo">`));

    expect(result.status).toBe("completed");
    expect(result.error).toBeNull();
    expect(result.artifacts).toHaveLength(1);
    expect(result.diagnostics.every((d) => !d.blocking)).toBe(true);
    expect(result.diagnostics.map((d) => d.code).sort()).toEqual(["HTML_EXTERNAL_REQUESTS_BLOCKED", "HTML_EXTERNAL_RESOURCE", "HTML_IMAGE_NOT_LOADED", "HTML_SCRIPT_REMOVED"]);
    expect(result.blockedRequests).toBeGreaterThanOrEqual(1);
    expect(await readdir(ws.out)).toHaveLength(1);
  });

  it("refuses a document that cannot be rendered within the limits without launching a render or writing an artifact", async () => {
    const big = Buffer.alloc(600 * 1024, 1).toString("base64");
    const { result, ws } = await run(SEMANTIC.replace("<main>", `<main><img src="data:image/png;base64,${big}" alt="big">`));

    expect(result.status).toBe("unsupported");
    expect(result.error?.code).toBe("UNSUPPORTED_CONTENT");
    expect(result.diagnostics.some((d) => d.code === "HTML_INLINE_IMAGE_TOO_LARGE" && d.blocking)).toBe(true);
    expect(result.artifacts).toEqual([]);
    expect(result.engineVersion).toMatch(/chromium not-launched/);
    expect(await readdir(ws.out)).toEqual([]);
  });

  it("carries advisory diagnostics (lang, title, alt) into a completed result", async () => {
    const { result } = await run(`<!doctype html><html><head><meta charset="utf-8"></head><body><p>No lang, no title.</p><img src="data:image/png;base64,${PNG_1PX}"></body></html>`);

    expect(result.status).toBe("completed");
    expect(result.diagnostics.map((d) => d.code).sort()).toEqual(["HTML_IMAGE_ALT_MISSING", "HTML_LANG_MISSING", "HTML_TITLE_MISSING"]);
    expect(result.diagnostics.every((d) => !d.blocking)).toBe(true);    // The rendered PDF prints such an image as decorative, and the finding says so.
    const alt = result.diagnostics.find((d) => d.code === "HTML_IMAGE_ALT_MISSING");
    expect(alt?.message).toBe("an <img> has no alt attribute; it was printed as decorative (an artifact, no text alternative). A person must check whether it carries meaning and, if so, add alt text to the source");
  });

  it("aborts every network request the page attempts and says so (deny-all route)", async () => {
    // preflight is bypassed on purpose to prove the second layer on its own
    const { result } = await run(`<!doctype html><html lang="en"><head><title>Leak</title></head><body><img src="https://example.com/pixel.png" alt="x"><p>text</p></body></html>`, {}, { skipPreflightForTests: true });

    expect(result.status).toBe("completed");
    expect(result.blockedRequests).toBeGreaterThanOrEqual(1);
    expect(result.diagnostics).toEqual([
      { code: "HTML_IMAGE_NOT_LOADED", message: expect.stringMatching(/^1 image could not be loaded/), blocking: false, line: null, element: "img" },
      { code: "HTML_EXTERNAL_REQUESTS_BLOCKED", message: expect.stringContaining("blocked"), blocking: false, line: null, element: null },
    ]);
    expect(result.artifacts).toHaveLength(1);
  });

  it("never runs scripts even when preflight is bypassed", async () => {
    let seen = "";
    const { result } = await run(`<!doctype html><html lang="en"><head><title>JS</title></head><body><h1 id="h">before</h1><script>document.getElementById('h').textContent='after'</script></body></html>`, {}, {
      skipPreflightForTests: true,
      onPageForTests: async (page) => {
        seen = await page.evaluate(() => document.body.innerText);
      },
    });

    expect(result.status).toBe("completed");
    expect(seen).toContain("before");
    expect(seen).not.toContain("after");
  });

  it("enforces the page cap after rendering and publishes nothing above it", async () => {
    const long = `<!doctype html><html lang="en"><head><title>Long</title><style>section { page-break-after: always; height: 10cm }</style></head><body>${"<section><p>page</p></section>".repeat(6)}</body></html>`;
    const { result, ws } = await run(long, { limits: { ...request().limits, maxPages: 3 } });

    expect(result.status).toBe("failed");
    expect(result.error?.code).toBe("LIMIT_EXCEEDED");
    expect(result.pages).toBeGreaterThan(3);
    expect(result.artifacts).toEqual([]);
    expect(await readdir(ws.out)).toEqual([]);
  });

  it("times out without an artifact when the budget is exhausted", async () => {
    const { result, ws } = await run(SEMANTIC, {}, { renderBudgetMsForTests: 1 });

    expect(result.status).toBe("failed");
    expect(result.error?.code).toBe("TIMEOUT");
    expect(result.artifacts).toEqual([]);
    expect(await readdir(ws.out)).toEqual([]);
  });

  it("refuses inputs above maxBytes and invalid encodings before rendering", async () => {
    const big = await run(SEMANTIC, { limits: { ...request().limits, maxBytes: 100 } });
    expect(big.result.status).toBe("failed");
    expect(big.result.error?.code).toBe("LIMIT_EXCEEDED");

    const bad = await run(new Uint8Array([0xff, 0xfe, 0x00]));
    expect(bad.result.status).toBe("unsupported");
    expect(bad.result.diagnostics[0]?.code).toBe("HTML_ENCODING_INVALID");
  });
});
