/**
 * Structured document (contracts/document.schema.json) to Typst source, template version 1.
 * The source is built from a fixed template. Every user string reaches it through lit() and is
 * written as a Typst string literal inside `[#lit]` content, so no document text can be parsed as
 * markup or code. Anything outside the contract throws DocumentRefusedError (fail closed).
 */

export const TEMPLATE_VERSION = "1";

export class DocumentRefusedError extends Error {
  override name = "DocumentRefusedError";
}

export interface RenderDocument {
  title: string;
  language: string;
  page?: { size?: string; orientation?: string; margins?: string };
  theme?: { font?: string; fontSize?: number; accent?: string };
  header?: unknown[];
  footer?: unknown[];
  body: unknown[];
}

export interface ToTypstOptions {
  /** Directory of the images as a Typst root-relative path, for example /in/images. */
  imagesRoot: string;
}

const FONTS = ["Noto Sans", "Noto Serif", "Noto Sans Arabic", "Noto Sans Hebrew"];
const PAPER: Record<string, string> = { A4: "a4", A5: "a5", Letter: "us-letter", Legal: "us-legal" };
const LENGTH = /^[0-9]+(\.[0-9]+)?(mm|cm|in|pt)$/;
const URL = /^(https?:\/\/|mailto:)[^\s]+$/;
const COLOR = /^#[0-9a-fA-F]{6}$/;
const UPLOAD_ID = /^[0-9a-fA-F-]{36}$/;
const FILE = /^(?![/\\])(?![A-Za-z][A-Za-z0-9+.-]*:)(?!.*(^|\/)\.\.(\/|$))[^\\\x00-\x1f]+$/;

function refuse(message: string): never {
  throw new DocumentRefusedError(message);
}

/** A Typst string literal. Quotes, backslashes and every control character are escaped. */
export function lit(value: string): string {
  let out = '"';
  for (const ch of value.replace(/[�-�](?![�-�])|(?<![�-�])[�-�]/g, "�")) {
    const cp = ch.codePointAt(0)!;
    if (ch === "\\") out += "\\\\";
    else if (ch === '"') out += '\\"';
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else if (cp === 0) out += "\\u{fffd}";
    else if (cp < 0x20 || (cp >= 0x7f && cp <= 0x9f) || cp === 0x2028 || cp === 0x2029) out += `\\u{${cp.toString(16)}}`;
    else out += ch;
  }
  return out + '"';
}

const text = (s: unknown): string => lit(str(s, "text"));
const content = (s: unknown): string => `[#${text(s)}]`;

function str(v: unknown, what: string): string {
  if (typeof v !== "string") return refuse(`${what} must be a string`);
  return v;
}

function obj(v: unknown, what: string): Record<string, unknown> {
  if (typeof v !== "object" || v === null || Array.isArray(v)) return refuse(`${what} must be an object`);
  return v as Record<string, unknown>;
}

function arr(v: unknown, what: string): unknown[] {
  if (!Array.isArray(v)) return refuse(`${what} must be an array`);
  return v;
}

function length(v: unknown, what: string): string {
  const s = str(v, what);
  if (!LENGTH.test(s)) refuse(`${what} is not a length`);
  return s;
}

/** BCP 47 tag to Typst text arguments: primary language plus an optional two-letter region. */
function langArgs(tag: unknown): string {
  const parts = str(tag, "language").split("-");
  const primary = parts[0] ?? "";
  if (!/^[A-Za-z]{2,3}$/.test(primary) || parts.some((p) => !/^[A-Za-z0-9]{1,8}$/.test(p))) refuse("language is not a language tag");
  const region = parts.slice(1).find((p) => /^[A-Za-z]{2}$/.test(p));
  return `lang: ${lit(primary.toLowerCase())}` + (region ? `, region: ${lit(region.toUpperCase())}` : "");
}

/** The chosen font first, then every bundled font so that a script without glyphs falls back. */
function fontList(font: string): string {
  if (!FONTS.includes(font)) refuse("font is not a bundled font");
  return "(" + [font, ...FONTS.filter((f) => f !== font)].map(lit).join(", ") + ")";
}

/** `plain` drops link targets: a PDF/UA-1 artifact (page header and footer) may not contain links. */
function inline(piece: unknown, plain = false): string {
  if (typeof piece === "string") return `#${lit(piece)}`;
  const o = obj(piece, "inline");
  if ("strong" in o) return `#strong${content(o["strong"])}`;
  if ("emphasis" in o) return `#emph${content(o["emphasis"])}`;
  if ("link" in o) return plain ? `#${text(obj(o["link"], "link")["text"])}` : linkOf(obj(o["link"], "link"));
  return refuse("unknown inline piece");
}

function linkOf(l: Record<string, unknown>): string {
  const url = str(l["url"], "url");
  if (!URL.test(url)) refuse("link target is not an http, https or mailto address");
  return `#link(${lit(url)})${content(l["text"])}`;
}

const inlines = (pieces: unknown[], plain = false): string => `[${pieces.map((p) => inline(p, plain)).join("")}]`;

function listOf(ordered: unknown, items: unknown[]): string {
  const cells = items.map((item) => {
    const parts = arr(item, "list item").map((p) => {
      if (typeof p === "object" && p !== null && "list" in p) {
        const nested = obj((p as Record<string, unknown>)["list"], "list");
        return listOf(nested["ordered"], arr(nested["items"], "items"));
      }
      return inline(p);
    });
    return `[${parts.join("")}]`;
  });
  if (cells.length === 0) refuse("a list needs items");
  return `#${ordered === true ? "enum" : "list"}(${cells.join(", ")})`;
}

function image(b: Record<string, unknown>, o: ToTypstOptions): string {
  const hasId = "uploadId" in b;
  const hasFile = "file" in b;
  if (hasId === hasFile) refuse("an image needs exactly one source");
  let name: string;
  if (hasId) {
    name = str(b["uploadId"], "uploadId");
    if (!UPLOAD_ID.test(name)) refuse("uploadId is not an identifier");
  } else {
    name = str(b["file"], "file");
    if (!FILE.test(name)) refuse("image file path is not allowed");
  }
  const src = lit(`${o.imagesRoot}/${name}`);
  if (b["decorative"] === true) {
    if ("alt" in b) refuse("an image cannot be both described and decorative");
    return `#pdf.artifact(image(${src}))`;
  }
  // A missing description is passed through on purpose: Typst refuses it under PDF/UA-1.
  const alt = "alt" in b ? `, alt: ${text(b["alt"])}` : "";
  return `#image(${src}${alt})`;
}

function table(b: Record<string, unknown>): string {
  const header = arr(b["header"], "header").map((r) => arr(r, "header row").map((c) => str(c, "cell")));
  const rows = arr(b["rows"], "rows").map((r) => arr(r, "row").map((c) => str(c, "cell")));
  const columns = header[0]?.length ?? 0;
  if (columns === 0 || [...header, ...rows].some((r) => r.length !== columns)) refuse("table rows must be rectangular");
  let align = "";
  if (b["columnAlign"] !== undefined) {
    const a = arr(b["columnAlign"], "columnAlign").map((x) => (x === "left" || x === "center" || x === "right" ? x : refuse("unknown column alignment")));
    if (a.length !== columns) refuse("columnAlign must have one entry per column");
    align = `, align: (${a.join(", ")})`;
  }
  const rowHeaders = b["rowHeaders"] === true;
  // Header cells are strong, and the accent tint marks the header rows visibly.
  const head = `table.header(${header.flat().map((c) => `[#strong[#${lit(c)}]]`).join(", ")})`;
  const body = rows.flatMap((r) => r.map((c, i) => (rowHeaders && i === 0 ? `[#strong[#${lit(c)}]]` : content(c))));
  return `#figure(caption: ${content(b["caption"])}, table(columns: ${columns}${align}, fill: (_, y) => if y < ${header.length} { accent.lighten(85%) }, ${[head, ...body].join(", ")}))`;
}

function keyValue(b: Record<string, unknown>): string {
  const cells = arr(b["rows"], "rows").flatMap((r) => {
    const pair = arr(r, "pair");
    if (pair.length !== 2) refuse("a key-value row has two cells");
    return [`[#strong[#${text(pair[0])}]]`, content(pair[1])];
  });
  return `#table(columns: 2, stroke: none, ${cells.join(", ")})`;
}

function block(raw: unknown, o: ToTypstOptions, inPage: boolean): string {
  const b = obj(raw, "block");
  switch (b["type"]) {
    case "heading": {
      const level = b["level"];
      if (typeof level !== "number" || !Number.isInteger(level) || level < 1 || level > 6) refuse("heading level must be 1 to 6");
      // Page header and footer headings are decoration: strong text, not part of the heading order.
      return inPage ? `#par[#strong${content(b["text"])}]` : `#heading(level: ${level})${content(b["text"])}`;
    }
    case "paragraph": {
      const inner = inlines(arr(b["content"], "content"), inPage);
      const lang = b["lang"] === undefined ? undefined : langArgs(b["lang"]);
      const font = b["font"] === undefined ? undefined : fontList(str(b["font"], "font"));
      if (!lang && !font) return `#par${inner}`;
      const args = [lang, font && `font: ${font}`].filter(Boolean).join(", ");
      return `#par[#text(${args})${inner}]`;
    }
    case "list":
      return listOf(b["ordered"], arr(b["items"], "items"));
    case "table":
      return table(b);
    case "image":
      return image(b, o);
    case "link":
      return inPage ? `#par[#${text(b["text"])}]` : `#par[${linkOf(b)}]`;
    case "pageBreak":
      return "#pagebreak(weak: true)";
    case "spacer":
      return `#v(${length(b["height"], "height")})`;
    case "keyValue":
      return keyValue(b);
    case "pageNumber":
      if (inPage) return "#par[#context counter(page).display()]";
      return refuse("pageNumber is only allowed in the footer");
    default:
      return refuse("unknown block type");
  }
}

const blocks = (list: unknown[] | undefined, o: ToTypstOptions, inPage: boolean): string =>
  arr(list ?? [], "blocks").map((b) => block(b, o, inPage)).join("\n");

export function toTypst(d: RenderDocument, o: ToTypstOptions): string {
  const doc = obj(d, "document");
  const page = doc["page"] === undefined ? {} : obj(doc["page"], "page");
  const theme = doc["theme"] === undefined ? {} : obj(doc["theme"], "theme");

  const size = page["size"] === undefined ? "A4" : str(page["size"], "page size");
  const paper = PAPER[size] ?? refuse("unknown page size");
  const orientation = page["orientation"] ?? "portrait";
  if (orientation !== "portrait" && orientation !== "landscape") refuse("unknown orientation");
  const margins = page["margins"] === undefined ? "20mm" : length(page["margins"], "margins");
  const font = theme["font"] === undefined ? "Noto Sans" : str(theme["font"], "font");
  const fontSize = theme["fontSize"] === undefined ? 11 : theme["fontSize"];
  if (typeof fontSize !== "number" || !Number.isFinite(fontSize) || fontSize < 6 || fontSize > 24) refuse("fontSize must be 6 to 24");
  const accent = theme["accent"] === undefined ? "#1a1a1a" : str(theme["accent"], "accent");
  if (!COLOR.test(accent)) refuse("accent must be #rrggbb");

  const header = blocks(doc["header"] as unknown[] | undefined, o, true);
  const footer = blocks(doc["footer"] as unknown[] | undefined, o, true);
  const body = blocks(doc["body"] as unknown[], o, false);

  return [
    `// Koldera document template ${TEMPLATE_VERSION}`,
    `#set document(title: ${text(doc["title"])}, date: none)`,
    `#set text(${langArgs(doc["language"])}, font: ${fontList(font)}, size: ${fontSize}pt)`,
    `#let accent = rgb(${lit(accent)})`,
    `#set page(paper: ${lit(paper)}, flipped: ${orientation === "landscape"}, margin: ${margins}` +
      (header ? `, header: pdf.artifact[\n${header}\n]` : "") +
      (footer ? `, footer: pdf.artifact[\n${footer}\n]` : "") +
      ")",
    "#set heading(numbering: none)",
    "#show heading: set text(fill: accent)",
    "#show link: underline",
    "#show figure.where(kind: table): set block(breakable: true)",
    "#show figure.where(kind: table): set figure.caption(position: top)",
    "#set table(inset: 6pt, stroke: 0.5pt + luma(160))",
    "",
    body,
    "",
  ].join("\n");
}
