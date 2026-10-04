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

// Wire types of contracts/web-worker-{request,result}.schema.json. Both documents are validated
// against the repository schema files before use, exactly like the PDF worker does.

export interface WebWorkerRequest {
  specVersion: "1.0";
  jobId: string;
  url: string;
  outputDirectory: "/work/out";
  limits: { timeoutSeconds: number; maxResponseBytes: number; maxRequests: number };
  policy: { allowHttp: boolean; proxyServer?: string | null };
}

export type FindingKind = "violation" | "incomplete";
export type Impact = "minor" | "moderate" | "serious" | "critical" | "unknown";

export interface WebFinding {
  ruleId: string;
  kind: FindingKind;
  impact: Impact;
  message: string;
  selector: string;
  snippet: string | null;
  wcagTags: string[];
  helpUrl: string | null;
  needsHumanReview: boolean;
}

export interface WebArtifact {
  kind: "web_report";
  path: string;
  sha256: string;
  bytes: number;
}

export type WorkerErrorCode = "INVALID_INPUT" | "BLOCKED_DESTINATION" | "NAVIGATION_FAILED" | "TIMEOUT" | "LIMIT_EXCEEDED" | "WORKER_ERROR";

export interface WebWorkerResult {
  specVersion: "1.0";
  jobId: string;
  status: "completed" | "unsupported" | "failed";
  requestedUrl: string;
  finalUrl: string | null;
  engineVersion: string;
  summary: { violations: number; incomplete: number; passes: number; inapplicable: number; blockedRequests: number };
  findings: WebFinding[];
  artifacts: WebArtifact[];
  manualReviewRequired: true;
  error: { code: WorkerErrorCode; message: string } | null;
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

export function validateRequest(value: unknown): string[] {
  requestValidator ??= loadSchema("web-worker-request.schema.json");
  return requestValidator(value) ? [] : (requestValidator.errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? ""}`.trim());
}

export function validateResult(value: unknown): string[] {
  resultValidator ??= loadSchema("web-worker-result.schema.json");
  return resultValidator(value) ? [] : (resultValidator.errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? ""}`.trim());
}

export function engineVersion(chromiumVersion: string): string {
  const require = createRequire(import.meta.url);
  const pkg = require("../../package.json") as { version: string };
  const playwright = require("playwright/package.json") as { version: string };
  const axe = require("axe-core/package.json") as { version: string };
  return `koldera-web-scanner ${pkg.version}; playwright ${playwright.version}; axe-core ${axe.version}; chromium ${chromiumVersion}`;
}
