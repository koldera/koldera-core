import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";
import { chromium, type Browser, type Page } from "playwright";
import type { AxeResults, Result as AxeRule, NodeResult } from "axe-core";

// @axe-core/playwright is CommonJS; require keeps the constructor typed under NodeNext.
type AxeBuilderInstance = { options(o: { resultTypes: string[] }): AxeBuilderInstance; analyze(): Promise<AxeResults> };
type AxeBuilderCtor = new (opts: { page: Page }) => AxeBuilderInstance;
const axeModule = createRequire(import.meta.url)("@axe-core/playwright") as { default?: AxeBuilderCtor } & AxeBuilderCtor;
const AxeBuilder: AxeBuilderCtor = axeModule.default ?? axeModule;
import { engineVersion, type WebFinding, type WebWorkerRequest, type WebWorkerResult, type WorkerErrorCode } from "../contracts/types.js";
import { RequestGuard } from "../network/requestGuard.js";
import { BlockedDestinationError, checkUrlSyntax, redactUrl, resolvePublic, systemResolver, type DestinationPolicy, type Resolver } from "../network/urlPolicy.js";

export interface ScanOptions {
  /** Real directory that stands for /work/out. */
  outputDirectory: string;
  resolver?: Resolver;
  /** Test-only: lets fixtures on 127.0.0.1 be scanned. The CLI never sets this. */
  allowLoopbackForTests?: boolean;
}

const REPORT_FILE = "web-report.json";
const SNIPPET_LIMIT = 300;
const MAX_NODES_PER_RULE = 25;

/**
 * Scans exactly one URL with an isolated Chromium and axe-core. The result is a machine finding
 * list — never a conformance verdict — and `manualReviewRequired` is always true.
 */
export async function scan(request: WebWorkerRequest, options: ScanOptions): Promise<WebWorkerResult> {
  const policy: DestinationPolicy = { allowHttp: request.policy.allowHttp, allowLoopbackForTests: options.allowLoopbackForTests ?? false };
  const resolver = options.resolver ?? systemResolver;
  const requestedUrl = redactUrl(request.url);
  const base = (status: WebWorkerResult["status"], error: WebWorkerResult["error"], extra: Partial<WebWorkerResult> = {}): WebWorkerResult => ({
    specVersion: "1.0",
    jobId: request.jobId,
    status,
    requestedUrl,
    finalUrl: null,
    engineVersion: engineVersion("not-launched"),
    summary: { violations: 0, incomplete: 0, passes: 0, inapplicable: 0, blockedRequests: 0 },
    findings: [],
    artifacts: [],
    manualReviewRequired: true,
    error,
    ...extra,
  });

  let addresses: string[];
  let checked;
  try {
    checked = checkUrlSyntax(request.url, policy);
    addresses = await resolvePublic(checked, resolver, policy);
  } catch (e) {
    if (e instanceof BlockedDestinationError) {
      return base("unsupported", { code: e.code, message: e.reason });
    }
    throw e;
  }

  // Limits are optional: an absent limit is not enforced (no timeout of our own, Playwright gets 0).
  const timeoutMs = request.limits?.timeoutSeconds === undefined ? Infinity : request.limits.timeoutSeconds * 1000;
  const deadline = Date.now() + timeoutMs;
  const remaining = () => Math.max(1000, deadline - Date.now());

  // Pin the target host to the addresses we vetted so Chromium cannot be re-bound to another
  // answer mid-scan; subresource hosts are re-checked per request by the guard and by the proxy.
  const args = ["--disable-extensions", "--disable-background-networking", "--no-first-run", "--disable-sync"];
  if (!checked.literalIp) {
    args.push(`--host-resolver-rules=MAP ${checked.hostname} ${addresses[0]}`);
  }
  const proxy = request.policy.proxyServer ? { server: request.policy.proxyServer } : undefined;

  let browser: Browser | undefined;
  const guard = new RequestGuard({ policy, resolver, maxRequests: request.limits?.maxRequests ?? Infinity, maxResponseBytes: request.limits?.maxResponseBytes ?? Infinity });
  try {
    browser = await chromium.launch({ headless: true, chromiumSandbox: request.policy.chromiumSandbox ?? false, args, ...(proxy ? { proxy } : {}) });
    const version = engineVersion(browser.version());
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      acceptDownloads: false,
      javaScriptEnabled: true,
      ignoreHTTPSErrors: false,
      userAgent: "Mozilla/5.0 (X11; Linux x86_64) KolderaScanner/0.1 (+automated accessibility check)",
      serviceWorkers: "block",
    });
    await guard.install(context);
    const page = await context.newPage();

    let response;
    try {
      response = await page.goto(request.url, { timeout: Number.isFinite(timeoutMs) ? remaining() : 0, waitUntil: "load" });
    } catch (e) {
      return finish(base, version, guard, e);
    }
    if (guard.stats.limitExceeded) {
      return base("failed", { code: "LIMIT_EXCEEDED", message: "request or response budget exceeded during navigation" }, { engineVersion: version, summary: summary(guard, null) });
    }
    const finalUrl = redactUrl(page.url());
    try {
      checkUrlSyntax(page.url(), policy);
    } catch (e) {
      return base("unsupported", { code: "BLOCKED_DESTINATION", message: "navigation ended on a blocked destination" }, { engineVersion: version, finalUrl });
    }
    if (!response) {
      return base("failed", { code: "NAVIGATION_FAILED", message: "navigation produced no response" }, { engineVersion: version, finalUrl });
    }

    const axe = new AxeBuilder({ page }).options({ resultTypes: ["violations", "incomplete", "passes", "inapplicable"] });
    const results: AxeResults = await withTimeout(axe.analyze(), remaining(), "axe analysis timed out");
    if (guard.stats.limitExceeded) {
      return base("failed", { code: "LIMIT_EXCEEDED", message: "request or response budget exceeded during analysis" }, { engineVersion: version, finalUrl, summary: summary(guard, results) });
    }

    const findings = mapFindings(results);
    const report = redactReport(results, finalUrl, response.status());
    await mkdir(options.outputDirectory, { recursive: true });
    const reportJson = JSON.stringify(report);
    const reportPath = path.join(options.outputDirectory, REPORT_FILE);
    await writeFile(reportPath, reportJson, "utf8");
    const bytes = Buffer.byteLength(reportJson, "utf8");
    return base("completed", null, {
      engineVersion: version,
      finalUrl,
      summary: summary(guard, results),
      findings,
      artifacts: [{ kind: "web_report", path: `/work/out/${REPORT_FILE}`, sha256: createHash("sha256").update(reportJson, "utf8").digest("hex"), bytes }],
    });
  } catch (e) {
    return finish(base, engineVersion(browser?.version() ?? "not-launched"), guard, e);
  } finally {
    await browser?.close().catch(() => undefined);
  }
}

function finish(
  base: (status: WebWorkerResult["status"], error: WebWorkerResult["error"], extra?: Partial<WebWorkerResult>) => WebWorkerResult,
  version: string,
  guard: RequestGuard,
  e: unknown,
): WebWorkerResult {
  const extra = { engineVersion: version, summary: summary(guard, null) };
  if (guard.stats.navigationBlockReason) {
    return base("unsupported", { code: "BLOCKED_DESTINATION", message: guard.stats.navigationBlockReason }, extra);
  }
  if (guard.stats.limitExceeded) {
    return base("failed", { code: "LIMIT_EXCEEDED", message: "request or response budget exceeded" }, extra);
  }
  const message = e instanceof Error ? e.message : String(e);
  const code: WorkerErrorCode = /timeout|timed out/i.test(message) ? "TIMEOUT" : /net::|navigation|ERR_/i.test(message) ? "NAVIGATION_FAILED" : "WORKER_ERROR";
  // Error text may echo the URL; keep only the error class name.
  const safe = message.match(/net::[A-Z_]+/)?.[0] ?? (code === "TIMEOUT" ? "operation exceeded the time budget" : "unexpected worker failure");
  return base("failed", { code, message: safe }, extra);
}

function summary(guard: RequestGuard, results: AxeResults | null) {
  return {
    violations: results ? results.violations.reduce((n, r) => n + r.nodes.length, 0) : 0,
    incomplete: results ? results.incomplete.reduce((n, r) => n + r.nodes.length, 0) : 0,
    passes: results ? results.passes.length : 0,
    inapplicable: results ? results.inapplicable.length : 0,
    blockedRequests: guard.stats.blocked,
  };
}

function mapFindings(results: AxeResults): WebFinding[] {
  const findings: WebFinding[] = [];
  const push = (rule: AxeRule, kind: WebFinding["kind"]) => {
    for (const node of rule.nodes.slice(0, MAX_NODES_PER_RULE)) {
      findings.push({
        ruleId: rule.id,
        kind,
        impact: (rule.impact ?? node.impact ?? "unknown") as WebFinding["impact"],
        message: truncate(kind === "violation" ? rule.help : `${rule.help} (needs manual check: ${nodeReason(node)})`, 1000),
        selector: truncate(Array.isArray(node.target[0]) ? node.target[0].join(" ") : String(node.target[0] ?? ""), 1024),
        snippet: redactSnippet(node.html),
        wcagTags: rule.tags.filter((t) => /^wcag/.test(t)),
        helpUrl: rule.helpUrl ?? null,
        needsHumanReview: kind === "incomplete",
      });
    }
  };
  for (const rule of results.violations) push(rule, "violation");
  for (const rule of results.incomplete) push(rule, "incomplete");
  return findings;
}

function nodeReason(node: NodeResult): string {
  const first = [...node.any, ...node.all, ...node.none][0];
  return first?.message ? truncate(first.message, 200) : "verify manually";
}

function truncate(value: string, max: number): string {
  return value.length <= max ? value : value.slice(0, max - 1) + "…";
}

/** Collapses whitespace, masks long attribute values (tokens, data URLs) and bounds the length. */
export function redactSnippet(html: string | undefined): string | null {
  if (!html) return null;
  const collapsed = html.replace(/\s+/g, " ").replace(/=("|')([^"']{40,})("|')/g, "=$1…$3");
  return truncate(collapsed, SNIPPET_LIMIT);
}

function redactReport(results: AxeResults, finalUrl: string, httpStatus: number) {
  const strip = (rules: AxeRule[]) =>
    rules.map((r) => ({
      id: r.id,
      impact: r.impact ?? null,
      tags: r.tags,
      help: r.help,
      helpUrl: r.helpUrl,
      nodes: r.nodes.slice(0, MAX_NODES_PER_RULE).map((n) => ({ target: n.target, html: redactSnippet(n.html), failureSummary: n.failureSummary ?? null })),
    }));
  return {
    tool: "axe-core",
    axeVersion: results.testEngine.version,
    url: finalUrl,
    httpStatus,
    timestamp: results.timestamp,
    violations: strip(results.violations),
    incomplete: strip(results.incomplete),
    passes: results.passes.map((r) => r.id),
    inapplicable: results.inapplicable.map((r) => r.id),
    note: "Automated axe-core checks only. Absence of violations is not WCAG conformance.",
  };
}

function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  if (!Number.isFinite(ms)) {
    return promise;
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms);
    promise.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); reject(e); });
  });
}
