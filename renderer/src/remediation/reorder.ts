import { createHash } from "node:crypto";
import { readFile, rename, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { parse, serialize, type DefaultTreeAdapterTypes as T } from "parse5";
import { PNG } from "pngjs";
import type { Diagnostic, HtmlReorderRequest, HtmlReorderResult, HtmlSemanticResult, ReorderErrorCode, SemanticNode } from "../contracts/types.js";
import { preflight } from "../preflight/preflight.js";
import { NOT_LAUNCHED } from "../render/isolated.js";
import { offlineDiagnostics } from "../render/offline.js";
import { renderVetted } from "../render/renderer.js";
import { collectVetted } from "../semantic/semantic.js";

export interface ReorderOptions {
  workRoot: string;
}

const TABLE_TAGS = new Set(["table", "thead", "tbody", "tfoot", "tr", "colgroup", "select", "datalist", "optgroup"]);
const SOURCE_NAME = "source.html";
const PDF_NAME = "generated.pdf";

/**
 * Reorders ONE approved sibling group in a copy of the source (dropping only those siblings' inline
 * `order` declarations that encoded the visual order), re-renders, and checks that the DOM sequence
 * is the approved one, focus follows it and the page looks the same within the pixel tolerance.
 * Anything else is refused or rolled back; the original is never modified.
 */
export async function reorderSource(request: HtmlReorderRequest, options: ReorderOptions): Promise<HtmlReorderResult> {
  const inputPath = path.join(options.workRoot, "in", SOURCE_NAME);
  let engineVersion = NOT_LAUNCHED;
  const checks = (): HtmlReorderResult["checks"] => ({ domSequenceMatchesApproved: null, focusOrderMeasured: null, focusSequenceFollowsDom: null, inlineOrderDeclarationsRemoved: 0, visualDiffRatio: null, visualWithinTolerance: null, pages: null });
  const base = (status: HtmlReorderResult["status"], code: ReorderErrorCode | null, message: string, extra: Partial<HtmlReorderResult> = {}): HtmlReorderResult => ({
    specVersion: "1.0",
    jobId: request.jobId,
    status,
    engineVersion,
    baseSha256: request.baseSha256,
    checks: checks(),
    artifacts: [],
    diagnostics: [],
    manualReviewRequired: true,
    error: code ? { code, message } : null,
    ...extra,
  });
  const unsupported = (message: string, extra: Partial<HtmlReorderResult> = {}) => base("unsupported", "ORDER_FIX_UNSUPPORTED", message, extra);

  let bytes: Buffer;
  try {
    const info = await stat(inputPath);
    if (info.size > request.limits.maxBytes) {
      return base("failed", "LIMIT_EXCEEDED", `source is ${info.size} bytes, above maxBytes=${request.limits.maxBytes}`);
    }
    bytes = await readFile(inputPath);
  } catch {
    return base("failed", "INVALID_INPUT", "source document is missing or unreadable");
  }
  if (createHash("sha256").update(bytes).digest("hex") !== request.baseSha256) {
    return base("failed", "INVALID_INPUT", "the source does not match baseSha256; the approval is stale");
  }
  const checked = preflight(bytes, { maxInlineImageBytes: request.limits.maxInlineImageBytes, maxTotalInlineImageBytes: request.limits.maxTotalInlineImageBytes });
  if (!checked.ok) {
    return base("unsupported", "UNSUPPORTED_CONTENT", "the source cannot be rendered as static HTML", { diagnostics: checked.diagnostics });
  }
  if (checked.removed > 0) {
    // The rendered copy differs from the source (scripts, frames, handlers left out); a corrected
    // source derived from it would silently drop them, and the snapshot's DOM paths belong to the
    // rendered copy. Only self-contained static HTML is corrected in place.
    return unsupported("the source contains scripts, frames, plug-ins or event handlers; the source-order fix applies only to self-contained static HTML", { diagnostics: checked.diagnostics });
  }

  // 1. Resolve the group in the parsed tree and refuse unsupported shapes.
  const document = parse(checked.html);
  const html = document.childNodes.find((n): n is T.Element => isElement(n) && n.tagName === "html");
  if (!html) {
    return unsupported("the source has no <html> element");
  }
  const parent = resolvePath(html, request.parentPath);
  if (!parent) {
    return unsupported("parent path does not resolve to an element");
  }
  if (TABLE_TAGS.has(parent.tagName)) {
    return unsupported(`the children of <${parent.tagName}> (table structure, form controls) are never reordered`);
  }
  const children = parent.childNodes.filter(isElement);
  if (children.length < 2) {
    return unsupported("the parent has fewer than two element children; nothing to permute");
  }
  const order = request.orderedChildIndexes;
  if (order.length !== children.length) {
    return unsupported(`the approved order must be a complete permutation of the parent's ${children.length} element children`);
  }
  const seen = new Set<number>();
  let identity = true;
  for (let i = 0; i < order.length; i++) {
    const index = order[i]!;
    if (index < 0 || index >= children.length || seen.has(index)) {
      return unsupported("the approved order is not a permutation of the parent's children");
    }
    seen.add(index);
    if (index !== i) identity = false;
  }
  if (identity) {
    return unsupported("the source is already in the approved order");
  }
  for (const child of children) {
    if (TABLE_TAGS.has(child.tagName) || child.tagName === "td" || child.tagName === "th") {
      return unsupported("table rows and cells are never reordered");
    }
    if (containsPositiveTabindex(child)) {
      return unsupported("a sibling contains a positive tabindex; the focus relationship is ambiguous and must be edited manually");
    }
  }

  // 2. Facts about the group as rendered (CSS order, focus) from the untouched source.
  const before = await collectVetted(checked.html, { jobId: request.jobId, limits: { timeoutSeconds: request.limits.timeoutSeconds, maxBytes: request.limits.maxBytes, maxNodes: 20000, maxInlineImageBytes: request.limits.maxInlineImageBytes, maxTotalInlineImageBytes: request.limits.maxTotalInlineImageBytes } }, request.baseSha256);
  engineVersion = before.engineVersion;
  if (before.status !== "completed") {
    return base("failed", before.error?.code ?? "RENDER_FAILED", before.error?.message ?? "the source could not be collected");
  }
  const groupNodes = children.map((_, i) => nodeAtPath(before, `${request.parentPath}/${i}`));
  if (groupNodes.some((n) => n === undefined)) {
    return unsupported("the sibling group is not visible in the rendered document");
  }
  const withOrder = groupNodes.filter((n) => n!.private.cssOrder !== 0);
  let removeInline = false;
  if (withOrder.length > 0) {
    if (withOrder.some((n) => !n!.private.inlineOrder)) {
      return unsupported("the visual order comes from a stylesheet rule (not an inline style); adjust the stylesheet manually so the approved DOM order is also the visual order");
    }
    const visual = [...groupNodes.keys()].sort((a, b) => groupNodes[a]!.private.cssOrder - groupNodes[b]!.private.cssOrder || a - b);
    if (groupNodes[0]!.private.parentFlexReversed) visual.reverse();
    if (visual.join(",") !== order.join(",")) {
      return unsupported("the approved order differs from the visual order the CSS produces; only a DOM order that matches what sighted readers already see can be applied without changing the layout");
    }
    removeInline = true;
  }

  // 3. Mutate a copy: permute the element children in place (whitespace text nodes keep their slots), drop inline `order`.
  let removed = 0;
  const permuted = order.map((i) => children[i]!);
  let slot = 0;
  parent.childNodes = parent.childNodes.map((node) => (isElement(node) ? permuted[slot++]! : node));
  if (removeInline) {
    for (const child of permuted) {
      const style = child.attrs.find((a) => a.name === "style");
      if (!style) continue;
      const kept = style.value.split(";").map((d) => d.trim()).filter((d) => d.length > 0 && !/^order\s*:/i.test(d));
      if (kept.length !== style.value.split(";").map((d) => d.trim()).filter((d) => d.length > 0).length) removed += 1;
      if (kept.length === 0) {
        child.attrs = child.attrs.filter((a) => a !== style);
      } else {
        style.value = kept.join(";");
      }
    }
  }
  const corrected = serialize(document);
  const correctedBytes = Buffer.from(corrected, "utf8");
  const correctedCheck = preflight(correctedBytes, { maxInlineImageBytes: request.limits.maxInlineImageBytes, maxTotalInlineImageBytes: request.limits.maxTotalInlineImageBytes });
  if (!correctedCheck.ok) {
    return base("failed", "ORDER_FIX_REGRESSION", "the corrected copy no longer passes the content preflight", { diagnostics: correctedCheck.diagnostics });
  }
  const correctedSha256 = createHash("sha256").update(correctedBytes).digest("hex");

  // 4. Render both, compare screenshots, and verify the corrected copy independently.
  const budgetMs = request.limits.timeoutSeconds * 1000;
  const original = await renderVetted(checked.html, { jobId: request.jobId, outputDirectory: request.outputDirectory, limits: request.limits, page: request.page, ...(request.content ? { content: request.content } : {}) }, { workRoot: options.workRoot, budgetMs, outputName: ".original.pdf", screenshot: true });
  if (original.status !== "completed" || !original.screenshot) {
    return base("failed", original.error?.code ?? "RENDER_FAILED", "the original could not be rendered for comparison");
  }
  await safeUnlink(path.join(options.workRoot, "out", ".original.pdf"));
  const rendered = await renderVetted(corrected, { jobId: request.jobId, outputDirectory: request.outputDirectory, limits: request.limits, page: request.page, ...(request.content ? { content: request.content } : {}) }, { workRoot: options.workRoot, budgetMs, outputName: PDF_NAME, screenshot: true });
  engineVersion = rendered.engineVersion;
  if (rendered.status !== "completed" || !rendered.screenshot) {
    await safeUnlink(path.join(options.workRoot, "out", PDF_NAME));
    return base("failed", rendered.error?.code ?? "RENDER_FAILED", rendered.error?.message ?? "the corrected copy could not be rendered");
  }
  const diff = pixelDiffRatio(original.screenshot, rendered.screenshot);
  const after = await collectVetted(corrected, { jobId: request.jobId, limits: { timeoutSeconds: request.limits.timeoutSeconds, maxBytes: request.limits.maxBytes, maxNodes: 20000, maxInlineImageBytes: request.limits.maxInlineImageBytes, maxTotalInlineImageBytes: request.limits.maxTotalInlineImageBytes } }, correctedSha256);
  const afterGroup = children.map((_, i) => nodeAtPath(after, `${request.parentPath}/${i}`));
  const domMatches = after.status === "completed" && afterGroup.every((node, i) => node !== undefined && same(node, groupNodes[order[i]!]!));
  const focusIds = afterGroup.filter((n): n is SemanticNode => n !== undefined).flatMap((n) => descendantsOf(after, n).filter((d) => d.private.focusable).map((d) => d.nodeId));
  const measured = after.status === "completed" && after.focusOrderMeasured;
  const observed = after.focusOrderNodeIds.filter((id) => focusIds.includes(id));
  const expected = focusIds.filter((id) => observed.includes(id));
  const focusFollows = measured ? observed.join(",") === expected.join(",") : null;
  const withinTolerance = diff <= request.limits.maxVisualDiffRatio;
  const result: HtmlReorderResult["checks"] = {
    domSequenceMatchesApproved: domMatches,
    focusOrderMeasured: measured,
    focusSequenceFollowsDom: focusFollows,
    inlineOrderDeclarationsRemoved: removed,
    visualDiffRatio: Math.round(diff * 1_000_000) / 1_000_000,
    visualWithinTolerance: withinTolerance,
    pages: rendered.pages,
  };
  if (!domMatches || !withinTolerance || focusFollows === false) {
    await safeUnlink(path.join(options.workRoot, "out", PDF_NAME));
    const reasons: string[] = [];
    if (!domMatches) reasons.push("the corrected copy does not read in the approved sequence");
    if (!withinTolerance) reasons.push(`the visual result changed (${(diff * 100).toFixed(2)} % of pixels differ, tolerance ${(request.limits.maxVisualDiffRatio * 100).toFixed(2)} %); edit the source manually`);
    if (focusFollows === false) reasons.push("keyboard focus would not follow the approved order");
    return base("failed", "ORDER_FIX_REGRESSION", reasons.join("; "), { checks: result });
  }
  const sourcePath = path.join(options.workRoot, "out", SOURCE_NAME);
  await writeFile(sourcePath + ".tmp", correctedBytes);
  await rename(sourcePath + ".tmp", sourcePath);
  // What the printed copy left out (dead links, images that did not load), as for a render.
  const diagnostics: Diagnostic[] = [...correctedCheck.diagnostics, ...(rendered.offline ? offlineDiagnostics(rendered.offline) : [])];
  return base("applied", null, "", {
    checks: result,
    diagnostics,
    artifacts: [
      { kind: "source_html", path: `${request.outputDirectory}/${SOURCE_NAME}`, sha256: correctedSha256, bytes: correctedBytes.length },
      ...rendered.artifacts.map((a) => ({ kind: "generated_pdf" as const, path: a.path, sha256: a.sha256, bytes: a.bytes })),
    ],
  });
}

function isElement(node: T.Node): node is T.Element {
  return "tagName" in node && typeof (node as T.Element).tagName === "string";
}

function resolvePath(html: T.Element, domPath: string): T.Element | null {
  let current: T.Element = html;
  if (domPath === "") return current;
  for (const part of domPath.split("/")) {
    const index = Number.parseInt(part, 10);
    const children = current.childNodes.filter(isElement);
    if (Number.isNaN(index) || index < 0 || index >= children.length) return null;
    current = children[index]!;
  }
  return current;
}

function containsPositiveTabindex(el: T.Element): boolean {
  const own = el.attrs.find((a) => a.name === "tabindex");
  if (own && Number.parseInt(own.value, 10) > 0) return true;
  return el.childNodes.filter(isElement).some(containsPositiveTabindex);
}

function nodeAtPath(snapshot: HtmlSemanticResult, domPath: string): SemanticNode | undefined {
  return snapshot.nodes.find((n) => n.private.domPath === domPath);
}

function descendantsOf(snapshot: HtmlSemanticResult, node: SemanticNode): SemanticNode[] {
  const prefix = node.private.domPath + "/";
  return snapshot.nodes.filter((n) => n.nodeId === node.nodeId || n.private.domPath.startsWith(prefix));
}

function same(a: SemanticNode, b: SemanticNode): boolean {
  return a.private.tag === b.private.tag && a.textPreview === b.textPreview && a.private.elementChildren === b.private.elementChildren;
}

/**
 * Fraction of differing pixels among the pixels that carry content (anything that is not the
 * dominant background colour in either image); a few moved glyphs on a large white page must not
 * hide behind the page's whitespace. Images of different size count as entirely different.
 */
export function pixelDiffRatio(a: Buffer, b: Buffer): number {
  const pa = PNG.sync.read(a);
  const pb = PNG.sync.read(b);
  if (pa.width !== pb.width || pa.height !== pb.height) return 1;
  const total = pa.width * pa.height;
  if (total === 0) return 0;
  const background = dominantColour(pa.data, total);
  let ink = 0;
  let differing = 0;
  for (let i = 0; i < total * 4; i += 4) {
    const aInk = pa.data[i] !== background[0] || pa.data[i + 1] !== background[1] || pa.data[i + 2] !== background[2];
    const bInk = pb.data[i] !== background[0] || pb.data[i + 1] !== background[1] || pb.data[i + 2] !== background[2];
    if (aInk || bInk) ink += 1;
    if (pa.data[i] !== pb.data[i] || pa.data[i + 1] !== pb.data[i + 1] || pa.data[i + 2] !== pb.data[i + 2] || pa.data[i + 3] !== pb.data[i + 3]) {
      differing += 1;
    }
  }
  return ink === 0 ? (differing > 0 ? 1 : 0) : Math.min(1, differing / ink);
}

function dominantColour(data: Buffer, total: number): [number, number, number] {
  const counts = new Map<number, number>();
  const step = Math.max(1, Math.floor(total / 20000));
  for (let p = 0; p < total; p += step) {
    const i = p * 4;
    const key = (data[i]! << 16) | (data[i + 1]! << 8) | data[i + 2]!;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  let best = 0xffffff;
  let bestCount = -1;
  for (const [key, count] of counts) {
    if (count > bestCount) {
      best = key;
      bestCount = count;
    }
  }
  return [(best >> 16) & 0xff, (best >> 8) & 0xff, best & 0xff];
}

async function safeUnlink(file: string): Promise<void> {
  try {
    await (await import("node:fs/promises")).unlink(file);
  } catch {
    // absent
  }
}
