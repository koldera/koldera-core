#!/usr/bin/env node
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { validateRequest, validateResult, type WebWorkerRequest, type WebWorkerResult } from "./contracts/types.js";
import { scan } from "./scan/scanner.js";

/**
 * `scan --request PATH --result PATH [--work-root DIR]` — the same envelope as the PDF worker:
 * exit 0 when a contract-valid result was written (including unsupported/failed results), 1 when
 * the result could not be persisted, 2 on usage errors. Logs are single JSON lines without URLs,
 * page content or host paths.
 */
export async function main(argv: string[]): Promise<number> {
  const args = parse(argv);
  if (!args) {
    process.stderr.write("usage: scan --request PATH --result PATH [--work-root DIR]\n");
    return 2;
  }
  const workRoot = path.resolve(args.workRoot ?? "/work");
  const outputDirectory = path.join(workRoot, "out");

  let result: WebWorkerResult;
  let jobId = "00000000-0000-0000-0000-000000000000";
  try {
    const raw = await readFile(args.request, "utf8");
    const parsed = JSON.parse(raw) as Partial<WebWorkerRequest>;
    if (typeof parsed.jobId === "string" && /^[0-9a-f-]{36}$/i.test(parsed.jobId)) {
      jobId = parsed.jobId;
    }
    const errors = validateRequest(parsed);
    if (errors.length > 0) {
      result = failed(jobId, "INVALID_INPUT", "request violates schema: " + errors.join("; "));
      log("request_invalid", { count: errors.length });
    } else {
      // Test-only switch for a loopback fixture server; never set it in a deployment. It is logged.
      const allowLoopbackForTests = process.env["KOLDERA_SCANNER_ALLOW_LOOPBACK_FOR_TESTS"] === "1";
      if (allowLoopbackForTests) {
        log("loopback_allowed_for_tests", {});
      }
      result = await scan(parsed as WebWorkerRequest, { outputDirectory, allowLoopbackForTests });
    }
  } catch (e) {
    log("worker_error", { type: e instanceof Error ? e.name : "unknown" });
    result = failed(jobId, "WORKER_ERROR", "unexpected worker failure");
  }

  const errors = validateResult(result);
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
  log("result_written", { status: result.status, jobId: result.jobId });
  return 0;
}

function failed(jobId: string, code: WebWorkerResult["error"] extends infer E ? (E extends { code: infer C } ? C : never) : never, message: string): WebWorkerResult {
  return {
    specVersion: "1.0",
    jobId,
    status: "failed",
    requestedUrl: "",
    finalUrl: null,
    engineVersion: "koldera-web-scanner",
    summary: { violations: 0, incomplete: 0, passes: 0, inapplicable: 0, blockedRequests: 0 },
    findings: [],
    artifacts: [],
    manualReviewRequired: true,
    error: { code, message },
  };
}

function parse(argv: string[]): { request: string; result: string; workRoot?: string } | null {
  if (argv[0] !== "scan") return null;
  const values: Record<string, string> = {};
  for (let i = 1; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!key || !key.startsWith("--") || value === undefined) return null;
    values[key.slice(2)] = value;
  }
  if (!values["request"] || !values["result"]) return null;
  const parsed: { request: string; result: string; workRoot?: string } = { request: values["request"], result: values["result"] };
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
