#!/usr/bin/env node
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  validateRequest,
  validateResult,
  validateReorderRequest,
  validateReorderResult,
  validateSemanticRequest,
  validateSemanticResult,
  validateDocumentRenderRequest,
  validateDocumentRenderResult,
  type DocumentRenderRequest,
  type DocumentRenderResult,
  type HtmlRendererRequest,
  type HtmlRendererResult,
  type HtmlReorderRequest,
  type HtmlReorderResult,
  type HtmlSemanticRequest,
  type HtmlSemanticResult,
  type RendererErrorCode,
} from "./contracts/types.js";
import { runDocument } from "./document/run.js";
import { reorderSource } from "./remediation/reorder.js";
import { render } from "./render/renderer.js";
import { collectSemantic } from "./semantic/semantic.js";

/**
 * `render | semantic | reorder | document --request PATH --result PATH [--work-root DIR]` — the same
 * envelope as the other workers: exit 0 when a contract-valid result was written (including
 * unsupported/failed results), 1 when the result could not be persisted, 2 on usage errors. Logs
 * are single JSON lines without document content or host paths.
 */
export async function main(argv: string[]): Promise<number> {
  const args = parse(argv);
  if (!args) {
    process.stderr.write("usage: render|semantic|reorder|document --request PATH --result PATH [--work-root DIR]\n");
    return 2;
  }
  const workRoot = path.resolve(args.workRoot ?? "/work");

  if (args.verb === "document") return mainDocument(args, workRoot);

  let result: HtmlRendererResult | HtmlSemanticResult | HtmlReorderResult;
  let jobId = "00000000-0000-0000-0000-000000000000";
  try {
    const raw = await readFile(args.request, "utf8");
    const parsed = JSON.parse(raw) as { jobId?: unknown };
    if (typeof parsed.jobId === "string" && /^[0-9a-f-]{36}$/i.test(parsed.jobId)) {
      jobId = parsed.jobId;
    }
    const validate = args.verb === "render" ? validateRequest : args.verb === "semantic" ? validateSemanticRequest : validateReorderRequest;
    const errors = validate(parsed);
    if (errors.length > 0) {
      result = failed(args.verb, jobId, "INVALID_INPUT", "request violates schema: " + errors.join("; "));
      log("request_invalid", { count: errors.length });
    } else if (args.verb === "render") {
      result = await render(parsed as unknown as HtmlRendererRequest, { workRoot });
    } else if (args.verb === "semantic") {
      result = await collectSemantic(parsed as unknown as HtmlSemanticRequest, { workRoot });
    } else {
      result = await reorderSource(parsed as unknown as HtmlReorderRequest, { workRoot });
    }
  } catch (e) {
    log("worker_error", { type: e instanceof Error ? e.name : "unknown" });
    result = failed(args.verb, jobId, "WORKER_ERROR", "unexpected worker failure");
  }

  const validateOut = args.verb === "render" ? validateResult : args.verb === "semantic" ? validateSemanticResult : validateReorderResult;
  const errors = validateOut(result);
  if (errors.length > 0) {
    log("result_invalid", { count: errors.length });
    return 1;
  }
  try {
    await mkdir(path.dirname(path.resolve(args.result)), { recursive: true });
    const tmp = args.result + ".tmp";
    await writeFile(tmp, JSON.stringify(result, null, 2), "utf8");
    await rename(tmp, args.result);
  } catch {
    log("result_not_persisted", {});
    return 1;
  }
  log("result_written", { verb: args.verb, status: result.status, jobId: result.jobId });
  return 0;
}

type Verb = "render" | "semantic" | "reorder" | "document";

/** The `document` verb has its own request/result contracts and no HTML-renderer diagnostics. */
async function mainDocument(args: { request: string; result: string }, workRoot: string): Promise<number> {
  let result: DocumentRenderResult;
  let jobId = "00000000-0000-0000-0000-000000000000";
  const failedResult = (code: "INVALID_INPUT" | "WORKER_ERROR", message: string): DocumentRenderResult => ({ specVersion: "1.0", jobId, status: "failed", engineVersion: "unknown", templateVersion: "1", artifacts: [], error: { code, message } });
  try {
    const parsed = JSON.parse(await readFile(args.request, "utf8")) as { jobId?: unknown };
    if (typeof parsed.jobId === "string" && /^[0-9a-f-]{36}$/i.test(parsed.jobId)) jobId = parsed.jobId;
    const errors = validateDocumentRenderRequest(parsed);
    if (errors.length > 0) {
      result = failedResult("INVALID_INPUT", "request violates schema: " + errors.join("; "));
      log("request_invalid", { count: errors.length });
    } else {
      result = await runDocument(parsed as unknown as DocumentRenderRequest, { workRoot });
    }
  } catch (e) {
    log("worker_error", { type: e instanceof Error ? e.name : "unknown" });
    result = failedResult("WORKER_ERROR", "unexpected worker failure");
  }
  if (validateDocumentRenderResult(result).length > 0) {
    log("result_invalid", {});
    return 1;
  }
  try {
    await mkdir(path.dirname(path.resolve(args.result)), { recursive: true });
    const tmp = args.result + ".tmp";
    await writeFile(tmp, JSON.stringify(result, null, 2), "utf8");
    await rename(tmp, args.result);
  } catch {
    log("result_not_persisted", {});
    return 1;
  }
  log("result_written", { verb: "document", status: result.status, jobId: result.jobId });
  return 0;
}

function failed(verb: Verb, jobId: string, code: RendererErrorCode, message: string): HtmlRendererResult | HtmlSemanticResult | HtmlReorderResult {
  const engineVersion = "koldera-html-renderer 0.1.0";
  if (verb === "render") {
    return { specVersion: "1.0", jobId, status: "failed", sourceSha256: null, engineVersion, taggedRequested: true, pages: null, blockedRequests: 0, diagnostics: [], artifacts: [], manualReviewRequired: true, error: { code, message } };
  }
  if (verb === "semantic") {
    return { specVersion: "1.0", jobId, status: "failed", sourceSha256: null, engineVersion, nodes: [], focusOrderNodeIds: [], focusOrderMeasured: false, visualOrderCandidateNodeIds: [], findings: [], error: { code, message } };
  }
  return {
    specVersion: "1.0",
    jobId,
    status: "failed",
    engineVersion,
    baseSha256: null,
    checks: { domSequenceMatchesApproved: null, focusOrderMeasured: null, focusSequenceFollowsDom: null, inlineOrderDeclarationsRemoved: 0, visualDiffRatio: null, visualWithinTolerance: null, pages: null },
    artifacts: [],
    diagnostics: [],
    manualReviewRequired: true,
    error: { code, message },
  };
}

function parse(argv: string[]): { verb: Verb; request: string; result: string; workRoot?: string } | null {
  const verb = argv[0];
  if (verb !== "render" && verb !== "semantic" && verb !== "reorder" && verb !== "document") return null;
  const values: Record<string, string> = {};
  for (let i = 1; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!key || !key.startsWith("--") || value === undefined) return null;
    values[key.slice(2)] = value;
  }
  if (!values["request"] || !values["result"]) return null;
  const parsed: { verb: Verb; request: string; result: string; workRoot?: string } = { verb, request: values["request"], result: values["result"] };
  if (values["work-root"]) parsed.workRoot = values["work-root"];
  return parsed;
}

function log(event: string, fields: Record<string, unknown>): void {
  process.stderr.write(JSON.stringify({ event, ...fields }) + "\n");
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]).endsWith(path.join("dist", "cli.js"));
if (invokedDirectly) {
  main(process.argv.slice(2)).then((code) => process.exit(code), () => process.exit(1));
}
