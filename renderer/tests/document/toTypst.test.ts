import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { DocumentRefusedError, lit, toTypst, type RenderDocument } from "../../src/document/toTypst.js";

const fixture = (name: string): RenderDocument => JSON.parse(readFileSync(path.join(import.meta.dirname, "fixtures", name), "utf8")) as RenderDocument;
const opts = { imagesRoot: "/in/images" };
const doc = (body: unknown[], extra: Record<string, unknown> = {}) => ({ title: "T", language: "uk", body, ...extra }) as unknown as RenderDocument;

/** Removes string literals and comments so that only Typst syntax is left to scan. */
function outsideStrings(src: string): string {
  return src.replace(/"(?:[^"\\]|\\.)*"/g, '""').replace(/\/\/[^\n]*/g, "");
}

describe("toTypst", () => {
  it("emits user text only as string literals", () => {
    const src = toTypst(doc([{ type: "paragraph", content: ['#import "x": *'] }]), opts);
    expect(src).not.toMatch(/^#import "x"/m);
    expect(src).toContain('"#import \\"x\\": *"');
  });

  it("maps a heading level to a Typst heading of that depth", () => {
    expect(toTypst(doc([{ type: "heading", level: 2, text: "A" }]), opts)).toContain('#heading(level: 2)[#"A"]');
  });

  it("sets title and language", () => {
    const src = toTypst(doc([], { title: "Рахунок" }), opts);
    expect(src).toContain('#set document(title: "Рахунок"');
    expect(src).toContain('#set text(lang: "uk"');
  });

  it("splits a region subtag and ignores a script subtag", () => {
    expect(toTypst(doc([], { language: "en-US" }), opts)).toContain('lang: "en", region: "US"');
    expect(toTypst(doc([], { language: "zh-Hant-TW" }), opts)).toContain('lang: "zh", region: "TW"');
  });

  it("is deterministic", () => {
    const d = doc([{ type: "paragraph", content: ["a"] }], { language: "en" });
    expect(toTypst(d, opts)).toBe(toTypst(d, opts));
  });

  it("wraps header and footer in pdf.artifact and fills the page number", () => {
    const src = toTypst(doc([], { header: [{ type: "paragraph", content: ["H"] }], footer: [{ type: "pageNumber" }] }), opts);
    expect(src).toMatch(/header: pdf\.artifact\[/);
    expect(src).toMatch(/footer: pdf\.artifact\[[\s\S]*counter\(page\)\.display\(\)/);
  });

  it("builds a captioned table with a header row", () => {
    const src = toTypst(doc([{ type: "table", caption: "C", header: [["a", "b"]], rows: [["1", "2"]], columnAlign: ["left", "right"] }]), opts);
    expect(src).toContain('caption: [#"C"]');
    expect(src).toContain("columns: 2");
    expect(src).toContain("align: (left, right)");
    expect(src).toContain('table.header([#strong[#"a"]], [#strong[#"b"]])');
  });

  it("refuses a ragged table instead of guessing", () => {
    expect(() => toTypst(doc([{ type: "table", caption: "C", header: [["a", "b"]], rows: [["1"]] }]), opts)).toThrow(DocumentRefusedError);
  });

  it("describes images with alt or hides decorative ones in an artifact", () => {
    const src = toTypst(doc([{ type: "image", file: "logo.png", alt: "Logo" }, { type: "image", uploadId: "123e4567-e89b-42d3-a456-426614174000", decorative: true }]), opts);
    expect(src).toContain('image("/in/images/logo.png"');
    expect(src).toContain('alt: "Logo"');
    expect(src).toMatch(/pdf\.artifact\(image\("\/in\/images\/123e4567-e89b-42d3-a456-426614174000"/);
  });

  it("refuses an image with no source, an escaping path, and unknown block types", () => {
    expect(() => toTypst(doc([{ type: "image", alt: "x" }]), opts)).toThrow(DocumentRefusedError);
    expect(() => toTypst(doc([{ type: "image", file: "../x.png", alt: "x" }]), opts)).toThrow(DocumentRefusedError);
    expect(() => toTypst(doc([{ type: "video" }]), opts)).toThrow(DocumentRefusedError);
  });

  it("refuses unknown fonts, bad colours, bad lengths, bad urls and bad language tags", () => {
    expect(() => toTypst(doc([], { theme: { font: "Comic Sans" } }), opts)).toThrow(DocumentRefusedError);
    expect(() => toTypst(doc([], { theme: { accent: "red" } }), opts)).toThrow(DocumentRefusedError);
    expect(() => toTypst(doc([{ type: "spacer", height: "1em" }]), opts)).toThrow(DocumentRefusedError);
    expect(() => toTypst(doc([{ type: "link", text: "x", url: "javascript:alert(1)" }]), opts)).toThrow(DocumentRefusedError);
    expect(() => toTypst(doc([], { language: 'x"y' }), opts)).toThrow(DocumentRefusedError);
  });

  describe("injection", () => {
    const payloads = ['"]; #read("x")', "\\", '\\"', "$ x $", "@ref", "<label>", "*bold*", "_it_", "#set text(fill: red)", 'line1\nline2\r\n#import "a"', "\u0000\u0007", "\u2028", ']\n#include "/etc/passwd"\n['];

    it("lit() turns every payload into one escaped string literal", () => {
      for (const p of payloads) {
        const l = lit(p);
        expect(l.startsWith('"') && l.endsWith('"')).toBe(true);
        const inner = l.slice(1, -1);
        // Every quote and backslash inside is part of an escape, and no raw control character remains.
        expect(inner.replace(/\\(?:u\{[0-9a-f]+\}|.)/g, "")).not.toMatch(/["\\]/);
        expect(inner).not.toMatch(/[\u0000-\u001f\u2028\u2029]/);
      }
    });

    it("leaves no forbidden token outside string literals for fixtures and a hostile document", () => {
      const hostile = doc(
        [
          { type: "heading", level: 1, text: payloads[0] },
          { type: "paragraph", content: [...payloads, { strong: payloads[2] }, { emphasis: payloads[9] }, { link: { text: payloads[0], url: "https://example.org/a" } }] },
          { type: "list", items: [[payloads[0], { list: { items: [[payloads[1]]] } }]] },
          { type: "table", caption: payloads[0], header: [[payloads[0], payloads[1]]], rows: [[payloads[2], payloads[9]]] },
          { type: "keyValue", rows: [[payloads[0], payloads[9]]] },
          { type: "image", file: "logo.png", alt: payloads[0] },
        ],
        { title: payloads[0], header: [{ type: "paragraph", content: [payloads[0]] }], footer: [{ type: "paragraph", content: [payloads[0]] }, { type: "pageNumber" }] },
      );
      const sources = [hostile, ...["invoice.json", "report.json", "multilingual.json", "statement.json"].map(fixture)].map((d) => toTypst(d, opts));
      const forbidden = /#import|#include|\bread\s*\(|\bplugin\s*\(|\b(json|csv|yaml|toml|xml|cbor)\s*\(|@preview|@local|\bimport\b|\binclude\b/;
      for (const src of sources) expect(outsideStrings(src)).not.toMatch(forbidden);
      // The hostile payload is present, but only inside literals.
      expect(sources[0]).toContain('"\\"]; #read(\\"x\\")"');
    });
  });
});
