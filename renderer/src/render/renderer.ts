import { budgetFromSeconds, definedOnly, limitOf, navigationTimeout } from "../limits.js";
import { createHash } from "node:crypto";
import { readFile, rename, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { PDFDocument } from "pdf-lib";
import { ContentNotFound, ContentSelectorInvalid, selectContent, type ContentSelectionOutcome } from "./content.js";
import {
  altMissingFates,
  describePrintedAsDecorative,
  markAltMissingImages,
  markedImagesPresent,
  markImagesWithoutAltDecorative,
  unmarkAltMissingImages,
  type AltMissingFate,
} from "./decorative.js";
import { offlineDiagnostics, prepareOffline, type OfflineOutcome } from "./offline.js";
import { clampRowSpans, keepEmptyCells } from "./cells.js";
import { nameInlineSvgs, svgDiagnostics, type SvgOutcome } from "./svg.js";
import type { Page } from "playwright";
import type { Diagnostic, HtmlRendererRequest, HtmlRendererResult, RendererErrorCode } from "../contracts/types.js";
import { preflight } from "../preflight/preflight.js";
import { NOT_LAUNCHED, openIsolated, type IsolatedSession } from "./isolated.js";

export interface RenderOptions {
  /** Local directory standing in for the canonical /work (contains in/ and out/). */
  workRoot: string;
  /** Test seam: prove the browser-level layers on their own. Never set by the CLI. */
  skipPreflightForTests?: boolean;
  /** Test seam: shrink the render budget below the schema minimum. */
  renderBudgetMsForTests?: number;
  /** Test seam: observe the loaded page before the PDF is produced. */
  onPageForTests?: (page: Page) => Promise<void>;
}

const OUTPUT_NAME = "generated.pdf";

/**
 * One isolated render: read the vetted source, preflight it, load it into the isolated Chromium
 * (see isolated.ts), ask for a tagged PDF, verify the page cap and publish the bytes with their
 * hash. `taggedRequested` in the result is a statement about the request to Chromium only.
 */
export async function render(request: HtmlRendererRequest, options: RenderOptions): Promise<HtmlRendererResult> {
  const inputPath = path.join(options.workRoot, "in", "source.html");
  const base = (status: HtmlRendererResult["status"], error: HtmlRendererResult["error"], extra: Partial<HtmlRendererResult> = {}): HtmlRendererResult => ({
    specVersion: "1.0",
    jobId: request.jobId,
    status,
    sourceSha256: null,
    engineVersion: NOT_LAUNCHED,
    taggedRequested: true,
    pages: null,
    blockedRequests: 0,
    diagnostics: [],
    artifacts: [],
    manualReviewRequired: true,
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
  if (bytes.length === 0) {
    return base("failed", { code: "INVALID_INPUT", message: "source document is empty" });
  }
  const sourceSha256 = createHash("sha256").update(bytes).digest("hex");

  let html: string;
  let diagnostics: Diagnostic[] = [];
  if (options.skipPreflightForTests) {
    html = new TextDecoder("utf-8").decode(bytes);
  } else {
    const checked = preflight(bytes, { ...definedOnly({ maxInlineImageBytes: request.limits?.maxInlineImageBytes, maxTotalInlineImageBytes: request.limits?.maxTotalInlineImageBytes }) });
    diagnostics = checked.diagnostics;
    if (!checked.ok) {
      const blocking = checked.diagnostics.filter((d) => d.blocking).length;
      return base("unsupported", { code: "UNSUPPORTED_CONTENT", message: `the source cannot be rendered as static HTML (${blocking} blocking finding${blocking === 1 ? "" : "s"})` }, { sourceSha256, diagnostics });
    }
    // The rendered copy: scripts, frames, plug-ins, <link>/<base>, meta refresh and event handlers left out (each reported above).
    html = checked.vetted;
  }

  const budgetMs = options.renderBudgetMsForTests ?? budgetFromSeconds(request.limits?.timeoutSeconds);
  const outcome = await renderVetted(html, request, { workRoot: options.workRoot, budgetMs, onPageForTests: options.onPageForTests });
  if (outcome.contentSelection && request.content) {
    const { kept, dropped } = outcome.contentSelection;
    diagnostics = [...diagnostics, { code: "HTML_CONTENT_SELECTED", message: `only the ${kept} element${kept === 1 ? "" : "s"} matching the content selector ${kept === 1 ? "was" : "were"} printed; ${dropped} top-level element${dropped === 1 ? "" : "s"} of <body> outside the selection ${dropped === 1 ? "was" : "were"} left out of the PDF`, blocking: false, line: null, element: request.content.selector.slice(0, 64) }];
  }
  if (outcome.offline) {
    diagnostics = [...diagnostics, ...offlineDiagnostics(outcome.offline)];
  }
  if (outcome.svg) {
    diagnostics = [...diagnostics, ...svgDiagnostics(outcome.svg)];
  }
  if (outcome.status === "completed") {
    // The printed PDF carries such images as decorative; the finding says so.
    diagnostics = describePrintedAsDecorative(diagnostics, outcome.altMissing);
  }
  if (outcome.blockedRequests > 0) {
    // Evidence from the second layer: whatever the source referenced, nothing left the sandbox and the PDF lacks it.
    diagnostics = [...diagnostics, { code: "HTML_EXTERNAL_REQUESTS_BLOCKED", message: `${outcome.blockedRequests} network request(s) were blocked; the PDF is rendered without those resources`, blocking: false, line: null, element: null }];
  }
  return base(outcome.status, outcome.error, { sourceSha256, engineVersion: outcome.engineVersion, diagnostics, blockedRequests: outcome.blockedRequests, pages: outcome.pages, artifacts: outcome.artifacts });
}

export interface VettedRenderOutcome {
  status: HtmlRendererResult["status"];
  error: HtmlRendererResult["error"];
  engineVersion: string;
  blockedRequests: number;
  pages: number | null;
  artifacts: HtmlRendererResult["artifacts"];
  /** PNG screenshot of the loaded page (full page, before printing); for the order fix's visual gate. */
  screenshot: Buffer | null;
  /** What the content selection kept and dropped; null without a selection. */
  contentSelection: ContentSelectionOutcome | null;
  /** Dead links and images that did not load, as changed before printing; null when the page never got that far. */
  offline: OfflineOutcome | null;
  /** Per <img> without alt (document order): printed as decorative, left out as not loaded, or not printed; null when the page never got that far. */
  altMissing: AltMissingFate[] | null;
  /** How many inline <svg> elements were named from their own markup vs. left decorative; null when the page never got that far. */
  svg: SvgOutcome | null;
}

/**
 * Renders HTML that already passed preflight (or a corrected copy of such HTML) to
 * `<workRoot>/out/<outputName>`; shared by the render verb and the source-order fix.
 */
export async function renderVetted(
  html: string,
  request: Pick<HtmlRendererRequest, "jobId" | "outputDirectory" | "limits" | "page" | "content" | "policy">,
  options: {
    workRoot: string;
    budgetMs: number;
    outputName?: string;
    onPageForTests?: ((page: Page) => Promise<void>) | undefined;
    /** Test seam: observe the page exactly as it is printed (after every DOM step). */
    onPrintForTests?: ((page: Page) => Promise<void>) | undefined;
    screenshot?: boolean;
  },
): Promise<VettedRenderOutcome> {
  const outputName = options.outputName ?? OUTPUT_NAME;
  const outputPath = path.join(options.workRoot, "out", outputName);
  const deadline = Date.now() + options.budgetMs;
  const remaining = () => Math.max(1, deadline - Date.now());
  let session: IsolatedSession | undefined;
  let engineVersion = NOT_LAUNCHED;
  const finish = (status: HtmlRendererResult["status"], code: RendererErrorCode | null, message: string, extra: Partial<VettedRenderOutcome> = {}): VettedRenderOutcome => ({
    status,
    error: code ? { code, message } : null,
    engineVersion,
    blockedRequests: session?.blocked() ?? 0,
    pages: null,
    artifacts: [],
    screenshot: null,
    contentSelection: null,
    offline: null,
    altMissing: null,
    svg: null,
    ...extra,
  });
  try {
    const work = (async () => {
      session = await openIsolated(html, remaining(), request.policy?.chromiumSandbox ?? false);
      engineVersion = session.engineVersion;
      const page = session.page;
      // Number the images preflight reports as HTML_IMAGE_ALT_MISSING before any DOM step, to say later what became of each.
      await markAltMissingImages(page);
      const altMissingTotal = (await markedImagesPresent(page)).length;
      // Content selection happens on the loaded DOM, before anything is measured or printed.
      const contentSelection = request.content ? await selectContent(page, request.content.selector) : null;
      if (options.onPageForTests) {
        await options.onPageForTests(page);
      }
      // Links into the render host, images that never loaded and an invented en-US.
      const selected = await markedImagesPresent(page);
      const offline = await prepareOffline(page);
      // An empty table cell must keep its place in the tag tree.
      await clampRowSpans(page);
      await keepEmptyCells(page);
      // SVGs are named before decorative-image marking, on the same DOM, before printing.
      const svg = await nameInlineSvgs(page);
      // An <img> without an alt attribute is presentation, not content.
      await markImagesWithoutAltDecorative(page);
      const altMissing = altMissingFates(altMissingTotal, selected, await markedImagesPresent(page));
      await unmarkAltMissingImages(page);
      if (options.onPrintForTests) {
        await options.onPrintForTests(page);
      }
      // Requests the page attempts are aborted by the deny-all route and counted; the caller reports the count.
      const screenshot = options.screenshot ? await page.screenshot({ fullPage: true, type: "png", timeout: navigationTimeout(remaining()) }) : null;
      const pdf = await page.pdf({
        tagged: true,
        printBackground: request.page.printBackground ?? true,
        format: request.page.format,
        preferCSSPageSize: true,
        displayHeaderFooter: false,
        outline: true,
      });
      const pages = (await PDFDocument.load(pdf, { updateMetadata: false, ignoreEncryption: true })).getPageCount();
      if (pages > limitOf(request.limits?.maxPages)) {
        return finish("failed", "LIMIT_EXCEEDED", `the rendered document has ${pages} pages, above maxPages=${request.limits?.maxPages}`, { pages, offline, altMissing, svg });
      }
      const tmp = outputPath + ".tmp";
      await writeFile(tmp, pdf);
      await rename(tmp, outputPath);
      const sha256 = createHash("sha256").update(pdf).digest("hex");
      return finish("completed", null, "", {
        pages,
        artifacts: [{ kind: "generated_pdf", path: `${request.outputDirectory}/${outputName}`, sha256, bytes: pdf.length }],
        screenshot,
        contentSelection,
        offline,
        altMissing,
        svg,
      });
    })();
    // If the timer wins, the abandoned work settles later (browser closed below); never unhandled.
    work.catch(() => undefined);
    return await Promise.race(Number.isFinite(options.budgetMs) ? [work, timeout(remaining())] : [work]);
  } catch (e) {
    if (e instanceof RenderTimeout || (e instanceof Error && /Timeout \d+ms exceeded/.test(e.message))) {
      return finish("failed", "TIMEOUT", `the render exceeded its budget of ${options.budgetMs} ms`);
    }
    if (e instanceof ContentSelectorInvalid) {
      return finish("failed", "INVALID_INPUT", "the content selector is not a valid CSS selector");
    }
    if (e instanceof ContentNotFound) {
      return finish("failed", "CONTENT_NOT_FOUND", "no element of the document matches the content selector; nothing was printed");
    }
    // Messages from Chromium may quote page content; only the error type is reported.
    return finish("failed", "RENDER_FAILED", `the renderer failed (${e instanceof Error ? e.name : "unknown"})`);
  } finally {
    if (session) {
      await session.close();
    }
  }
}

export class RenderTimeout extends Error {
  constructor() {
    super("render budget exhausted");
    this.name = "RenderTimeout";
  }
}

export function timeout(ms: number): Promise<never> {
  return new Promise((_, reject) => setTimeout(() => reject(new RenderTimeout()), ms));
}
