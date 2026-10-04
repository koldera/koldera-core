import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { ValidateFunction } from "ajv";

// ajv and ajv-formats are CommonJS; load them through require so NodeNext typing stays honest.
const cjs = createRequire(import.meta.url);
type AjvCtor = new (opts: { allErrors: boolean; strict: boolean }) => { compile(schema: unknown): ValidateFunction };
const ajvModule = cjs("ajv/dist/2020.js") as { default?: AjvCtor } & AjvCtor;
const Ajv2020: AjvCtor = ajvModule.default ?? ajvModule;
const formatsModule = cjs("ajv-formats") as { default?: (ajv: unknown) => void } & ((ajv: unknown) => void);
const addFormats: (ajv: unknown) => void = formatsModule.default ?? formatsModule;

// Wire types of contracts/html-renderer-{request,result}.schema.json. Both documents are validated
// against the repository schema files before use, exactly like the other workers do.

export interface HtmlRendererRequest {
  specVersion: "1.0";
  jobId: string;
  inputPath: "/work/in/source.html";
  outputDirectory: "/work/out";
  limits: { timeoutSeconds: number; maxBytes: number; maxPages: number; maxInlineImageBytes: number; maxTotalInlineImageBytes: number };
  page: { format: "A4" | "Letter"; printBackground?: boolean };
  /** Only the outermost elements matching this CSS selector are printed (see the contract's description). */
  content?: ContentSelection;
}

export interface ContentSelection {
  selector: string;
}

export type DiagnosticCode =
  | "HTML_ENCODING_INVALID"
  | "HTML_NOT_A_DOCUMENT"
  | "HTML_SCRIPT_REMOVED"
  | "HTML_ELEMENT_REMOVED"
  | "HTML_ATTRIBUTE_REMOVED"
  | "HTML_EVENT_HANDLER_REMOVED"
  | "HTML_URL_REMOVED"
  | "HTML_EXTERNAL_RESOURCE"
  | "HTML_EXTERNAL_REQUESTS_BLOCKED"
  | "HTML_INLINE_IMAGE_INVALID"
  | "HTML_INLINE_IMAGE_TOO_LARGE"
  | "HTML_INLINE_IMAGES_TOO_LARGE"
  | "CSS_IMPORT_IGNORED"
  | "CSS_EXTERNAL_RESOURCE"
  | "CSS_UNSAFE_CONSTRUCT"
  | "HTML_LANG_MISSING"
  | "HTML_TITLE_MISSING"
  | "HTML_IMAGE_ALT_MISSING"
  | "HTML_CONTENT_SELECTED"
  | "HTML_RELATIVE_LINK_REMOVED"
  | "HTML_IMAGE_NOT_LOADED"
  | "HTML_SVG_NAME_MISSING";

export interface Diagnostic {
  code: DiagnosticCode;
  message: string;
  blocking: boolean;
  line: number | null;
  element: string | null;
}

export interface RendererArtifact {
  kind: "generated_pdf";
  path: string;
  sha256: string;
  bytes: number;
}

export type RendererErrorCode = "INVALID_INPUT" | "UNSUPPORTED_CONTENT" | "EXTERNAL_RESOURCE_BLOCKED" | "CONTENT_NOT_FOUND" | "TIMEOUT" | "LIMIT_EXCEEDED" | "RENDER_FAILED" | "WORKER_ERROR";

export interface HtmlRendererResult {
  specVersion: "1.0";
  jobId: string;
  status: "completed" | "unsupported" | "failed";
  sourceSha256: string | null;
  engineVersion: string;
  taggedRequested: true;
  pages: number | null;
  blockedRequests: number;
  diagnostics: Diagnostic[];
  artifacts: RendererArtifact[];
  manualReviewRequired: true;
  error: { code: RendererErrorCode; message: string } | null;
}

// ---- semantic order snapshot and the approved source-order fix ----------------------------

export interface HtmlSemanticRequest {
  specVersion: "1.0";
  jobId: string;
  inputPath: "/work/in/source.html";
  limits: { timeoutSeconds: number; maxBytes: number; maxNodes: number; maxInlineImageBytes: number; maxTotalInlineImageBytes: number };
}

export interface SemanticNodePrivate {
  domPath: string;
  tag: string;
  elementChildren: number;
  focusable: boolean;
  tabindex: number | null;
  cssOrder: number;
  inlineOrder: boolean;
  parentDisplay: string;
  parentFlexReversed: boolean;
}

export interface SemanticNode {
  nodeId: string;
  parentId: string | null;
  role: string;
  logicalIndex: number;
  textPreview: string | null;
  boundingBox: { x: number; y: number; width: number; height: number } | null;
  private: SemanticNodePrivate;
}

export type FindingCategory = "confirmed_machine_failure" | "suspected_semantic_mismatch" | "manual_review";

export interface SemanticFinding {
  code: string;
  category: FindingCategory;
  message: string;
  nodeIds: string[];
  evidence: string | null;
}

export interface HtmlSemanticResult {
  specVersion: "1.0";
  jobId: string;
  status: "completed" | "unsupported" | "failed";
  sourceSha256: string | null;
  engineVersion: string;
  nodes: SemanticNode[];
  focusOrderNodeIds: string[];
  focusOrderMeasured: boolean;
  visualOrderCandidateNodeIds: string[];
  findings: SemanticFinding[];
  error: { code: RendererErrorCode; message: string } | null;
}

export interface HtmlReorderRequest {
  specVersion: "1.0";
  jobId: string;
  inputPath: "/work/in/source.html";
  outputDirectory: "/work/out";
  baseSha256: string;
  parentPath: string;
  orderedChildIndexes: number[];
  limits: { timeoutSeconds: number; maxBytes: number; maxPages: number; maxInlineImageBytes: number; maxTotalInlineImageBytes: number; maxVisualDiffRatio: number };
  page: { format: "A4" | "Letter"; printBackground?: boolean };
  content?: ContentSelection;
}

export type ReorderErrorCode = "INVALID_INPUT" | "ORDER_FIX_UNSUPPORTED" | "ORDER_FIX_REGRESSION" | RendererErrorCode;

export interface HtmlReorderResult {
  specVersion: "1.0";
  jobId: string;
  status: "applied" | "unsupported" | "failed";
  engineVersion: string;
  baseSha256: string | null;
  checks: {
    domSequenceMatchesApproved: boolean | null;
    focusOrderMeasured: boolean | null;
    focusSequenceFollowsDom: boolean | null;
    inlineOrderDeclarationsRemoved: number;
    visualDiffRatio: number | null;
    visualWithinTolerance: boolean | null;
    pages: number | null;
  };
  artifacts: { kind: "source_html" | "generated_pdf"; path: string; sha256: string; bytes: number }[];
  diagnostics: Diagnostic[];
  manualReviewRequired: true;
  error: { code: ReorderErrorCode; message: string } | null;
}

const here = path.dirname(fileURLToPath(import.meta.url));
// Repository layout by default; containers set KOLDERA_CONTRACTS_DIR to wherever contracts/ was copied.
const contractsDir = process.env["KOLDERA_CONTRACTS_DIR"] ?? path.resolve(here, "..", "..", "..", "contracts");

function loadSchema(name: string): ValidateFunction {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const schema = JSON.parse(readFileSync(path.join(contractsDir, name), "utf8"));
  return ajv.compile(schema);
}

let requestValidator: ValidateFunction | undefined;
let resultValidator: ValidateFunction | undefined;
let semanticRequestValidator: ValidateFunction | undefined;
let semanticResultValidator: ValidateFunction | undefined;
let reorderRequestValidator: ValidateFunction | undefined;
let reorderResultValidator: ValidateFunction | undefined;

export function validateSemanticRequest(value: unknown): string[] {
  semanticRequestValidator ??= loadSchema("html-semantic-request.schema.json");
  return errorsOf(semanticRequestValidator, value);
}

export function validateSemanticResult(value: unknown): string[] {
  semanticResultValidator ??= loadSchema("html-semantic-result.schema.json");
  return errorsOf(semanticResultValidator, value);
}

export function validateReorderRequest(value: unknown): string[] {
  reorderRequestValidator ??= loadSchema("html-reorder-request.schema.json");
  return errorsOf(reorderRequestValidator, value);
}

export function validateReorderResult(value: unknown): string[] {
  reorderResultValidator ??= loadSchema("html-reorder-result.schema.json");
  return errorsOf(reorderResultValidator, value);
}

export function validateRequest(value: unknown): string[] {
  requestValidator ??= loadSchema("html-renderer-request.schema.json");
  return errorsOf(requestValidator, value);
}

export function validateResult(value: unknown): string[] {
  resultValidator ??= loadSchema("html-renderer-result.schema.json");
  return errorsOf(resultValidator, value);
}

function errorsOf(validator: ValidateFunction, value: unknown): string[] {
  return validator(value) ? [] : (validator.errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "invalid"}`);
}
