# Configuration

koldera-core reads `KOLDERA_*` environment variables. A value that is set but invalid stops the server at start
with a message that names the variable. Secrets (`KOLDERA_TOKEN`, `KOLDERA_SIGNING_KEY`, `KOLDERA_WEB_PROXY`) are
never printed in logs.

## Server

The "Default" column shows the value when nothing is set. Where the container image sets a value itself, the
variable is marked "set by the image".

| Variable | Default | Meaning |
| --- | --- | --- |
| `KOLDERA_TOKEN` | generated at start | Bearer token for `/v1`. Several tokens: separate them with commas. When unset, a random token is generated and written once to the log as a `token_generated` event; set your own for anything that restarts. |
| `KOLDERA_AUTH` | unset (token authentication) | The only accepted value is `none`, which switches authentication off: every caller is the server. Use it only on a loopback address or behind a proxy that authenticates (see [deployment.md](deployment.md)). |
| `KOLDERA_DATA_DIR` | `/data` (set by the image) | Uploads, jobs, artifacts and the signing key. Must be writable; the server refuses to start otherwise. |
| `KOLDERA_PUBLIC_URL` | built from the request | Externally visible base URL used in signed upload and download links. Set it behind a proxy that terminates TLS, otherwise links carry the scheme the server sees (`http`). |
| `KOLDERA_SIGNING_KEY` | persisted in `$KOLDERA_DATA_DIR/.signing-key` | Base64, at least 32 bytes. Signs upload and download URLs and client-session tokens. Set the same key on every instance or execution environment that must accept each other's links. |
| `KOLDERA_FEATURES` | `pdf,html,web,document` | Comma-separated subset of `pdf`, `html`, `web`, `document`. A disabled feature answers `404` on its route. Leave `web` off if you do not need page scans. |
| `KOLDERA_CORS_ORIGINS` | none | Exact origins (comma-separated) that may call the API from a browser. A literal `*` is not supported and allows nothing. Client sessions are bound to their own origin and do not need an entry. |
| `KOLDERA_RETENTION_HOURS` | `24` | How long uploads, jobs and artifacts are kept before they are removed. |
| `KOLDERA_CONCURRENCY` | half the CPU count, at least 1 | Jobs that run at the same time. |
| `KOLDERA_QUEUE_CAPACITY` | `100` | Jobs that may wait. A full queue answers `503 QUEUE_FULL` with `Retry-After`. |
| `ASPNETCORE_URLS` | `http://+:8080` (set by the image) | Listen address (standard ASP.NET Core setting). |

## Limits

All limits are unset by default, which means no limit. Set them for any instance that accepts files from people you
do not trust.

| Variable | Meaning |
| --- | --- |
| `KOLDERA_PDF_MAX_BYTES` | Largest PDF upload, in bytes. Larger declarations are refused with `422 FILE_TOO_LARGE`. |
| `KOLDERA_PDF_MAX_PAGES` | Most pages a PDF (or a generated PDF) may have. |
| `KOLDERA_HTML_MAX_BYTES` | Largest HTML upload, in bytes. |
| `KOLDERA_JOB_TIMEOUT_SECONDS` | Time one job may run before it fails with `WORKER_TIMEOUT`. |
| `KOLDERA_WEB_MAX_RESPONSE_BYTES` | Most response bytes a web scan may download. |
| `KOLDERA_WEB_MAX_REQUESTS` | Most network requests a web scan may make. |

## Web scans and Chromium

| Variable | Default | Meaning |
| --- | --- | --- |
| `KOLDERA_WEB_PROXY` | none | Egress proxy URL for the scanner's browser. The supplied compose file points it at the Squid container that refuses private and metadata addresses and caps each response at 5 MB ([deployment.md](deployment.md#egress-proxy-limits)). With a proxy set, the scanner leaves name resolution to the proxy. |
| `KOLDERA_WEB_ALLOW_HTTP` | `false` | Allow `http://` targets. Off means https only. |
| `KOLDERA_CHROMIUM_SANDBOX` | `false` | `true` starts Chromium with its own sandbox. It only works where unprivileged user namespaces are allowed; the default Docker profile and AWS Lambda do not, so leave it unset there. See [deployment.md](deployment.md#browser-sandbox). |

## Tools the server starts

The image sets `KOLDERA_TYPST_CMD`, `VERAPDF_CMD`, `KOLDERA_CONTRACTS_DIR` and `KOLDERA_FONTS_DIR`; the other defaults below are code defaults that match the image layout. Change them only for a custom layout.

| Variable | Default | Meaning |
| --- | --- | --- |
| `KOLDERA_TYPST_CMD` | `/opt/typst/typst` (also set by the image) | Typst binary used for structured documents. |
| `VERAPDF_CMD` | `/opt/verapdf/verapdf` (set by the image) | veraPDF launcher the PDF engine calls. |
| `KOLDERA_JAVA` | `java` | Java launcher for the PDF engine. |
| `KOLDERA_NODE` | `node` | Node.js launcher for the renderer and the scanner. |
| `KOLDERA_ENGINE_JAR` | `/app/engine/koldera-pdf-engine.jar` | PDF engine. |
| `KOLDERA_RENDERER_CLI` | `/app/renderer/dist/cli.js` | HTML and document renderer. |
| `KOLDERA_SCANNER_CLI` | `/app/scanner/dist/cli.js` | Web scanner. |
| `KOLDERA_CONTRACTS_DIR` | `/app/contracts` (set by the image) | Directory of the JSON schemas the workers validate against. |
| `KOLDERA_FONTS_DIR` | `/app/renderer/fonts` (set by the image) | Bundled Noto fonts. Read by the renderer when it runs standalone; the server does not use it. |

## Command line

`koldera pdf|html|scan|document` reads the same variables (data directory, limits, Typst and worker locations,
`KOLDERA_WEB_PROXY`, `KOLDERA_WEB_ALLOW_HTTP`, `KOLDERA_CHROMIUM_SANDBOX`). Exit codes: `0` machine check passed, `2` completed with failed
machine rules, `3` job failed, unsupported or no machine verdict, `64` usage error, `130` interrupted (Ctrl+C).

## What survives a restart

Uploads, jobs and artifacts are stored in `KOLDERA_DATA_DIR`. After a restart, finished jobs stay readable until
retention removes them, queued jobs are queued again, and a job that was running fails with `WORKER_INTERRUPTED`.
Idempotency keys (24 hours) and client-session budgets are held in memory and are lost, so a retry across a restart
can create a second job and a session starts again with a full budget. The server is a single instance: the data
directory is local and there is no shared queue, so do not run several behind one load balancer.
