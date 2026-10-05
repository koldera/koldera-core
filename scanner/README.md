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
- Literal IPs and every DNS answer must be globally routable (private, loopback, link-local and CGNAT IPv4
  ranges and unique-local, link-local, mapped, NAT64, 6to4 and documentation IPv6 ranges are refused).
- The target host is pinned to the vetted address with `--host-resolver-rules`.
- Every request and every redirect hop is fetched by the guard with redirects disabled and re-checked
  before it is followed; cookies, authorization and referer are stripped; request and response-byte
  budgets apply to real bodies.

Connection-time layer: the application checks cannot see what a resolver answers the browser. Run the
scanner behind an egress proxy or firewall that denies private and reserved destinations and ports other
than 80 and 443, and pass it as `policy.proxyServer`.

The environment variable `KOLDERA_SCANNER_ALLOW_LOOPBACK_FOR_TESTS=1` admits `127.0.0.1` for the test
suite's fixture server. Never set it in a deployment.

## What it never does

It never follows a redirect or fetches a subresource without the destination check, never sends the user's
cookies or referer, never stores query strings in its report, and never states that a page is accessible or
conformant.
