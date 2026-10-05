import { budgetFromSeconds, definedOnly, limitOf } from "../limits.js";
import { createHash } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import type { HtmlSemanticRequest, HtmlSemanticResult, RendererErrorCode } from "../contracts/types.js";
import { preflight } from "../preflight/preflight.js";
import { NOT_LAUNCHED, openIsolated, type IsolatedSession } from "../render/isolated.js";
import { RenderTimeout, timeout } from "../render/renderer.js";
import { collectElements, deriveFindings, measureFocusOrder, toNodes, visualCandidate } from "./collector.js";

export interface SemanticOptions {
  workRoot: string;
}

/**
 * `semantic` verb: the DOM (reading) order, rendered geometry and measured keyboard focus order
 * of a vetted static source, plus deterministic candidate findings. Read-only; the same isolated
 * Chromium as the renderer.
 */
export async function collectSemantic(request: HtmlSemanticRequest, options: SemanticOptions): Promise<HtmlSemanticResult> {
  const inputPath = path.join(options.workRoot, "in", "source.html");
  const base = (status: HtmlSemanticResult["status"], error: HtmlSemanticResult["error"], extra: Partial<HtmlSemanticResult> = {}): HtmlSemanticResult => ({
    specVersion: "1.0",
    jobId: request.jobId,
    status,
    sourceSha256: null,
    engineVersion: NOT_LAUNCHED,
    nodes: [],
    focusOrderNodeIds: [],
    focusOrderMeasured: false,
    visualOrderCandidateNodeIds: [],
    findings: [],
    error,
    ...extra,
  });

  let bytes: Buffer;
  try {
    const info = await stat(inputPath);
    if (info.size > limitOf(request.limits?.maxBytes)) {
      return base("failed", { code: "LIMIT_EXCEEDED", message: `source is ${info.size} bytes, above maxBytes=${request.limits?.maxBytes}` });
    }
    bytes = await readFile(inputPath);
  } catch {
    return base("failed", { code: "INVALID_INPUT", message: "source document is missing or unreadable" });
  }
  const sourceSha256 = createHash("sha256").update(bytes).digest("hex");
  const checked = preflight(bytes, { ...definedOnly({ maxInlineImageBytes: request.limits?.maxInlineImageBytes, maxTotalInlineImageBytes: request.limits?.maxTotalInlineImageBytes }) });
  if (!checked.ok) {
    return base("unsupported", { code: "UNSUPPORTED_CONTENT", message: "the source cannot be rendered as static HTML" }, { sourceSha256 });
  }
  return collectVetted(checked.vetted, request, sourceSha256);
}

/** Collects from already vetted HTML (shared with the source-order fix, which re-checks its corrected copy). */
export async function collectVetted(html: string, request: Pick<HtmlSemanticRequest, "jobId" | "limits" | "policy">, sourceSha256: string): Promise<HtmlSemanticResult> {
  const budgetMs = budgetFromSeconds(request.limits?.timeoutSeconds);
  const deadline = Date.now() + budgetMs;
  const remaining = () => Math.max(1, deadline - Date.now());
  let session: IsolatedSession | undefined;
  let engineVersion = NOT_LAUNCHED;
  const finish = (status: HtmlSemanticResult["status"], code: RendererErrorCode | null, message: string, extra: Partial<HtmlSemanticResult> = {}): HtmlSemanticResult => ({
    specVersion: "1.0",
    jobId: request.jobId,
    status,
    sourceSha256,
    engineVersion,
    nodes: [],
    focusOrderNodeIds: [],
    focusOrderMeasured: false,
    visualOrderCandidateNodeIds: [],
    findings: [],
    error: code ? { code, message } : null,
    ...extra,
  });
  try {
    const work = (async () => {
      session = await openIsolated(html, remaining(), request.policy?.chromiumSandbox ?? false);
      engineVersion = session.engineVersion;
      // Requests the page attempts are aborted by the deny-all route; the DOM is collected as rendered without them.
      const { elements, truncated } = await collectElements(session.page, Math.min(limitOf(request.limits?.maxNodes), Number.MAX_SAFE_INTEGER));
      if (truncated) {
        return finish("failed", "LIMIT_EXCEEDED", `the document has more than maxNodes=${request.limits?.maxNodes} elements`);
      }
      const nodes = toNodes(elements, sourceSha256);
      const focus = await measureFocusOrder(session.page, nodes);
      const visual = visualCandidate(nodes);
      const findings = deriveFindings(nodes, focus, visual);
      return finish("completed", null, "", { nodes, focusOrderNodeIds: focus.order, focusOrderMeasured: focus.measured, visualOrderCandidateNodeIds: visual, findings });
    })();
    work.catch(() => undefined);
    return await Promise.race(Number.isFinite(budgetMs) ? [work, timeout(remaining())] : [work]);
  } catch (e) {
    if (e instanceof RenderTimeout || (e instanceof Error && /Timeout \d+ms exceeded/.test(e.message))) {
      return finish("failed", "TIMEOUT", `the collector exceeded its budget of ${budgetMs} ms`);
    }
    return finish("failed", "RENDER_FAILED", `the collector failed (${e instanceof Error ? e.name : "unknown"})`);
  } finally {
    if (session) {
      await session.close();
    }
  }
}
