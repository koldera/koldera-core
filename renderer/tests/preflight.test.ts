import { describe, expect, it } from "vitest";
import { preflight, type PreflightLimits } from "../src/preflight/preflight.js";

const limits: PreflightLimits = { maxInlineImageBytes: 64 * 1024, maxTotalInlineImageBytes: 128 * 1024 };

const PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

function doc(body: string, head = "<title>Fixture</title>", htmlAttrs = ' lang="en"'): Uint8Array {
  return new TextEncoder().encode(`<!doctype html><html${htmlAttrs}><head><meta charset="utf-8">${head}</head><body>${body}</body></html>`);
}

function codes(bytes: Uint8Array) {
  const r = preflight(bytes, limits);
  return { r, blocking: r.diagnostics.filter((d) => d.blocking).map((d) => d.code), advisory: r.diagnostics.filter((d) => !d.blocking).map((d) => d.code) };
}

describe("preflight accepts self-contained static semantic HTML", () => {
  it("passes a semantic document with headings, lists, a table, an inline data image and inline CSS", () => {
    const { r, blocking, advisory } = codes(
      doc(
        `<main><h1>Report</h1><p>Intro with <a href="https://example.com/">a link</a>, <abbr title="World Wide Web">WWW</abbr> and <time datetime="2026-09-17">today</time>.</p>
         <ul><li>One</li><li>Two</li></ul>
         <table><caption>Numbers</caption><thead><tr><th scope="col">A</th><th scope="col">B</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>
         <figure><img src="data:image/png;base64,${PNG_1PX}" alt="One blue pixel" width="1" height="1"><figcaption>Pixel</figcaption></figure>
         <p style="color: #333; margin: 0 1em">Styled</p></main>`,
        `<title>Fixture</title><style>body { font-family: sans-serif } h1 { color: rgb(20, 20, 20); background: url(data:image/png;base64,${PNG_1PX}) }</style>`,
      ),
    );
    expect(blocking).toEqual([]);
    expect(advisory).toEqual([]);
    expect(r.ok).toBe(true);
    expect(r.html).toContain("<h1>Report</h1>");
  });

  it("reports missing lang, title and alt as advisory findings, not as refusals", () => {
    const { r, blocking, advisory } = codes(doc(`<p>Hi</p><img src="data:image/png;base64,${PNG_1PX}">`, "", ""));
    expect(blocking).toEqual([]);
    expect(advisory.sort()).toEqual(["HTML_IMAGE_ALT_MISSING", "HTML_LANG_MISSING", "HTML_TITLE_MISSING"]);
    expect(r.ok).toBe(true);
  });
});

describe("preflight leaves out what could only execute or navigate, and reports it", () => {
  it.each([
    ["script element", `<script>alert(1)</script>`, "HTML_SCRIPT_REMOVED", "script"],
    ["script element with src", `<script src="https://cdn.example/x.js"></script>`, "HTML_SCRIPT_REMOVED", "script"],
    ["inline event handler", `<p onclick="x()">Hi</p>`, "HTML_EVENT_HANDLER_REMOVED", "onclick"],
    ["javascript: link", `<a href="javascript:alert(1)">x</a>`, "HTML_URL_REMOVED", "javascript:"],
    ["iframe", `<iframe src="https://example.com/"></iframe>`, "HTML_ELEMENT_REMOVED", "<iframe"],
    ["object", `<object data="x.swf"></object>`, "HTML_ELEMENT_REMOVED", "<object"],
    ["embed", `<embed src="x.svg">`, "HTML_ELEMENT_REMOVED", "<embed"],
    ["ping attribute", `<a href="https://x/" ping="https://t/">x</a>`, "HTML_ATTRIBUTE_REMOVED", "ping="],
    ["srcset", `<img src="data:image/png;base64,${PNG_1PX}" srcset="https://x/y.png 2x" alt="a">`, "HTML_ATTRIBUTE_REMOVED", "srcset="],
    ["form action", `<form action="/x"><input type="text"></form>`, "HTML_ATTRIBUTE_REMOVED", "action="],
    ["file: link", `<a href="file:///etc/passwd">x</a>`, "HTML_URL_REMOVED", "file:"],
  ])("%s", (_name, body, code, gone) => {
    const { r, blocking, advisory } = codes(doc(body));
    expect(blocking).toEqual([]);
    expect(advisory).toContain(code);
    expect(r.ok).toBe(true);
    expect(r.removed).toBeGreaterThan(0);
    expect(r.html).toContain(gone);
    expect(r.vetted).not.toContain(gone);
  });

  it.each([
    ["link stylesheet", `<link rel="stylesheet" href="https://x/a.css">`, "HTML_ELEMENT_REMOVED", "<link"],
    ["base", `<base href="https://x/">`, "HTML_ELEMENT_REMOVED", "<base"],
    ["meta refresh", `<meta http-equiv="refresh" content="0;url=https://x/">`, "HTML_ELEMENT_REMOVED", "refresh"],
  ])("%s", (_name, head, code, gone) => {
    const { r, blocking, advisory } = codes(doc("<p>x</p>", `<title>t</title>${head}`));
    expect(blocking).toEqual([]);
    expect(advisory).toContain(code);
    expect(r.ok).toBe(true);
    expect(r.vetted).not.toContain(gone);
    expect(r.vetted).toContain("<p>x</p>");
  });

  it("keeps the rest of the document intact around what it removes", () => {
    const { r } = codes(doc(`<main><h1>Title</h1><script>x()</script><p onclick="y()" class="lead">Text</p><svg viewBox="0 0 1 1"><rect width="1" height="1"/></svg><button type="button">Go</button><next-route-announcer></next-route-announcer></main>`));
    expect(r.ok).toBe(true);
    expect(r.removed).toBe(2);
    expect(r.vetted).toContain('<h1>Title</h1><p class="lead">Text</p><svg viewBox="0 0 1 1"><rect width="1" height="1"></rect></svg><button type="button">Go</button><next-route-announcer></next-route-announcer>');
    expect(r.diagnostics.map((d) => d.code).sort()).toEqual(["HTML_EVENT_HANDLER_REMOVED", "HTML_SCRIPT_REMOVED"]);
  });

  it("returns the source unchanged when nothing had to be removed", () => {
    const { r } = codes(doc(`<p>Plain</p>`));
    expect(r.removed).toBe(0);
    expect(r.vetted).toBe(r.html);
  });
});

describe("preflight reports what is not fetched without refusing the document", () => {
  it.each([
    ["external image", `<img src="https://example.com/a.png" alt="a">`, "HTML_EXTERNAL_RESOURCE"],
    ["relative image", `<img src="images/a.png" alt="a">`, "HTML_EXTERNAL_RESOURCE"],
    ["file image", `<img src="file:///etc/passwd" alt="a">`, "HTML_EXTERNAL_RESOURCE"],
    ["video", `<video src="x.mp4"></video>`, "HTML_EXTERNAL_RESOURCE"],
    ["svg image", `<svg><image href="https://x/y.png"/></svg>`, "HTML_EXTERNAL_RESOURCE"],
    ["non-base64 data image", `<img src="data:image/png,hello" alt="a">`, "HTML_INLINE_IMAGE_INVALID"],
    ["style attribute with external url", `<p style="background: url(https://x/y.png)">x</p>`, "CSS_EXTERNAL_RESOURCE"],
    ["style attribute with relative url", `<p style="background: url('bg.png')">x</p>`, "CSS_EXTERNAL_RESOURCE"],
  ])("%s", (_name, body, code) => {
    const { r, blocking, advisory } = codes(doc(body));
    expect(blocking).toEqual([]);
    expect(advisory).toContain(code);
    expect(r.ok).toBe(true);
    expect(r.removed).toBe(0);
  });

  it.each([
    ["css import", `<style>@import url("https://x/a.css");</style>`, "CSS_IMPORT_IGNORED"],
    ["css external url", `<style>body { background: url(https://x/a.png) }</style>`, "CSS_EXTERNAL_RESOURCE"],
    ["css external font", `<style>@font-face { font-family: X; src: url(https://x/a.woff2) }</style>`, "CSS_EXTERNAL_RESOURCE"],
    ["css expression", `<style>p { width: expression(1) }</style>`, "CSS_UNSAFE_CONSTRUCT"],
  ])("%s", (_name, head, code) => {
    const { r, blocking, advisory } = codes(doc("<p>x</p>", `<title>t</title>${head}`));
    expect(blocking).toEqual([]);
    expect(advisory).toContain(code);
    expect(r.ok).toBe(true);
  });

  it("accepts inline svg data images and inline fonts, counting their bytes against the budget", () => {
    const font = Buffer.alloc(100 * 1024, 1).toString("base64");
    const { r, blocking } = codes(doc(`<img src="data:image/svg+xml;base64,PHN2Zy8+" alt="a">`, `<title>t</title><style>@font-face { font-family: X; src: url(data:font/woff2;base64,${font}) }</style>`));
    expect(r.diagnostics.filter((d) => d.code === "HTML_INLINE_IMAGE_INVALID")).toEqual([]);
    expect(blocking).toEqual([]);
    const over = codes(doc(`<p>x</p>`, `<title>t</title><style>@font-face { font-family: X; src: url(data:font/woff2;base64,${font}) } @font-face { font-family: Y; src: url(data:font/woff2;base64,${font}) }</style>`));
    expect(over.blocking).toContain("HTML_INLINE_IMAGES_TOO_LARGE");
  });
});

describe("preflight still refuses what cannot be rendered within the limits", () => {
  it("caps single and total inline image bytes", () => {
    const big = Buffer.alloc(70 * 1024, 1).toString("base64");
    const single = codes(doc(`<img src="data:image/png;base64,${big}" alt="big">`));
    expect(single.blocking).toContain("HTML_INLINE_IMAGE_TOO_LARGE");

    const mid = Buffer.alloc(50 * 1024, 1).toString("base64");
    const total = codes(doc(`<img src="data:image/png;base64,${mid}" alt="a"><img src="data:image/png;base64,${mid}" alt="b"><img src="data:image/png;base64,${mid}" alt="c">`));
    expect(total.blocking).toContain("HTML_INLINE_IMAGES_TOO_LARGE");
  });

  it("refuses invalid UTF-8, NUL bytes and non-documents", () => {
    expect(codes(new Uint8Array([0xff, 0xfe, 0x3c, 0x70, 0x3e])).blocking).toContain("HTML_ENCODING_INVALID");
    expect(codes(new TextEncoder().encode("<p>a\u0000b</p>")).blocking).toContain("HTML_ENCODING_INVALID");
    expect(codes(new TextEncoder().encode("%PDF-1.4 not html at all")).blocking).toContain("HTML_NOT_A_DOCUMENT");
  });

  it("reports the source line of every removal without echoing the removed content", () => {
    const { r } = codes(doc("<p>ok</p>\n<p>ok</p>\n<script>alert(1)</script>"));
    const d = r.diagnostics.find((x) => x.code === "HTML_SCRIPT_REMOVED")!;
    expect(d.line).toBe(3);
    expect(d.element).toBe("script");
    expect(d.message).not.toContain("alert");
  });
});

describe("preflight source", () => {
  it("spells the NUL check as an escape, so git diffs the file as text", async () => {
    const { readFileSync } = await import("node:fs");
    const source = readFileSync(new URL("../src/preflight/preflight.ts", import.meta.url));
    expect(source.includes(0)).toBe(false);
  });
});
