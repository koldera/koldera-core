import { createHash } from "node:crypto";
import { cp, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { PDFArray, PDFDict, PDFDocument, PDFName, PDFRef, type PDFContext, type PDFObject } from "pdf-lib";
import { PNG } from "pngjs";
import { describe, expect, it } from "vitest";
import { validateDocumentRenderResult } from "../../src/contracts/types.js";
import { runDocument } from "../../src/document/run.js";

const typst = process.env["TYPST_CMD"];
const fixtures = path.join(import.meta.dirname, "fixtures");
const FIXTURES = ["invoice", "report", "multilingual", "statement"];

async function workspace(doc: unknown) {
  const dir = await mkdtemp(path.join(tmpdir(), "koldera-doc-"));
  await mkdir(path.join(dir, "in", "images"), { recursive: true });
  await mkdir(path.join(dir, "out"));
  await cp(path.join(fixtures, "logo.png"), path.join(dir, "in", "images", "logo.png"));
  await cp(path.join(fixtures, "chart.png"), path.join(dir, "in", "images", "chart.png"));
  await writeFile(path.join(dir, "in", "document.json"), JSON.stringify(doc));
  return dir;
}

const request = () => ({
  specVersion: "1.0" as const,
  jobId: "123e4567-e89b-42d3-a456-426614174000",
  documentPath: "/work/in/document.json" as const,
  imagesDirectory: "/work/in/images" as const,
  outputDirectory: "/work/out" as const,
  typstCommand: typst ?? "typst",
  allowFileImages: true,
});

const fixtureDoc = async (name: string) => JSON.parse(await readFile(path.join(fixtures, `${name}.json`), "utf8")) as unknown;

/** Struct element children of a StructElem (or the StructTreeRoot) that carry an /S name. */
function kids(context: PDFContext, element: PDFDict): PDFDict[] {
  const at = (o: PDFObject | undefined) => (o instanceof PDFRef ? context.lookup(o) : o);
  const k = at(element.get(PDFName.of("K")));
  return (k instanceof PDFArray ? k.asArray() : k ? [k] : []).map(at).filter((d): d is PDFDict => d instanceof PDFDict && d.get(PDFName.of("S")) instanceof PDFName);
}

function elements(context: PDFContext, node: PDFDict, type: string, out: PDFDict[] = []): PDFDict[] {
  for (const kid of kids(context, node)) {
    if (kid.get(PDFName.of("S")) === PDFName.of(type)) out.push(kid);
    elements(context, kid, type, out);
  }
  return out;
}

// Skipped without TYPST_CMD so a plain `pnpm test` works on a machine without Typst; CI always sets it.
describe.skipIf(!typst)("document compile (Typst)", () => {
  for (const name of FIXTURES) {
    it(`compiles ${name} to a PDF/UA-1 tagged PDF, identically twice`, async () => {
      const doc = await fixtureDoc(name);
      const a = await runDocument(request(), { workRoot: await workspace(doc) });
      const b = await runDocument(request(), { workRoot: await workspace(doc) });
      expect(a.error ?? null).toBeNull();
      expect(a.status).toBe("completed");
      expect(validateDocumentRenderResult(a)).toEqual([]);
      expect(a.templateVersion).toBe("1");
      expect(a.engineVersion).toMatch(/^typst 0\.15\./);
      expect(a.artifacts[0]?.sha256).toMatch(/^[a-f0-9]{64}$/);
      expect(b.artifacts[0]?.sha256).toBe(a.artifacts[0]?.sha256);
    });
  }

  it("tags the column header row as TH and body cells as TD in the real Typst output", async () => {
    const doc = { title: "T", language: "en", body: [{ type: "table", caption: "C", header: [["Region", "Q1"]], rows: [["North", "1"], ["South", "2"]] }] };
    const dir = await workspace(doc);
    const r = await runDocument(request(), { workRoot: dir });
    expect(r.error ?? null).toBeNull();
    const pdf = await PDFDocument.load(await readFile(path.join(dir, "out", "document.pdf")), { updateMetadata: false });
    const tree = pdf.catalog.lookup(PDFName.of("StructTreeRoot"), PDFDict);
    const rows = elements(pdf.context, tree, "TR").map((tr) => kids(pdf.context, tr).map((c) => (c.get(PDFName.of("S")) as PDFName).decodeText()));
    expect(rows).toEqual([
      ["TH", "TH"],
      ["TD", "TD"],
      ["TD", "TD"],
    ]);
  });

  it("refuses rowHeaders: true before Typst runs (Typst 0.15 would tag them TD)", async () => {
    const doc = { title: "T", language: "en", body: [{ type: "table", caption: "C", header: [["a", "b"]], rows: [["x", "1"]], rowHeaders: true }] };
    const r = await runDocument(request(), { workRoot: await workspace(doc) });
    expect(r.error?.code).toBe("DOCUMENT_REFUSED");
    expect(r.error?.message).toMatch(/rowHeaders/);
  });

  it("writes the artifact at the reported path with the reported hash", async () => {
    const dir = await workspace(await fixtureDoc("invoice"));
    const r = await runDocument(request(), { workRoot: dir });
    const art = r.artifacts[0]!;
    const bytes = await readFile(path.join(dir, art.path.replace(/^\/work\//, "")));
    expect(bytes.subarray(0, 5).toString()).toBe("%PDF-");
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(art.sha256);
    expect(bytes.length).toBe(art.bytes);
  });

  it("refuses an image without a description (validator bypassed) as DOCUMENT_REFUSED", async () => {
    const doc = { title: "T", language: "en", body: [{ type: "image", file: "logo.png" }] };
    const r = await runDocument(request(), { workRoot: await workspace(doc) });
    expect(r.status).toBe("failed");
    expect(r.error?.code).toBe("DOCUMENT_REFUSED");
    expect(r.error?.message).toMatch(/alt/i);
    expect(r.artifacts).toEqual([]);
    expect(validateDocumentRenderResult(r)).toEqual([]);
  });

  it("does not leak host paths when an image is missing", async () => {
    const dir = await workspace({ title: "T", language: "en", body: [{ type: "image", file: "nope.png", alt: "x" }] });
    const r = await runDocument(request(), { workRoot: dir });
    expect(r.error?.code).toBe("DOCUMENT_REFUSED");
    expect(r.error?.message).not.toContain(dir);
  });

  it("cannot read files outside the work root", async () => {
    const dir = await workspace({ title: "T", language: "en", body: [{ type: "image", file: "x/../../../../../../Windows/win.ini", alt: "x" }] });
    const r = await runDocument(request(), { workRoot: dir });
    expect(r.status).toBe("failed");
  });

  it("accepts an upload without a file extension and fits a large image to the page", async () => {
    const id = "123e4567-e89b-42d3-a456-426614174001";
    const dir = await workspace({ title: "T", language: "en", body: [{ type: "image", uploadId: id, alt: "Wide picture" }, { type: "paragraph", content: ["after"] }] });
    const png = new PNG({ width: 3000, height: 3000 });
    png.data.fill(200);
    await writeFile(path.join(dir, "in", "images", id), PNG.sync.write(png));
    const r = await runDocument(request(), { workRoot: dir });
    expect(r.error ?? null).toBeNull();
    // A 3000 px square must be scaled into the text area, so image and paragraph fit on at most two pages.
    expect((await PDFDocument.load(await readFile(path.join(dir, "out", "document.pdf")))).getPageCount()).toBeLessThanOrEqual(2);
  });

  it("renders hostile text as text", async () => {
    const doc = { title: 'T"]; #read("x")', language: "en", body: [{ type: "paragraph", content: ['"]; #read("/etc/passwd") #import "@preview/x:1.0.0"'] }] };
    const r = await runDocument(request(), { workRoot: await workspace(doc) });
    expect(r.error ?? null).toBeNull();
    expect(r.status).toBe("completed");
  });

  it("fails closed on a document that violates the schema", async () => {
    const r = await runDocument(request(), { workRoot: await workspace({ title: "T" }) });
    expect(r.status).toBe("failed");
    expect(r.error?.code).toBe("DOCUMENT_REFUSED");
  });

  it("reports INVALID_INPUT when the document file is missing", async () => {
    const dir = await workspace({});
    const r = await runDocument(request(), { workRoot: path.join(dir, "elsewhere") });
    expect(r.status).toBe("failed");
    expect(r.error?.code).toBe("INVALID_INPUT");
  });
});
