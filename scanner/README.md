# Web scanner

Scans one page for accessibility problems with Playwright, Chromium and axe-core 4.13. One URL per job,
automated findings only: never a conformance verdict. `manualReviewRequired` is always `true`, and axe
`incomplete` items are kept as findings for a human to review.

Requires Node 22 and the Chromium build that matches the pinned Playwright version.

```
pnpm install --frozen-lockfile
pnpm exec playwright install chromium-headless-shell
pnpm build && pnpm test
```

The package launches headless Chromium, which Playwright serves as its headless shell, so that is the browser to install
(once per Playwright version). On a bare Linux host add `--with-deps` for the system libraries; the Docker image does the
same with `playwright install --with-deps --only-shell chromium`.

## CLI

```
node dist/cli.js scan --request request.json --result result.json [--work-root DIR]
```

Contracts (validated on input and output), in `contracts/` at the repository root:
`web-worker-request.schema.json` and `web-worker-result.schema.json`. The report artifact is
`<work-root>/out/web-report.json`: a redacted axe report with no query strings and bounded, masked snippets.

Exit codes: 0 result written (including `unsupported` and `failed`), 1 result could not be persisted,
2 usage error. stderr carries JSON events without URLs, content or host paths.

Status mapping: blocked destination, non-https URL, credentials in the URL or private DNS answers is
`unsupported` / `BLOCKED_DESTINATION`; a navigation error is `failed` / `NAVIGATION_FAILED`; the time budget
is `failed` / `TIMEOUT`; the request or byte budget is `failed` / `LIMIT_EXCEEDED`.

## Request safety

Application layer (`src/network`):

- https only unless the request policy sets `allowHttp`; no credentials in the URL; default ports only.
- Reserved hostnames (`localhost`, `*.local`, `*.internal`, metadata names, single labels) are refused.
- Literal IPs must be globally routable (private, loopback, link-local and CGNAT IPv4 ranges and unique-local,
  link-local, mapped, NAT64, 6to4 and documentation IPv6 ranges are refused). Without a proxy every DNS answer
  must be globally routable too.
- Every request and every redirect hop is fetched by the guard with redirects disabled and re-checked
  before it is followed; cookies, authorization and referer are stripped; request and response-byte
  budgets apply to real bodies.
- WebSockets are refused (`routeWebSocket`), `RTCPeerConnection`, `RTCDataChannel` and `WebTransport` are
  removed before page script runs, and Chromium starts with `--disable-quic` and WebRTC limited to proxied
  connections, so no UDP leaves the browser.

Connection-time layer: Chromium always talks through a proxy, so nothing it opens itself (a redirect's follow-up,
a worker's WebSocket, WebRTC over TCP) bypasses the rules.

- **With `policy.proxyServer`** (the compose setup): the proxy resolves names and enforces the destinations. The
  scanner then does not resolve host names itself, because a network that only reaches out through a proxy often
  has no DNS; the syntax, reserved-name and IP-literal checks still run first. A page the proxy refused (Squid's
  `X-Squid-Error`) is reported as `BLOCKED_DESTINATION`, never scanned. Use a proxy that denies private and
  reserved destinations and ports other than 80 and 443, as `docker/egress-proxy` does.
- **Without a proxy** (`docker run`, the CLI, Lambda): the scanner starts its own loopback gate and points
  Chromium at it. The gate applies the same checks to every connection and connects to the address it vetted,
  so a second DNS answer cannot rebind a host. It is an application-level control in the same process; a
  network-level egress filter is still the stronger setup.

The environment variable `KOLDERA_SCANNER_ALLOW_LOOPBACK_FOR_TESTS=1` admits `127.0.0.1` for the test
suite's fixture server. Never set it in a deployment.

## What it never does

It never follows a redirect or fetches a subresource without the destination check, never sends the user's
cookies or referer, never stores query strings in its report, and never states that a page is accessible or
conformant.
