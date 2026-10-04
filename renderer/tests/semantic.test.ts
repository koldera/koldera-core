import { createHash } from "node:crypto";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { validateSemanticResult, type HtmlSemanticRequest, type SemanticNode } from "../src/contracts/types.js";
import { collectSemantic } from "../src/semantic/semantic.js";

const request = (): HtmlSemanticRequest => ({
  specVersion: "1.0",
  jobId: "123e4567-e89b-42d3-a456-426614174000",
  inputPath: "/work/in/source.html",
  limits: { timeoutSeconds: 30, maxBytes: 2 * 1024 * 1024, maxNodes: 5000, maxInlineImageBytes: 512 * 1024, maxTotalInlineImageBytes: 2 * 1024 * 1024 },
});

async function run(html: string) {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-sem-"));
  await mkdir(path.join(root, "in"));
  await writeFile(path.join(root, "in", "source.html"), html);
  const result = await collectSemantic(request(), { workRoot: root });
  expect(validateSemanticResult(result), JSON.stringify(result.error)).toEqual([]);
  return result;
}

const doc = (body: string, head = "") => `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Order fixture</title><style>body{margin:0;font-family:sans-serif}${head}</style></head><body>${body}</body></html>`;

// Deepest match: a <p> wrapping a single <a> carries the same preview as the link.
const byPreview = (nodes: SemanticNode[], text: string) => nodes.filter((n) => n.textPreview === text).at(-1)!;

describe("semantic collector (real Chromium, scripts off)", () => {
  it("R01: two independent columns are distinct sequences, never an automatic failure", async () => {
    const result = await run(
      doc(
        `<main style="display:flex;gap:20px"><section><h2>Left</h2><p>Left one</p><p>Left two</p></section><section><h2>Right</h2><p>Right one</p><p>Right two</p></section></main>`,
      ),
    );

    expect(result.status).toBe("completed");
    expect(result.sourceSha256).toMatch(/^[a-f0-9]{64}$/);
    expect(result.engineVersion).toMatch(/chromium \d+/);
    const leaves = result.nodes.filter((n) => n.textPreview !== null).map((n) => n.textPreview);
    expect(leaves).toEqual(["Left", "Left one", "Left two", "Right", "Right one", "Right two"]);
    // Geometry interleaves the columns; that is a candidate for a human, not a verdict.
    const candidate = result.visualOrderCandidateNodeIds.map((id) => result.nodes.find((n) => n.nodeId === id)!.textPreview);
    expect(candidate).toEqual(["Left", "Right", "Left one", "Right one", "Left two", "Right two"]);
    expect(result.findings.map((f) => f.code)).toEqual(["VISUAL_ORDER_DIFFERS_FROM_DOM"]);
    expect(result.findings[0]!.category).toBe("manual_review");
    expect(result.findings.some((f) => f.category === "confirmed_machine_failure")).toBe(false);
    const left = byPreview(result.nodes, "Left one");
    const right = byPreview(result.nodes, "Right one");
    expect(left.boundingBox!.x).toBeLessThan(right.boundingBox!.x);
    expect(Math.abs(left.boundingBox!.y - right.boundingBox!.y)).toBeLessThan(2);
    expect(left.parentId).not.toBe(right.parentId);
    expect(left.role).toBe("p");
    expect(result.focusOrderMeasured).toBe(false);
    expect(result.focusOrderNodeIds).toEqual([]);
  });

  it("R02: CSS order that reverses siblings is an evidence-backed candidate with the DOM and visual sequences", async () => {
    const result = await run(doc(`<div style="display:flex"><p style="order:2">First in DOM</p><p style="order:1">Second in DOM</p><p>Third in DOM</p></div>`));

    const finding = result.findings.find((f) => f.code === "CSS_ORDER_REORDERS_SIBLINGS")!;
    expect(finding).toBeDefined();
    expect(finding.category).toBe("suspected_semantic_mismatch");
    const first = byPreview(result.nodes, "First in DOM");
    const second = byPreview(result.nodes, "Second in DOM");
    const third = byPreview(result.nodes, "Third in DOM");
    expect(finding.nodeIds).toEqual([first.nodeId, second.nodeId, third.nodeId]);
    expect(finding.evidence).toContain("DOM: 1, 2, 3");
    expect(finding.evidence).toContain("visual: 3, 2, 1");
    expect(first.private.cssOrder).toBe(2);
    expect(first.private.inlineOrder).toBe(true);
    expect(third.private.cssOrder).toBe(0);
    expect(first.private.parentDisplay).toBe("flex");
    expect(second.boundingBox!.x).toBeGreaterThan(third.boundingBox!.x);
  });

  it("R03: positive tabindex is reported on the focus sequence, separately from reading order", async () => {
    const result = await run(doc(`<p><a href="#a" tabindex="3">Alpha</a></p><p><a href="#b" tabindex="1">Beta</a></p><p><a href="#c">Gamma</a></p>`));

    expect(result.focusOrderMeasured).toBe(true);
    const names = result.focusOrderNodeIds.map((id) => result.nodes.find((n) => n.nodeId === id)!.textPreview);
    expect(names).toEqual(["Beta", "Alpha", "Gamma"]);
    const positive = result.findings.find((f) => f.code === "POSITIVE_TABINDEX")!;
    expect(positive.category).toBe("suspected_semantic_mismatch");
    expect(positive.nodeIds).toHaveLength(2);
    const differs = result.findings.find((f) => f.code === "FOCUS_ORDER_DIFFERS_FROM_DOM")!;
    expect(differs.category).toBe("suspected_semantic_mismatch");
    expect(differs.evidence).toContain("Beta");
    expect(byPreview(result.nodes, "Alpha").private.focusable).toBe(true);
    expect(byPreview(result.nodes, "Alpha").private.tabindex).toBe(3);
    // Reading order stays the DOM order; nothing was "fixed" by reading geometry.
    const leaves = result.nodes.filter((n) => n.textPreview !== null && n.role === "a").map((n) => n.textPreview);
    expect(leaves).toEqual(["Alpha", "Beta", "Gamma"]);
  });

  it("node ids are opaque and stable per source; previews are bounded; paths are element-child indexes", async () => {
    const html = doc(`<main><h1>Title</h1><p>${"word ".repeat(80)}</p></main>`);
    const a = await run(html);
    const b = await run(html);
    const c = await run(doc(`<main><h1>Title</h1><p>other</p></main>`));

    expect(a.nodes.map((n) => n.nodeId)).toEqual(b.nodes.map((n) => n.nodeId));
    expect(a.nodes[2]!.nodeId).not.toBe(c.nodes[2]!.nodeId);
    for (const node of a.nodes) {
      expect(node.nodeId).toMatch(/^h[a-f0-9]{16}$/);
    }
    const p = a.nodes.find((n) => n.role === "p")!;
    expect(p.textPreview!.length).toBeLessThanOrEqual(160);
    expect(p.textPreview!.endsWith("…")).toBe(true);
    expect(p.private.domPath).toBe("1/0/1");
    expect(a.nodes[0]!.role).toBe("body");
    expect(a.nodes[0]!.parentId).toBeNull();
    expect(a.nodes[0]!.textPreview).toBeNull();
    expect(a.nodes[1]!.role).toBe("main");
    expect(a.nodes[1]!.private.elementChildren).toBe(2);
  });

  it("P1-A probe gaps: an image previews its alt text (empty alt = decorative = none) and a list item keeps its own text next to a nested list", async () => {
    const png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
    const r = await run(doc(`<main><p><img src="${png}" alt="Company logo"> described</p><p><img src="${png}" alt=""> decorative</p><p><img src="${png}"> missing</p><ul><li>Clothes <em>and</em> shoes<ul><li>Shirts</li></ul></li></ul></main>`));
    const images = r.nodes.filter((n) => n.private.tag === "img");
    expect(images.map((n) => n.textPreview)).toEqual(["Company logo", null, null]);
    // A paragraph reads its inline image's alt in place: "Company logo described", not "described".
    const paragraphs = r.nodes.filter((n) => n.private.tag === "p");
    expect(paragraphs.map((n) => n.textPreview)).toEqual(["Company logo described", "decorative", "missing"]);
    const items = r.nodes.filter((n) => n.private.tag === "li");
    expect(items.map((n) => n.textPreview)).toEqual(["Clothes and shoes", "Shirts"]);
    // Reading sequence keeps the container text before its nested leaves.
    const sequence = r.nodes.filter((n) => n.textPreview).map((n) => n.textPreview);
    expect(sequence.indexOf("Clothes and shoes")).toBeLessThan(sequence.indexOf("Shirts"));
  });

  it("P1-A probe gap (corpus html-17): text placed off-canvas is reported as dropped from the PDF; hidden and aria-hidden text is not", async () => {
    const r = await run(doc(`<style>.off{position:absolute;left:-10000px}.up{position:absolute;top:-5000px}</style><main><h1>Visible</h1><p class="off">Skip to content</p><p class="up">Above the page</p><p hidden>Hidden</p><p aria-hidden="true">Aria hidden</p><p>Body</p></main>`));
    expect(r.status).toBe("completed");
    const finding = r.findings.find((f) => f.code === "OFFSCREEN_TEXT_NOT_IN_PDF")!;
    expect(finding).toBeDefined();
    expect(finding.category).toBe("manual_review");
    const named = finding.nodeIds.map((id) => r.nodes.find((n) => n.nodeId === id)!.textPreview);
    expect(named).toEqual(["Skip to content", "Above the page"]);
    expect(finding.evidence).toContain("Skip to content");
    // In-flow, display:none and aria-hidden text are not off-canvas and must not be reported here.
    const plain = await run(doc(`<main><h1>Visible</h1><p hidden>Hidden</p><p aria-hidden="true">Aria hidden</p><p>Body</p></main>`));
    expect(plain.findings.some((f) => f.code === "OFFSCREEN_TEXT_NOT_IN_PDF")).toBe(false);
  });

  it("collects the DOM as rendered (scripts left out), refuses what preflight refuses and caps the node count", async () => {
    const scripted = await run(doc(`<p>x</p><script>1</script>`));
    expect(scripted.status).toBe("completed");
    expect(scripted.nodes.some((n) => n.private.tag === "script")).toBe(false);
    expect(scripted.nodes.some((n) => n.private.tag === "p")).toBe(true);

    const refused = await run("%PDF-1.4 not html");
    expect(refused.status).toBe("unsupported");
    expect(refused.error?.code).toBe("UNSUPPORTED_CONTENT");
    expect(refused.nodes).toEqual([]);

    const root = await mkdtemp(path.join(tmpdir(), "koldera-sem-"));
    await mkdir(path.join(root, "in"));
    await writeFile(path.join(root, "in", "source.html"), doc("<p>a</p>".repeat(20)));
    const capped = await collectSemantic({ ...request(), limits: { ...request().limits, maxNodes: 5 } }, { workRoot: root });
    expect(capped.status).toBe("failed");
    expect(capped.error?.code).toBe("LIMIT_EXCEEDED");
    expect(createHash("sha256").update("x").digest("hex")).toHaveLength(64);
  });
});
