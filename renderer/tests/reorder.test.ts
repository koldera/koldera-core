import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, readdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { validateReorderResult, type HtmlReorderRequest } from "../src/contracts/types.js";
import { reorderSource } from "../src/remediation/reorder.js";
import { collectSemantic } from "../src/semantic/semantic.js";

const doc = (body: string, head = "") => `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Order fixture</title><style>body{margin:0;font-family:sans-serif}${head}</style></head><body>${body}</body></html>`;

const CSS_ORDER = doc(`<main><h1>Steps</h1><div style="display:flex;gap:16px"><p style="order:3">First in DOM</p><p style="order:2">Second in DOM</p><p style="order:1">Third in DOM</p></div></main>`);

async function workspace(html: string) {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-reorder-"));
  await mkdir(path.join(root, "in"));
  await mkdir(path.join(root, "out"));
  await writeFile(path.join(root, "in", "source.html"), html);
  return root;
}

function request(html: string, parentPath: string, order: number[], overrides: Partial<HtmlReorderRequest["limits"]> = {}): HtmlReorderRequest {
  return {
    specVersion: "1.0",
    jobId: "123e4567-e89b-42d3-a456-426614174000",
    inputPath: "/work/in/source.html",
    outputDirectory: "/work/out",
    baseSha256: createHash("sha256").update(html).digest("hex"),
    parentPath,
    orderedChildIndexes: order,
    limits: { timeoutSeconds: 60, maxBytes: 2 * 1024 * 1024, maxPages: 10, maxInlineImageBytes: 512 * 1024, maxTotalInlineImageBytes: 2 * 1024 * 1024, maxVisualDiffRatio: 0.02, ...overrides },
    page: { format: "A4", printBackground: true },
  };
}

async function run(html: string, parentPath: string, order: number[], overrides: Partial<HtmlReorderRequest["limits"]> = {}) {
  const root = await workspace(html);
  const result = await reorderSource(request(html, parentPath, order, overrides), { workRoot: root });
  expect(validateReorderResult(result), JSON.stringify(result)).toEqual([]);
  expect(result.manualReviewRequired).toBe(true);
  return { result, root };
}

describe("approved source-order fix (real Chromium)", () => {
  it("R02: applies the approved sibling order, removes only the inline order declarations, keeps the visual result and re-renders", async () => {
    // The flex row shows Third, Second, First; the reviewer approves exactly that DOM order: children 2, 1, 0 of the div at body/main/div = 1/0/1.
    const { result, root } = await run(CSS_ORDER, "1/0/1", [2, 1, 0]);

    expect(result.status, JSON.stringify(result.error)).toBe("applied");
    expect(result.error).toBeNull();
    expect(result.checks.domSequenceMatchesApproved).toBe(true);
    expect(result.checks.inlineOrderDeclarationsRemoved).toBe(3);
    expect(result.checks.visualWithinTolerance).toBe(true);
    expect(result.checks.visualDiffRatio).toBeLessThanOrEqual(0.02);
    expect(result.checks.pages).toBe(1);
    expect(result.artifacts.map((a) => a.kind).sort()).toEqual(["generated_pdf", "source_html"]);
    const corrected = await readFile(path.join(root, "out", "source.html"), "utf8");
    expect(corrected).toMatch(/Third in DOM[\s\S]*Second in DOM[\s\S]*First in DOM/);
    expect(corrected).not.toMatch(/order\s*:/);
    expect(corrected).toContain("display:flex;gap:16px");
    expect(corrected).toContain("<title>Order fixture</title>");
    const source = result.artifacts.find((a) => a.kind === "source_html")!;
    expect(createHash("sha256").update(await readFile(path.join(root, "out", "source.html"))).digest("hex")).toBe(source.sha256);
    const pdf = await readFile(path.join(root, "out", "generated.pdf"));
    expect(pdf.subarray(0, 5).toString("latin1")).toBe("%PDF-");
    expect(createHash("sha256").update(pdf).digest("hex")).toBe(result.artifacts.find((a) => a.kind === "generated_pdf")!.sha256);
    // The original source is untouched and an independent collection of the corrected copy agrees.
    expect(await readFile(path.join(root, "in", "source.html"), "utf8")).toBe(CSS_ORDER);
    const check = await collectSemantic({ specVersion: "1.0", jobId: result.jobId, inputPath: "/work/in/source.html", limits: { timeoutSeconds: 30, maxBytes: 2 * 1024 * 1024, maxNodes: 5000, maxInlineImageBytes: 512 * 1024, maxTotalInlineImageBytes: 2 * 1024 * 1024 } }, { workRoot: await (async () => { const r = await workspace(corrected); return r; })() });
    expect(check.nodes.filter((n) => n.role === "p").map((n) => n.textPreview)).toEqual(["Third in DOM", "Second in DOM", "First in DOM"]);
    expect(check.findings.some((f) => f.code === "CSS_ORDER_REORDERS_SIBLINGS")).toBe(false);
  });

  it("keeps keyboard focus consistent with the new DOM order when the siblings contain links", async () => {
    const html = doc(`<nav style="display:flex"><a href="#one" style="order:2">One</a><a href="#two" style="order:1">Two</a></nav>`);
    const { result } = await run(html, "1/0", [1, 0]);

    expect(result.status, JSON.stringify(result.error)).toBe("applied");
    expect(result.checks.focusOrderMeasured).toBe(true);
    expect(result.checks.focusSequenceFollowsDom).toBe(true);
    // Neither fragment has a target: the printed copy drops both links and says so.
    expect(result.diagnostics.find((d) => d.code === "HTML_RELATIVE_LINK_REMOVED")?.message).toMatch(/^2 links /);
  });

  it("refuses a permutation that would visibly move content (no CSS order to compensate)", async () => {
    const html = doc(`<main><p>Alpha</p><p>Beta</p><p>Gamma</p></main>`);
    const { result, root } = await run(html, "1/0", [2, 0, 1]);

    expect(result.status).toBe("failed");
    expect(result.error?.code).toBe("ORDER_FIX_REGRESSION");
    expect(result.error?.message).toContain("visual");
    expect(result.checks.visualWithinTolerance).toBe(false);
    expect(result.checks.visualDiffRatio).toBeGreaterThan(0.02);
    expect(result.artifacts).toEqual([]);
    expect(await readdir(path.join(root, "out"))).toEqual([]);
  });

  it.each([
    ["identity", CSS_ORDER, "1/0/1", [0, 1, 2], "already"],
    ["incomplete", CSS_ORDER, "1/0/1", [1, 0], "complete"],
    ["duplicate", CSS_ORDER, "1/0/1", [0, 0, 1], "permutation"],
    ["out of range", CSS_ORDER, "1/0/1", [0, 1, 7], "permutation"],
    ["unknown parent", CSS_ORDER, "1/9", [1, 0], "parent"],
    ["approved order differs from the visual order the CSS produces", CSS_ORDER, "1/0/1", [1, 2, 0], "visual order"],
    ["order from a stylesheet, not inline", doc(`<div class="row"><p class="a">A</p><p class="b">B</p></div>`, `.row{display:flex}.a{order:2}.b{order:1}`), "1/0", [1, 0], "stylesheet"],
    ["positive tabindex inside the group", doc(`<div style="display:flex"><p style="order:2"><a href="#x" tabindex="2">A</a></p><p style="order:1">B</p></div>`), "1/0", [1, 0], "tabindex"],
    ["table rows", doc(`<table><tr><td>r1</td></tr><tr><td>r2</td></tr></table>`), "1/0/0", [1, 0], "table"],
  ])("refuses %s", async (_name, html, parentPath, order, fragment) => {
    const { result, root } = await run(html, parentPath, order);
    expect(result.status).toBe("unsupported");
    expect(result.error?.code).toBe("ORDER_FIX_UNSUPPORTED");
    expect(result.error?.message.toLowerCase()).toContain(fragment);
    expect(result.artifacts).toEqual([]);
    expect(await readdir(path.join(root, "out"))).toEqual([]);
  });

  it("refuses a stale approval and content the preflight refuses", async () => {
    const root = await workspace(CSS_ORDER);
    const stale = await reorderSource({ ...request(CSS_ORDER, "1/0/1", [2, 1, 0]), baseSha256: "0".repeat(64) }, { workRoot: root });
    expect(stale.status).toBe("failed");
    expect(stale.error?.code).toBe("INVALID_INPUT");
    expect(stale.error?.message).toContain("stale");

    // A document that renders only after removals is not corrected in place: the corrected source would silently lose them.
    const scripted = doc(`<div style="display:flex"><p style="order:2">A</p><p style="order:1">B</p></div><script>1</script>`);
    const { result } = await run(scripted, "1/0", [1, 0]);
    expect(result.status).toBe("unsupported");
    expect(result.error?.code).toBe("ORDER_FIX_UNSUPPORTED");
    expect(result.error?.message).toContain("self-contained static HTML");
    expect(result.artifacts).toEqual([]);
  });
});
