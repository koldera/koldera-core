import { createHash } from "node:crypto";
import type { Page } from "playwright";
import type { SemanticFinding, SemanticNode } from "../contracts/types.js";

/** Raw per-element record produced inside the page (no ids yet; ids are computed here from the path). */
export interface RawElement {
  domPath: string;
  parentPath: string | null;
  tag: string;
  role: string;
  text: string | null;
  box: { x: number; y: number; width: number; height: number } | null;
  elementChildren: number;
  focusable: boolean;
  tabindex: number | null;
  cssOrder: number;
  inlineOrder: boolean;
  parentDisplay: string;
  parentFlexReversed: boolean;
}

const PREVIEW_LENGTH = 160;
const MAX_TAB_STOPS = 200;

/**
 * Walks the rendered DOM in document order (the reading order a static page actually has) and
 * records, per element: path, semantics, own text, rendered box, focusability and the CSS facts
 * that can make visual order and focus order disagree with DOM order. Runs inside Chromium's
 * utility world, so the page's own (disabled) scripts are never involved.
 */
export async function collectElements(page: Page, maxNodes: number): Promise<{ elements: RawElement[]; truncated: boolean }> {
  return page.evaluate(
    ({ max, previewLength }) => {
      const out: RawElement[] = [];
      let truncated = false;
      const body = document.body;
      if (!body) return { elements: out, truncated };
      const pathOf = (el: Element): string => {
        const parts: number[] = [];
        let cur: Element | null = el;
        while (cur && cur !== document.documentElement) {
          const parent: Element | null = cur.parentElement;
          if (!parent) break;
          parts.unshift(Array.prototype.indexOf.call(parent.children, cur));
          cur = parent;
        }
        return parts.join("/");
      };
      const inlineDisplays = new Set(["inline", "inline-block", "inline-flex", "inline-grid", "contents", "none"]);
      const isFocusable = (el: Element): boolean => {
        const tag = el.tagName.toLowerCase();
        const ti = el.getAttribute("tabindex");
        if (ti !== null && Number.parseInt(ti, 10) < 0) return false;
        if (tag === "a" && el.hasAttribute("href")) return true;
        if (["button", "input", "select", "textarea", "summary"].includes(tag)) return !el.hasAttribute("disabled");
        if (ti !== null && !Number.isNaN(Number.parseInt(ti, 10))) return true;
        return (el as HTMLElement).isContentEditable === true;
      };
      const walk = (el: Element, parentPath: string | null): void => {
        if (out.length >= max) {
          truncated = true;
          return;
        }
        const style = getComputedStyle(el);
        const parent = el.parentElement;
        const parentStyle = parent ? getComputedStyle(parent) : null;
        const children = Array.from(el.children);
        // What a reader would get from this element itself, in source order: its text nodes and inline
        // descendants (an inline image contributes its alt text in place — an empty alt is decorative
        // and yields nothing, a missing alt yields nothing either and is a finding elsewhere, never a
        // guess); block children are left out because they are nodes of their own (a list item next
        // to its nested list keeps only its own line).
        const inlineText = (node: Node): string => {
          if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? "";
          if (node.nodeType !== Node.ELEMENT_NODE) return "";
          const child = node as Element;
          if (child.tagName.toLowerCase() === "img") return ` ${child.getAttribute("alt") ?? ""} `;
          if (!inlineDisplays.has(getComputedStyle(child).display)) return "";
          return Array.from(child.childNodes).map(inlineText).join("");
        };
        let text: string | null = null;
        const tagName = el.tagName.toLowerCase();
        const raw = (tagName === "img" ? (el.getAttribute("alt") ?? "") : Array.from(el.childNodes).map(inlineText).join("")).replace(/\s+/g, " ").trim();
        if (raw.length > 0) text = raw.length > previewLength ? raw.slice(0, previewLength - 1) + "…" : raw;
        const rect = el.getBoundingClientRect();
        const box = rect.width > 0 || rect.height > 0 ? { x: Math.round(rect.x * 100) / 100, y: Math.round(rect.y * 100) / 100, width: Math.round(rect.width * 100) / 100, height: Math.round(rect.height * 100) / 100 } : null;
        const tabindexAttr = el.getAttribute("tabindex");
        const tabindex = tabindexAttr === null || Number.isNaN(Number.parseInt(tabindexAttr, 10)) ? null : Number.parseInt(tabindexAttr, 10);
        const orderValue = Number.parseInt(style.order || "0", 10);
        const domPath = pathOf(el);
        out.push({
          domPath,
          parentPath,
          tag: el.tagName.toLowerCase(),
          role: el.getAttribute("role") || el.tagName.toLowerCase(),
          text,
          box,
          elementChildren: children.length,
          focusable: isFocusable(el),
          tabindex,
          cssOrder: Number.isNaN(orderValue) ? 0 : orderValue,
          inlineOrder: (el as HTMLElement).style?.order !== undefined && (el as HTMLElement).style.order !== "",
          parentDisplay: parentStyle ? parentStyle.display : "none",
          parentFlexReversed: parentStyle ? /-reverse$/.test(parentStyle.flexDirection) : false,
        });
        for (const child of children) walk(child, domPath);
      };
      walk(body, null);
      return { elements: out, truncated };
    },
    { max: maxNodes, previewLength: PREVIEW_LENGTH },
  );
}

/** Opaque, deterministic per source and position; never a selector. */
export function nodeId(sourceSha256: string, domPath: string): string {
  return "h" + createHash("sha256").update(`${sourceSha256}:${domPath}`).digest("hex").slice(0, 16);
}

export function toNodes(elements: RawElement[], sourceSha256: string): SemanticNode[] {
  return elements.map((e, index) => ({
    nodeId: nodeId(sourceSha256, e.domPath),
    parentId: e.parentPath === null ? null : nodeId(sourceSha256, e.parentPath),
    role: e.role.slice(0, 64),
    logicalIndex: index,
    textPreview: e.text,
    boundingBox: e.box,
    private: {
      domPath: e.domPath,
      tag: e.tag,
      elementChildren: e.elementChildren,
      focusable: e.focusable,
      tabindex: e.tabindex,
      cssOrder: e.cssOrder,
      inlineOrder: e.inlineOrder,
      parentDisplay: e.parentDisplay,
      parentFlexReversed: e.parentFlexReversed,
    },
  }));
}

/**
 * Keyboard traversal with the real browser: Tab from the document start until focus returns to
 * the document or the first stop repeats. Reported as the observed sequence, never adjusted.
 */
export async function measureFocusOrder(page: Page, nodes: SemanticNode[]): Promise<{ measured: boolean; order: string[] }> {
  const byPath = new Map(nodes.map((n) => [n.private.domPath, n.nodeId]));
  if (!nodes.some((n) => n.private.focusable)) {
    return { measured: false, order: [] };
  }
  await page.evaluate(() => {
    (document.activeElement as HTMLElement | null)?.blur?.();
    document.body.focus();
  });
  const order: string[] = [];
  const seen = new Set<string>();
  for (let i = 0; i < MAX_TAB_STOPS; i++) {
    await page.keyboard.press("Tab");
    const domPath = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body || el === document.documentElement) return null;
      const parts: number[] = [];
      let cur: Element | null = el;
      while (cur && cur !== document.documentElement) {
        const parent: Element | null = cur.parentElement;
        if (!parent) break;
        parts.unshift(Array.prototype.indexOf.call(parent.children, cur));
        cur = parent;
      }
      return parts.join("/");
    });
    if (domPath === null) break;
    if (seen.has(domPath)) break;
    seen.add(domPath);
    const id = byPath.get(domPath);
    if (id) order.push(id);
  }
  return { measured: true, order };
}

/**
 * Geometry-derived candidate: leaf (text-bearing) nodes sorted into 4 px bands by top edge, then
 * by left edge. Deliberately naive — it interleaves independent columns — and therefore reported
 * only as a candidate for the reviewer, never as the reading order.
 */
export function visualCandidate(nodes: SemanticNode[]): string[] {
  const leaves = nodes.filter((n) => n.textPreview !== null && n.boundingBox !== null && n.boundingBox.height > 0);
  const sorted = [...leaves].sort((a, b) => {
    const ya = Math.round(a.boundingBox!.y / 4);
    const yb = Math.round(b.boundingBox!.y / 4);
    if (ya !== yb) return ya - yb;
    if (a.boundingBox!.x !== b.boundingBox!.x) return a.boundingBox!.x - b.boundingBox!.x;
    return a.logicalIndex - b.logicalIndex;
  });
  return sorted.map((n) => n.nodeId);
}

/**
 * Deterministic candidate findings. Only constructs that provably change the order — CSS `order`,
 * reversed flex direction, positive tabindex, a measured focus sequence that leaves DOM order —
 * become "suspected" findings; a mere geometry/DOM difference is a manual-review note.
 */
export function deriveFindings(nodes: SemanticNode[], focus: { measured: boolean; order: string[] }, visual: string[]): SemanticFinding[] {
  const findings: SemanticFinding[] = [];
  const byParent = new Map<string | null, SemanticNode[]>();
  for (const node of nodes) {
    const list = byParent.get(node.parentId) ?? [];
    list.push(node);
    byParent.set(node.parentId, list);
  }
  for (const [parentId, siblings] of byParent) {
    if (parentId === null || siblings.length < 2) continue;
    const parent = nodes.find((n) => n.nodeId === parentId)!;
    const display = siblings[0]!.private.parentDisplay;
    if (!/^(flex|grid|inline-flex|inline-grid)$/.test(display)) continue;
    const domOrder = siblings.map((s) => s.logicalIndex);
    const visualOrder = [...siblings].sort((a, b) => a.private.cssOrder - b.private.cssOrder || a.logicalIndex - b.logicalIndex).map((s) => s.logicalIndex);
    if (siblings[0]!.private.parentFlexReversed) visualOrder.reverse();
    if (domOrder.join(",") !== visualOrder.join(",")) {
      const label = (index: number) => String(domOrder.indexOf(index) + 1);
      findings.push({
        code: siblings[0]!.private.parentFlexReversed && siblings.every((s) => s.private.cssOrder === 0) ? "FLEX_DIRECTION_REVERSES_SIBLINGS" : "CSS_ORDER_REORDERS_SIBLINGS",
        category: "suspected_semantic_mismatch",
        message: `the children of <${parent.private.tag}> are shown in a different sequence than the DOM (reading) order because of CSS ${siblings[0]!.private.parentFlexReversed ? "flex-direction" : "order"}; a reader using the DOM meets them in another order than a sighted reader — decide which sequence is the intended one`,
        nodeIds: siblings.map((s) => s.nodeId),
        evidence: `DOM: ${domOrder.map(label).join(", ")}; visual: ${visualOrder.map(label).join(", ")} (numbers are DOM positions within the group)`,
      });
    }
  }
  const positive = nodes.filter((n) => n.private.tabindex !== null && n.private.tabindex > 0);
  if (positive.length > 0) {
    findings.push({
      code: "POSITIVE_TABINDEX",
      category: "suspected_semantic_mismatch",
      message: `${positive.length} element(s) use a positive tabindex, which pulls keyboard focus out of the document order; focus order is reported separately and is never repaired by moving content`,
      nodeIds: positive.map((n) => n.nodeId),
      evidence: positive.map((n) => `<${n.private.tag}> tabindex=${n.private.tabindex}`).join("; ").slice(0, 500),
    });
  }
  if (focus.measured) {
    const domFocusables = nodes.filter((n) => n.private.focusable).map((n) => n.nodeId);
    const observed = focus.order.filter((id) => domFocusables.includes(id));
    const expected = domFocusables.filter((id) => observed.includes(id));
    if (observed.join(",") !== expected.join(",")) {
      const name = (id: string) => nodes.find((n) => n.nodeId === id)?.textPreview ?? id;
      findings.push({
        code: "FOCUS_ORDER_DIFFERS_FROM_DOM",
        category: "suspected_semantic_mismatch",
        message: "the measured keyboard focus sequence does not follow the document order; check that the order is meaningful (WCAG 2.4.3 allows more than one meaningful sequence)",
        nodeIds: observed,
        evidence: `keyboard: ${observed.map(name).join(" → ")}`.slice(0, 500),
      });
    }
  }
  // Chromium's print pipeline never paginates content laid out at negative coordinates, so text
  // parked off-canvas (the `left:-10000px` "visually hidden" pattern) reaches the HTML reader but
  // not the PDF reader. Reported, never moved: which of the two the author meant is theirs to say.
  const offscreen = nodes.filter((n) => n.textPreview !== null && n.boundingBox !== null && (n.boundingBox.x + n.boundingBox.width <= 0 || n.boundingBox.y + n.boundingBox.height <= 0));
  if (offscreen.length > 0) {
    findings.push({
      code: "OFFSCREEN_TEXT_NOT_IN_PDF",
      category: "manual_review",
      message: `${offscreen.length} element(s) with text are positioned entirely off the canvas; the PDF renderer does not draw them, so the generated PDF lacks text the HTML page still exposes to assistive technology — keep it in flow or confirm the difference is intended`,
      nodeIds: offscreen.map((n) => n.nodeId),
      evidence: offscreen.map((n) => `<${n.private.tag}> "${n.textPreview}" at ${n.boundingBox!.x},${n.boundingBox!.y}`).join("; ").slice(0, 500),
    });
  }
  const domLeaves = nodes.filter((n) => visual.includes(n.nodeId)).map((n) => n.nodeId);
  if (visual.length > 1 && domLeaves.join(",") !== visual.join(",")) {
    findings.push({
      code: "VISUAL_ORDER_DIFFERS_FROM_DOM",
      category: "manual_review",
      message: "a top-to-bottom, left-to-right reading of the rendered page does not follow the document order; that is expected for columns and sidebars and is not a failure by itself — a person decides whether the document order is meaningful",
      nodeIds: [],
      evidence: null,
    });
  }
  return findings;
}
