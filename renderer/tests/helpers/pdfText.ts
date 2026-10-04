import { PDFArray, PDFDict, PDFDocument, PDFName, PDFRawStream, PDFRef, decodePDFRawStream } from "pdf-lib";

/**
 * Minimal PDF text extraction for tests only: reads every page's content stream(s), follows `Tf`
 * font changes and decodes `Tj`/`TJ` string operands through the active font's `/ToUnicode` CMap
 * (`bfchar`/`bfrange`, the common case Chromium's tagged PDF export writes). Not a general-purpose
 * extractor — enough to assert a specific word made it into the printed page in a test.
 */
export async function extractPdfText(bytes: Uint8Array): Promise<string> {
  const pdf = await PDFDocument.load(bytes, { updateMetadata: false });
  const out: string[] = [];
  for (const page of pdf.getPages()) {
    const contents = page.node.Contents();
    const stream = decodeContents(pdf.context, contents);
    const resources = page.node.Resources();
    const fonts = new Map<string, Map<number, string>>();
    if (resources) {
      const fontDict = resources.lookupMaybe(PDFName.of("Font"), PDFDict);
      if (fontDict) {
        for (const [key, ref] of fontDict.entries()) {
          const fontObj = pdf.context.lookup(ref, PDFDict);
          if (fontObj) fonts.set(key.decodeText(), toUnicodeMap(pdf.context, fontObj));
        }
      }
    }
    out.push(textOfContentStream(stream, fonts));
  }
  return out.join("\n");
}

function decodeContents(context: PDFDocument["context"], contents: ReturnType<import("pdf-lib").PDFPageLeaf["Contents"]>): Uint8Array {
  if (!contents) return new Uint8Array(0);
  const parts: Uint8Array[] = [];
  const streams = contents instanceof PDFArray ? Array.from({ length: contents.size() }, (_, i) => contents.get(i)) : [contents];
  for (const entry of streams) {
    const resolved = entry instanceof PDFRef ? context.lookup(entry) : entry;
    if (resolved instanceof PDFRawStream) parts.push(decodePDFRawStream(resolved).getBytes());
  }
  let total = 0;
  for (const p of parts) total += p.length + 1;
  const joined = new Uint8Array(total);
  let offset = 0;
  for (const p of parts) {
    joined.set(p, offset);
    offset += p.length;
    joined[offset] = 0x0a;
    offset += 1;
  }
  return joined;
}

/** Parses a font's `/ToUnicode` CMap stream (if any) into code -> unicode text. */
function toUnicodeMap(context: PDFDocument["context"], font: PDFDict): Map<number, string> {
  const map = new Map<number, string>();
  const toUnicode = font.lookupMaybe(PDFName.of("ToUnicode"), PDFRawStream);
  if (!toUnicode) return map;
  const cmap = Buffer.from(decodePDFRawStream(toUnicode).getBytes()).toString("latin1");
  for (const block of cmap.matchAll(/beginbfchar([\s\S]*?)endbfchar/g)) {
    for (const m of block[1]!.matchAll(/<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>/g)) {
      map.set(parseInt(m[1]!, 16), hexToUtf16(m[2]!));
    }
  }
  for (const block of cmap.matchAll(/beginbfrange([\s\S]*?)endbfrange/g)) {
    for (const m of block[1]!.matchAll(/<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>/g)) {
      const lo = parseInt(m[1]!, 16);
      const hi = parseInt(m[2]!, 16);
      const dstBase = parseInt(m[3]!, 16);
      for (let code = lo; code <= hi && code - lo < 65536; code++) {
        map.set(code, hexToUtf16((dstBase + (code - lo)).toString(16).padStart(m[3]!.length, "0")));
      }
    }
  }
  return map;
}

function hexToUtf16(hex: string): string {
  const padded = hex.length % 4 === 0 ? hex : hex.padStart(hex.length + (4 - (hex.length % 4)), "0");
  let out = "";
  for (let i = 0; i < padded.length; i += 4) out += String.fromCharCode(parseInt(padded.slice(i, i + 4), 16));
  return out;
}

/** Walks BT/ET text objects, tracking the active font (`Tf`) and decoding `Tj`/`TJ` string operands. */
function textOfContentStream(bytes: Uint8Array, fonts: Map<string, Map<number, string>>): string {
  const text = Buffer.from(bytes).toString("latin1");
  let activeFont: Map<number, string> | undefined;
  let out = "";
  const TOKEN = /\/([A-Za-z0-9#+._-]+)\s+[-\d.]+\s+Tf|<([0-9A-Fa-f\s]*)>\s*Tj|\[((?:<[0-9A-Fa-f\s]*>|\([^)]*\)|[^\]])*)\]\s*TJ|\(([^)]*)\)\s*Tj/g;
  let match: RegExpExecArray | null;
  while ((match = TOKEN.exec(text)) !== null) {
    if (match[1] !== undefined) {
      activeFont = fonts.get(match[1]);
      continue;
    }
    if (match[2] !== undefined) {
      out += decodeHexRun(match[2], activeFont);
    } else if (match[3] !== undefined) {
      for (const hex of match[3].matchAll(/<([0-9A-Fa-f\s]*)>/g)) out += decodeHexRun(hex[1]!, activeFont);
      for (const lit of match[3].matchAll(/\(([^)]*)\)/g)) out += lit[1] ?? "";
    } else if (match[4] !== undefined) {
      out += match[4];
    }
  }
  return out;
}

function decodeHexRun(hex: string, font: Map<number, string> | undefined): string {
  const clean = hex.replace(/\s+/g, "");
  let out = "";
  for (let i = 0; i + 4 <= clean.length; i += 4) {
    const code = parseInt(clean.slice(i, i + 4), 16);
    out += font?.get(code) ?? String.fromCharCode(code);
  }
  return out;
}
