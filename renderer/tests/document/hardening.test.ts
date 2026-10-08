import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { validateDocumentRenderRequest } from "../../src/contracts/types.js";
import { compileTypst, typstVersionProblem } from "../../src/document/compile.js";
import { runDocument } from "../../src/document/run.js";
import { DocumentRefusedError, lit, toTypst, type RenderDocument } from "../../src/document/toTypst.js";

const doc = (body: unknown[], extra: Record<string, unknown> = {}) => ({ title: "T", language: "uk", body, ...extra }) as unknown as RenderDocument;
const uploadId = "123e4567-e89b-42d3-a456-426614174000";

describe("lit() and lone surrogates", () => {
  it("replaces a lone surrogate by U+FFFD and leaves a valid pair alone", () => {
    const fffd = String.fromCharCode(0xfffd);
    expect(lit("a\uD800b")).toBe(`"a${fffd}b"`);
    expect(lit("a\uDC00b")).toBe(`"a${fffd}b"`);
    expect(lit("a\uD800")).toBe(`"a${fffd}"`);
    const pair = String.fromCodePoint(0x1f600);
    expect(lit(`a${pair}b`)).toBe(`"a${pair}b"`);
    expect(lit("a\uD800b\uDC00").isWellFormed()).toBe(true);
  });
});

describe("page number placement", () => {
  const opts = { imagesRoot: "/in/images" };

  it("is refused in the header and allowed in the footer", () => {
    expect(() => toTypst(doc([], { header: [{ type: "pageNumber" }] }), opts)).toThrow(DocumentRefusedError);
    expect(toTypst(doc([], { footer: [{ type: "pageNumber" }] }), opts)).toMatch(/footer: pdf\.artifact\[[\s\S]*counter\(page\)\.display\(\)/);
  });
});

describe("file image sources", () => {
  const file = [{ type: "image", file: "logo.png", alt: "Logo" }];
  const upload = [{ type: "image", uploadId, alt: "Logo" }];

  it("are refused unless the caller allows them, uploads never need the flag", () => {
    expect(() => toTypst(doc(file), { imagesRoot: "/in/images" })).toThrow(DocumentRefusedError);
    expect(() => toTypst(doc(file), { imagesRoot: "/in/images", allowFileImages: false })).toThrow(/file/);
    expect(toTypst(doc(file), { imagesRoot: "/in/images", allowFileImages: true })).toContain('image("/in/images/logo.png"');
    expect(toTypst(doc(upload), { imagesRoot: "/in/images" })).toContain(`image("/in/images/${uploadId}"`);
  });

  it("are refused in header and footer images as well", () => {
    expect(() => toTypst(doc([], { header: [{ type: "image", file: "logo.png", decorative: true }] }), { imagesRoot: "/in/images" })).toThrow(DocumentRefusedError);
  });

  describe("through the request", () => {
    const request = (extra: Record<string, unknown> = {}) => ({
      specVersion: "1.0" as const,
      jobId: "123e4567-e89b-42d3-a456-426614174000",
      documentPath: "/work/in/document.json" as const,
      imagesDirectory: "/work/in/images" as const,
      outputDirectory: "/work/out" as const,
      // Not installed on purpose: only the stage before Typst is under test.
      typstCommand: "typst-is-not-installed-here",
      ...extra,
    });

    async function workspace(document: unknown) {
      const dir = await mkdtemp(path.join(tmpdir(), "koldera-docflag-"));
      await mkdir(path.join(dir, "in", "images"), { recursive: true });
      await writeFile(path.join(dir, "in", "document.json"), JSON.stringify(document));
      return dir;
    }

    it("a request without the flag refuses a file image before Typst runs", async () => {
      const r = await runDocument(request(), { workRoot: await workspace({ title: "T", language: "en", body: file }) });
      expect(r.status).toBe("failed");
      expect(r.error?.code).toBe("DOCUMENT_REFUSED");
      expect(r.error?.message).toMatch(/file/);
    });

    it("a request with allowFileImages gets past the refusal", async () => {
      const r = await runDocument(request({ allowFileImages: true }), { workRoot: await workspace({ title: "T", language: "en", body: file }) });
      // Typst cannot be started here, which is a later stage than the document refusal.
      expect(r.error?.code).toBe("WORKER_ERROR");
    });

    it("the request schema knows the flag and only as a boolean", () => {
      expect(validateDocumentRenderRequest(request({ allowFileImages: true }))).toEqual([]);
      expect(validateDocumentRenderRequest(request())).toEqual([]);
      expect(validateDocumentRenderRequest(request({ allowFileImages: "yes" }))).not.toEqual([]);
    });
  });
});

describe("compileTypst process failures", () => {
  type Exec = NonNullable<Parameters<typeof compileTypst>[0]["execFile"]>;
  const outcome = (exec: Exec) => compileTypst({ command: "typst", workRoot: ".", source: "a.typ", output: "a.pdf", execFile: exec });

  it("reports output over the buffer limit as WORKER_ERROR, not as a timeout", async () => {
    const exec: Exec = (_file, _args, _options, callback) => {
      // Node kills the child and sets both fields when stderr outgrows maxBuffer.
      callback(Object.assign(new Error("stderr maxBuffer length exceeded"), { code: "ERR_CHILD_PROCESS_STDIO_MAXBUFFER", killed: true }), "", "x");
    };
    const r = await outcome(exec);
    expect(r).toMatchObject({ ok: false, code: "WORKER_ERROR" });
    expect(JSON.stringify(r)).not.toMatch(/time/i);
  });

  it("still reports a killed process after the timeout as TIMEOUT", async () => {
    const exec: Exec = (_file, _args, _options, callback) => {
      callback(Object.assign(new Error("timed out"), { killed: true, signal: "SIGTERM", code: null }), "", "");
    };
    expect(await outcome(exec)).toMatchObject({ ok: false, code: "TIMEOUT" });
  });

  it("still reports a command that cannot be started as WORKER_ERROR", async () => {
    const exec: Exec = (_file, _args, _options, callback) => {
      callback(Object.assign(new Error("spawn ENOENT"), { code: "ENOENT" }), "", "");
    };
    expect(await outcome(exec)).toMatchObject({ ok: false, code: "WORKER_ERROR", message: "Typst could not be started" });
  });
});

describe("Typst version pin", () => {
  it("accepts the pinned 0.15.x line only", () => {
    expect(typstVersionProblem("typst 0.15.1 (b8fb6c8f)")).toBeNull();
    expect(typstVersionProblem("typst 0.15.0")).toBeNull();
    for (const v of ["typst 0.16.0", "typst 0.14.2", "typst 0.1.5", "typst 1.15.0", "unknown", ""]) expect(typstVersionProblem(v)).toMatch(/0\.15/);
  });

  it("fails the job as WORKER_ERROR before compiling with another program or version", async () => {
    const dir = await mkdtemp(path.join(tmpdir(), "koldera-docver-"));
    await mkdir(path.join(dir, "in", "images"), { recursive: true });
    await writeFile(path.join(dir, "in", "document.json"), JSON.stringify({ title: "T", language: "en", body: [{ type: "paragraph", content: ["x"] }] }));
    // `node --version` answers, but not as Typst 0.15.
    const r = await runDocument(
      { specVersion: "1.0", jobId: "123e4567-e89b-42d3-a456-426614174000", documentPath: "/work/in/document.json", imagesDirectory: "/work/in/images", outputDirectory: "/work/out", typstCommand: process.execPath },
      { workRoot: dir },
    );
    expect(r.status).toBe("failed");
    expect(r.error?.code).toBe("WORKER_ERROR");
    expect(r.error?.message).toMatch(/0\.15/);
  });
});
