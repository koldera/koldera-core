import { navigationTimeout } from "../limits.js";
import { chromium, type Browser, type BrowserContext, type Page } from "playwright";

export const RENDERER_VERSION = "koldera-html-renderer 0.1.0";
export const PLAYWRIGHT_VERSION = "1.63.0";
export const DOCUMENT_URL = "https://render.koldera.invalid/document.html";
export const VIEWPORT = { width: 1240, height: 1754 };
/** Third layer after preflight and the deny-all route: Chromium's own CSP engine. */
const CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'";

export interface IsolatedSession {
  browser: Browser;
  context: BrowserContext;
  page: Page;
  /** Requests the page attempted besides the document itself (route aborts + CSP refusals). */
  blocked(): number;
  engineVersion: string;
  close(): Promise<void>;
}

/**
 * The one way this worker opens a document: a fresh Chromium with scripts off, downloads and service
 * workers refused, every non-document request aborted and counted, a `default-src 'none'` CSP, a host
 * resolver that answers nothing and no remote fonts. Shared by render, the semantic collector and
 * the source-order fix so all three see the same page.
 */
export async function openIsolated(html: string, timeoutMs: number, chromiumSandbox = false): Promise<IsolatedSession> {
  let blocked = 0;
  // chromiumSandbox is explicit: false (default) keeps --no-sandbox, true needs unprivileged user namespaces.
  const browser = await chromium.launch({
    headless: true,
    chromiumSandbox,
    args: ["--disable-extensions", "--disable-background-networking", "--disable-sync", "--no-first-run", "--disable-remote-fonts", "--host-resolver-rules=MAP * ~NOTFOUND"],
  });
  try {
    const context = await browser.newContext({
      javaScriptEnabled: false,
      serviceWorkers: "block",
      acceptDownloads: false,
      bypassCSP: false,
      viewport: VIEWPORT,
    });
    await context.route("**/*", async (route) => {
      if (route.request().url() === DOCUMENT_URL && route.request().isNavigationRequest()) {
        await route.fulfill({ status: 200, contentType: "text/html; charset=utf-8", body: html, headers: { "content-security-policy": CSP, "cache-control": "no-store" } });
        return;
      }
      blocked += 1;
      await route.abort("blockedbyclient");
    });
    const page = await context.newPage();
    page.on("console", (message) => {
      if (message.type() === "error" && /Content Security Policy/i.test(message.text())) {
        blocked += 1;
      }
    });
    await page.goto(DOCUMENT_URL, { waitUntil: "load", timeout: navigationTimeout(timeoutMs) });
    return {
      browser,
      context,
      page,
      blocked: () => blocked,
      engineVersion: `${RENDERER_VERSION}; playwright ${PLAYWRIGHT_VERSION}; chromium ${browser.version()}`,
      close: () => browser.close().catch(() => undefined),
    };
  } catch (e) {
    await browser.close().catch(() => undefined);
    throw e;
  }
}

export const NOT_LAUNCHED = `${RENDERER_VERSION}; playwright ${PLAYWRIGHT_VERSION}; chromium not-launched`;
