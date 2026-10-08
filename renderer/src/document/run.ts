import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdir, readFile, rm } from "node:fs/promises";
import { writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { ValidateFunction } from "ajv";
import type { DocumentErrorCode, DocumentRenderRequest, DocumentRenderResult } from "../contracts/types.js";
import { compileTypst, typstVersion, typstVersionProblem } from "./compile.js";
import { DocumentRefusedError, TEMPLATE_VERSION, toTypst, type RenderDocument } from "./toTypst.js";

const cjs = createRequire(import.meta.url);
type AjvCtor = new (opts: { allErrors: boolean; strict: boolean }) => { compile(schema: unknown): ValidateFunction };
const ajvModule = cjs("ajv/dist/2020.js") as { default?: AjvCtor } & AjvCtor;
const Ajv2020: AjvCtor = ajvModule.default ?? ajvModule;
const formatsModule = cjs("ajv-formats") as { default?: (ajv: unknown) => void } & ((ajv: unknown) => void);
const addFormats: (ajv: unknown) => void = formatsModule.default ?? formatsModule;

const here = path.dirname(fileURLToPath(import.meta.url));
const contractsDir = process.env["KOLDERA_CONTRACTS_DIR"] ?? path.resolve(here, "..", "..", "..", "contracts");

let documentValidator: ValidateFunction | undefined;

/** The contract check of the document itself; the server validated it already, this is the second line. */
function documentErrors(value: unknown): string[] {
  if (!documentValidator) {
    // Not strict: the schema's own annotations (format, description) are not the renderer's concern.
    const ajv = new Ajv2020({ allErrors: false, strict: false });
    addFormats(ajv);
    documentValidator = ajv.compile(JSON.parse(readFileSync(path.join(contractsDir, "document.schema.json"), "utf8")));
  }
  return documentValidator(value) ? [] : (documentValidator.errors ?? []).slice(0, 3).map((e) => `${e.instancePath || "/"} ${e.message ?? "invalid"}`);
}

const fail = (request: DocumentRenderRequest, engineVersion: string, code: DocumentErrorCode, message: string): DocumentRenderResult => ({
  specVersion: "1.0",
  jobId: request.jobId,
  status: "failed",
  engineVersion,
  templateVersion: TEMPLATE_VERSION,
  artifacts: [],
  error: { code, message: message.slice(0, 500) },
});

/** `/work/x/y` to `<workRoot>/x/y`. The request schema pins the canonical paths. */
const local = (workRoot: string, canonical: string): string => path.join(workRoot, ...canonical.split("/").slice(2));

export async function runDocument(request: DocumentRenderRequest, options: { workRoot: string }): Promise<DocumentRenderResult> {
  const workRoot = path.resolve(options.workRoot);
  const version = await typstVersion(request.typstCommand, workRoot).catch(() => "unknown");

  let raw: string;
  try {
    raw = await readFile(local(workRoot, request.documentPath), "utf8");
  } catch {
    return fail(request, version, "INVALID_INPUT", "the document file could not be read");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return fail(request, version, "DOCUMENT_REFUSED", "the document is not valid JSON");
  }
  const errors = documentErrors(parsed);
  if (errors.length > 0) return fail(request, version, "DOCUMENT_REFUSED", "the document violates the contract: " + errors.join("; "));

  // Typst root-relative path of the images directory, e.g. /in/images.
  const imagesRoot = "/" + request.imagesDirectory.split("/").slice(2).join("/");
  let source: string;
  try {
    source = toTypst(parsed as RenderDocument, { imagesRoot, allowFileImages: request.allowFileImages === true });
  } catch (e) {
    if (e instanceof DocumentRefusedError) return fail(request, version, "DOCUMENT_REFUSED", e.message);
    throw e;
  }

  // Only the pinned Typst has been checked to tag what the template asks for; another one does not compile.
  const versionProblem = typstVersionProblem(version);
  if (versionProblem) return fail(request, version, "WORKER_ERROR", versionProblem);

  const outDir = local(workRoot, request.outputDirectory);
  await mkdir(outDir, { recursive: true });
  const typPath = path.join(outDir, "document.typ");
  const pdfPath = path.join(outDir, "document.pdf");
  try {
    await writeFile(typPath, source, "utf8");
    const outcome = await compileTypst({ command: request.typstCommand, workRoot, source: typPath, output: pdfPath });
    if (!outcome.ok) {
      await rm(pdfPath, { force: true });
      return fail(request, version, outcome.code, outcome.message);
    }
    const bytes = await readFile(pdfPath);
    if (bytes.length === 0) return fail(request, version, "WORKER_ERROR", "Typst wrote an empty file");
    return {
      specVersion: "1.0",
      jobId: request.jobId,
      status: "completed",
      engineVersion: version,
      templateVersion: TEMPLATE_VERSION,
      artifacts: [{ kind: "generated_pdf", path: "/work/out/document.pdf", sha256: createHash("sha256").update(bytes).digest("hex"), bytes: bytes.length }],
      error: null,
    };
  } finally {
    // The intermediate source is not an artifact.
    await rm(typPath, { force: true });
  }
}
