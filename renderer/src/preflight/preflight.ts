import { limitOf } from "../limits.js";
import { parse, serialize, type DefaultTreeAdapterTypes as T } from "parse5";
import type { Diagnostic, DiagnosticCode } from "../contracts/types.js";
import { contentLanguageTag } from "../render/language.js";

export interface PreflightLimits {
  maxInlineImageBytes?: number;
  maxTotalInlineImageBytes?: number;
}

export interface PreflightOutcome {
  ok: boolean;
  /** Decoded source, unchanged. */
  html: string;
  /**
   * What the renderer prints: the source with everything that could only execute, navigate or
   * fetch removed (scripts, frames, plug-ins, `<link>`, `<base>`, meta refresh, event handlers,
   * script-scheme URLs). Identical to `html` when nothing was removed.
   */
  vetted: string;
  /** Number of elements and attributes removed from `vetted`; the source-order fix refuses such documents. */
  removed: number;
  diagnostics: Diagnostic[];
}

/**
 * Content boundary: the HTML is rendered as static content. Whatever needs a script engine, a network
 * or host files is removed from the rendered copy or not fetched, and each case is reported with a
 * line number. The document is refused only when it cannot be rendered at all or exceeds the
 * inline-resource budget. The renderer's sandbox is a second gate behind this one.
 */
export function preflight(bytes: Uint8Array, limits: PreflightLimits): PreflightOutcome {
  const diagnostics: Diagnostic[] = [];
  const add = (code: DiagnosticCode, message: string, blocking: boolean, line: number | null = null, element: string | null = null) => {
    if (diagnostics.length < 500) diagnostics.push({ code, message, blocking, line, element });
  };

  let html: string;
  try {
    html = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
  } catch {
    add("HTML_ENCODING_INVALID", "the source is not valid UTF-8", true);
    return { ok: false, html: "", vetted: "", removed: 0, diagnostics };
  }
  if (html.includes("\u0000")) {
    add("HTML_ENCODING_INVALID", "the source contains NUL bytes", true);
    return { ok: false, html: "", vetted: "", removed: 0, diagnostics };
  }

  const document = parse(html, { sourceCodeLocationInfo: true });
  const budget = { used: 0 };
  const state: State = { elementsSeen: 0, doctype: false, htmlLang: false, title: false, removed: 0, pragmaLanguage: null };
  for (const child of document.childNodes) {
    if (child.nodeName === "#documentType") state.doctype = true;
  }
  walk(document, add, limits, budget, state);
  if (state.elementsSeen === 0 && !state.doctype && !/<html[\s>]/i.test(html)) {
    add("HTML_NOT_A_DOCUMENT", "the source contains no HTML elements", true);
  }
  if (!state.htmlLang) {
    // The renderer prints with the content-language pragma's single tag when <html lang> is missing (render/offline.ts).
    const message = state.pragmaLanguage
      ? `the <html> element has no lang attribute; the PDF language was taken from <meta http-equiv="content-language"> ("${state.pragmaLanguage}"). Add lang to <html>`
      : "the <html> element has no lang attribute; the PDF language cannot be derived from the source";
    add("HTML_LANG_MISSING", message, false, 1, "html");
  }
  if (!state.title) add("HTML_TITLE_MISSING", "the document has no non-empty <title>; the PDF will have no title to display", false, null, "title");
  if (budget.used > limitOf(limits.maxTotalInlineImageBytes)) {
    add("HTML_INLINE_IMAGES_TOO_LARGE", `inline images total ${budget.used} bytes, above the limit of ${limits.maxTotalInlineImageBytes}`, true);
  }
  const ok = !diagnostics.some((d) => d.blocking);
  return { ok, html, vetted: ok ? (state.removed > 0 ? serialize(document) : html) : "", removed: state.removed, diagnostics };
}

type Add = (code: DiagnosticCode, message: string, blocking: boolean, line?: number | null, element?: string | null) => void;
type State = { elementsSeen: number; doctype: boolean; htmlLang: boolean; title: boolean; removed: number; pragmaLanguage: string | null };

const HTML_NS = "http://www.w3.org/1999/xhtml";

/** Elements that exist only to execute, embed another document, navigate or fetch; removed from the rendered copy. */
const REMOVED_ELEMENTS = new Set(["script", "iframe", "frame", "frameset", "object", "embed", "applet", "base", "link", "template", "portal"]);

/** Attributes that only fetch or navigate; removed from the rendered copy. `srcset`/`imagesrcset` go so an inline `src` wins. */
const REMOVED_ATTRIBUTES = new Set(["ping", "srcset", "imagesrcset", "formaction", "action", "manifest", "background", "poster"]);

/** Attributes whose value is a URL the renderer may follow (as a link) or fetch (as a resource). */
const URL_ATTRIBUTES = new Set(["href", "src", "cite", "data", "longdesc", "usemap", "xlink:href"]);
const LINK_SCHEMES = new Set(["http", "https", "mailto", "tel"]);
const DATA_IMAGE = /^data:image\/(png|jpe?g|gif|webp|bmp|svg\+xml);base64,([A-Za-z0-9+/=\s]+)$/i;

function walk(node: T.ParentNode, add: Add, limits: PreflightLimits, budget: { used: number }, state: State): void {
  for (const child of [...node.childNodes]) {
    if (!isElement(child)) continue;
    const line = child.sourceCodeLocation?.startLine ?? null;
    const name = child.tagName.toLowerCase();
    // Implied html/head/body carry no source location; only elements the author wrote count.
    if (child.sourceCodeLocation) state.elementsSeen++;
    if (child.namespaceURI === HTML_NS && name === "script") {
      remove(node, child, state);
      add("HTML_SCRIPT_REMOVED", "scripts are never executed; the <script> element was left out of the rendered copy", false, line, name);
      continue;
    }
    if (child.namespaceURI === HTML_NS && REMOVED_ELEMENTS.has(name)) {
      remove(node, child, state);
      add("HTML_ELEMENT_REMOVED", `<${name}> cannot be rendered offline and was left out of the rendered copy`, false, line, name);
      continue;
    }
    if (child.namespaceURI === HTML_NS && name === "meta" && child.attrs.some((a) => a.name.toLowerCase() === "http-equiv" && a.value.trim().toLowerCase() === "refresh")) {
      remove(node, child, state);
      add("HTML_ELEMENT_REMOVED", "<meta http-equiv=\"refresh\"> would navigate away and was left out of the rendered copy", false, line, name);
      continue;
    }
    checkAttributes(child, name, line, add, limits, budget, state);
    if (child.namespaceURI === HTML_NS && name === "meta") {
      const attr = (n: string) => child.attrs.find((a) => a.name.toLowerCase() === n)?.value;
      const content = attr("content");
      if ((attr("http-equiv") ?? "").trim().toLowerCase() === "content-language" && content !== undefined) {
        // HTML's pragma-set default language: one tag, a list is ignored; the last pragma wins (render/offline.ts applies the same rule).
        state.pragmaLanguage = contentLanguageTag(content);
      }
    }
    if (child.namespaceURI === HTML_NS && name === "style") {
      checkCss(textOf(child), line, add, limits, budget);
    }
    if (child.namespaceURI === HTML_NS && name === "title" && textOf(child).trim().length > 0) {
      state.title = true;
    }
    walk(child, add, limits, budget, state);
  }
}

function remove(parent: T.ParentNode, child: T.Element, state: State): void {
  parent.childNodes = parent.childNodes.filter((n) => n !== child);
  state.removed += 1;
}

function checkAttributes(el: T.Element, name: string, line: number | null, add: Add, limits: PreflightLimits, budget: { used: number }, state: State): void {
  let hasAlt = false;
  let hasSrc = false;
  const kept: typeof el.attrs = [];
  for (const attr of el.attrs) {
    const attrName = (attr.prefix ? attr.prefix + ":" : "") + attr.name.toLowerCase();
    if (attrName.startsWith("on")) {
      state.removed += 1;
      add("HTML_EVENT_HANDLER_REMOVED", `event handler attribute ${attrName} on <${name}> was left out of the rendered copy`, false, line, name);
      continue;
    }
    if (REMOVED_ATTRIBUTES.has(attrName)) {
      state.removed += 1;
      add("HTML_ATTRIBUTE_REMOVED", `attribute ${attrName} on <${name}> would fetch or navigate and was left out of the rendered copy`, false, line, name);
      continue;
    }
    if (name === "html" && attrName === "lang" && attr.value.trim().length > 0) state.htmlLang = true;
    if (name === "img" && attrName === "alt") hasAlt = true;
    if (attrName === "style") {
      checkCss(attr.value, line, add, limits, budget);
    } else if (name === "img" && attrName === "src") {
      hasSrc = true;
      checkImageSource(attr.value, line, name, add, limits, budget);
    } else if (URL_ATTRIBUTES.has(attrName)) {
      const scheme = schemeOf(attr.value);
      const isLink = name === "a" || name === "area";
      if (scheme !== null && !LINK_SCHEMES.has(scheme) && !(scheme === "data" && !isLink && DATA_IMAGE.test(attr.value.trim()))) {
        // javascript:, vbscript:, file:, blob:, data: on links… — dropped rather than rendered as something to follow.
        state.removed += 1;
        add("HTML_URL_REMOVED", `${attrName} on <${name}> uses the ${scheme}: scheme and was left out of the rendered copy`, false, line, name);
        continue;
      }
      if (!isLink && attr.value.trim().length > 0 && !/^\s*data:/i.test(attr.value) && !/^\s*#/.test(attr.value)) {
        add("HTML_EXTERNAL_RESOURCE", `${attrName} on <${name}> refers to a resource that is not fetched; the PDF is rendered without it`, false, line, name);
      }
    }
    kept.push(attr);
  }
  el.attrs = kept;
  if (name === "img" && hasSrc && !hasAlt) {
    add("HTML_IMAGE_ALT_MISSING", "an <img> has no alt attribute; a human must decide the text alternative", false, line, name);
  }
}

function checkImageSource(value: string, line: number | null, element: string, add: Add, limits: PreflightLimits, budget: { used: number }): void {
  const trimmed = value.trim();
  if (!/^data:/i.test(trimmed)) {
    add("HTML_EXTERNAL_RESOURCE", "the image is not an inline data: URL and is not fetched; the PDF is rendered without it", false, line, element);
    return;
  }
  const match = DATA_IMAGE.exec(trimmed);
  if (!match) {
    add("HTML_INLINE_IMAGE_INVALID", "inline images must be base64 data: URLs of type png, jpeg, gif, webp, bmp or svg; this one is not rendered", false, line, element);
    return;
  }
  const bytes = base64Bytes(match[2]!);
  if (bytes > limitOf(limits.maxInlineImageBytes)) {
    add("HTML_INLINE_IMAGE_TOO_LARGE", `an inline image is ${bytes} bytes, above the limit of ${limits.maxInlineImageBytes}`, true, line, element);
  }
  budget.used += bytes;
}

const CSS_URL = /\b(?:url|src|image-set)\s*\(\s*(['"]?)([^'")]*)\1\s*\)/gi;

function checkCss(css: string, line: number | null, add: Add, limits: PreflightLimits, budget: { used: number }): void {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, " ");
  const lower = text.toLowerCase();
  if (lower.includes("@import")) add("CSS_IMPORT_IGNORED", "@import is not fetched; the PDF is rendered without that stylesheet", false, line, "style");
  if (/expression\s*\(|behavior\s*:|-moz-binding|javascript\s*:/i.test(text)) add("CSS_UNSAFE_CONSTRUCT", "the stylesheet uses a construct the renderer ignores", false, line, "style");
  for (const match of text.matchAll(CSS_URL)) {
    const target = (match[2] ?? "").trim();
    if (/^data:font\//i.test(target) || /^data:application\/(font|x-font|vnd\.ms-fontobject)/i.test(target)) {
      // Inline fonts are rendered; their bytes count against the same budget as inline images.
      const payload = /;base64,([A-Za-z0-9+/=\s]+)$/i.exec(target);
      if (payload) budget.used += base64Bytes(payload[1]!);
      continue;
    }
    const image = DATA_IMAGE.exec(target);
    if (!image) {
      add("CSS_EXTERNAL_RESOURCE", "a stylesheet url() refers to a resource that is not fetched; the PDF is rendered without it", false, line, "style");
      continue;
    }
    const bytes = base64Bytes(image[2]!);
    if (bytes > limitOf(limits.maxInlineImageBytes)) {
      add("HTML_INLINE_IMAGE_TOO_LARGE", `an inline image is ${bytes} bytes, above the limit of ${limits.maxInlineImageBytes}`, true, line, "style");
    }
    budget.used += bytes;
  }
}

function schemeOf(value: string): string | null {
  const match = /^\s*([a-z][a-z0-9+.-]*):/i.exec(value);
  return match ? match[1]!.toLowerCase() : null;
}

function base64Bytes(payload: string): number {
  const clean = payload.replace(/\s+/g, "");
  const padding = clean.endsWith("==") ? 2 : clean.endsWith("=") ? 1 : 0;
  return Math.max(0, Math.floor((clean.length * 3) / 4) - padding);
}

function textOf(el: T.Element): string {
  let out = "";
  for (const child of el.childNodes) {
    if (child.nodeName === "#text") out += (child as T.TextNode).value;
  }
  return out;
}

function isElement(node: T.ChildNode): node is T.Element {
  return "tagName" in node && typeof (node as T.Element).tagName === "string";
}
