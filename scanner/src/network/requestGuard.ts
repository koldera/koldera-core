import type { APIResponse, BrowserContext, Route } from "playwright";
import { BlockedDestinationError, checkUrlSyntax, resolvePublic, type DestinationPolicy, type Resolver } from "./urlPolicy.js";

export interface GuardStats {
  requests: number;
  blocked: number;
  /** Reason of the first blocked request or redirect hop on the main navigation, if any. */
  navigationBlockReason: string | null;
  responseBytes: number;
  limitExceeded: boolean;
}

export interface GuardOptions {
  policy: DestinationPolicy;
  resolver: Resolver;
  maxRequests: number;
  maxResponseBytes: number;
}

const STRIPPED_HEADERS = new Set(["cookie", "authorization", "referer", "proxy-authorization", "x-forwarded-for"]);
const MAX_REDIRECT_HOPS = 10;
/** Set by the scanner's egress gate on every refusal (lower case: Playwright reports header names that way). */
export const PROXY_REFUSED_HEADER = "x-koldera-egress-refused";

/**
 * Per-request gate on the browser context. Playwright does not route a redirect chain's follow-ups,
 * so the guard fetches itself with redirects disabled, re-checks each Location against the
 * destination policy before following it, enforces request/byte budgets and strips identifying
 * headers. A navigation is finally redirected once to the vetted end of the chain.
 */
export class RequestGuard {
  readonly stats: GuardStats = { requests: 0, blocked: 0, navigationBlockReason: null, responseBytes: 0, limitExceeded: false };

  constructor(private readonly options: GuardOptions) {}

  async install(context: BrowserContext): Promise<void> {
    await context.route("**/*", (route) => this.handle(route));
    // `route` never sees WebSockets. A routed socket that is never connected to its server stays a local mock, so
    // closing it refuses every WebSocket (a page could otherwise reach loopback or private hosts this way).
    await context.routeWebSocket(() => true, async (ws) => {
      this.stats.blocked += 1;
      await ws.close({ code: 1008, reason: "blocked by the scanner" }).catch(() => undefined);
    });
  }

  private async handle(route: Route): Promise<void> {
    const request = route.request();
    const isNavigation = request.isNavigationRequest();
    try {
      if (!(await this.admit(request.url(), isNavigation))) {
        await route.abort("blockedbyclient");
        return;
      }
      const headers: Record<string, string> = {};
      for (const [name, value] of Object.entries(request.headers())) {
        if (!STRIPPED_HEADERS.has(name.toLowerCase())) {
          headers[name] = value;
        }
      }

      let currentUrl = request.url();
      let response: APIResponse = await route.fetch({ headers, maxRedirects: 0 });
      let hops = 0;
      while (!this.refusedByProxy(response, isNavigation) && isRedirect(response.status()) && response.headers()["location"] !== undefined) {
        hops += 1;
        if (hops > MAX_REDIRECT_HOPS) {
          this.block(isNavigation, "too many redirects");
          await route.abort("blockedbyclient");
          return;
        }
        let target: string;
        try {
          target = new URL(response.headers()["location"] ?? "", currentUrl).toString();
        } catch {
          this.block(isNavigation, "redirect to an invalid location");
          await route.abort("blockedbyclient");
          return;
        }
        if (!(await this.admit(target, isNavigation))) {
          await route.abort("blockedbyclient");
          return;
        }
        currentUrl = target;
        response = await route.fetch({ url: target, headers, maxRedirects: 0 });
      }
      if (this.refusedByProxy(response, isNavigation)) {
        await route.abort("blockedbyclient");
        return;
      }

      const body = await response.body();
      this.stats.responseBytes += body.length;
      if (this.stats.responseBytes > this.options.maxResponseBytes) {
        this.stats.limitExceeded = true;
        this.stats.blocked += 1;
        await route.abort("blockedbyclient");
        return;
      }
      if (hops > 0 && isNavigation) {
        // Hand the browser one vetted hop so its URL bar and base URL end up on the real page.
        await route.fulfill({ status: 302, headers: { location: currentUrl } });
        return;
      }
      await route.fulfill({ response, body });
    } catch {
      // Network-level failures (refused, reset, TLS) surface to the page as a failed request.
      await route.abort("failed").catch(() => undefined);
    }
  }

  /** Counts the request against the budget and checks the destination policy. */
  private async admit(url: string, isNavigation: boolean): Promise<boolean> {
    this.stats.requests += 1;
    if (this.stats.requests > this.options.maxRequests || this.stats.limitExceeded) {
      this.stats.limitExceeded = true;
      this.stats.blocked += 1;
      return false;
    }
    try {
      const checked = checkUrlSyntax(url, this.options.policy);
      await resolvePublic(checked, this.options.resolver, this.options.policy);
      return true;
    } catch (e) {
      this.block(isNavigation, e instanceof BlockedDestinationError ? e.reason : "blocked");
      return false;
    }
  }

  /**
   * A proxy that refuses a CONNECT answers with its own error page, and the proxy agent hands that page back as if the
   * site had sent it. Squid marks it with X-Squid-Error, the scanner's own gate with X-Koldera-Egress-Refused; such a
   * page is a refused destination, never something to scan.
   */
  private refusedByProxy(response: APIResponse, isNavigation: boolean): boolean {
    const h = response.headers();
    if (h[PROXY_REFUSED_HEADER] === undefined && !(this.options.policy.resolveViaProxy && h["x-squid-error"] !== undefined)) {
      return false;
    }
    this.block(isNavigation, "the egress proxy refused the destination");
    return true;
  }

  /** A connection the egress gate refused (outside the route, so not tied to a navigation). */
  countBlocked(): void {
    this.stats.blocked += 1;
  }

  private block(isNavigation: boolean, reason: string): void {
    this.stats.blocked += 1;
    if (isNavigation && this.stats.navigationBlockReason === null) {
      this.stats.navigationBlockReason = reason;
    }
  }
}

function isRedirect(status: number): boolean {
  return status === 301 || status === 302 || status === 303 || status === 307 || status === 308;
}
