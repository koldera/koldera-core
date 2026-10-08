# koldera-core

Self-hosted accessibility checks: PDF/UA-1 validation with veraPDF, fixed PDF copies, HTML and structured documents
rendered to tagged PDF, and axe-core scans of web pages. One container, an HTTP API and a command-line tool.

Everything it reports is a **machine check**: PDF/UA-1 rules through veraPDF and WCAG 2.2 AA automated rules through
axe-core. Automated checks cannot decide everything, so a person still has to review the result.

Status: early release (0.1.0). The API may change before 1.0.

No telemetry: koldera-core does not send data anywhere; documents stay on your server.

## What it does

- **PDF analyze and fix.** Validates a PDF against the PDF/UA-1 profile. With `fix` it writes a separate repaired
  copy (title, language, metadata, structure tags for untagged files, repairs of existing tags, links, tab order,
  fonts) and re-validates it. A copy that fails an integrity or regression gate is discarded.
- **HTML to tagged PDF.** Renders one static HTML file with Chromium (scripts off, no network) to a tagged PDF and
  checks it.
- **Web scan.** Scans one public page with axe-core and returns findings, including items that need a human.
- **Structured documents to PDF/UA.** A JSON document of semantic blocks is rendered with Typst to a tagged PDF and
  checked. See [docs/documents.md](docs/documents.md).
- **Reading-order evidence and approved corrections.** Shows the order of the structure tree or DOM as evidence, and
  applies one reordering that a person approved, with rollback on any regression.

## What it never does

- Modify your original file, or silently repair signed, encrypted or malformed documents.
- Generate alternative text, or guess headings, tables, lists or reading order beyond the documented inference.
- Decide that a reading order or a page is right. Findings carry `needsHumanReview`, and `manualReviewRequired` is
  always `true`.
- Claim conformance. A `pdfuaid:part=1` identifier is a PDF/UA-1 conformance declaration, so the engine never writes
  it by default; the API offers no way to request it. The engine writes it only for an explicit worker request after
  human review, and never over structure it inferred or figures it marked as artifact.

Known limits: Typst 0.15.1 cannot tag row headers as TH, so a structured-document table with `rowHeaders: true` is
refused (`422 VALIDATION_ERROR`) instead of rendered with untagged row headers; links in a document's header and footer
render as plain text; Chromium runs without its own sandbox in the supplied container (see
[docs/deployment.md](docs/deployment.md#browser-sandbox)); idempotency keys and client-session budgets are held in
memory and lost on restart; it is a single instance and does not scale horizontally.

## Quick start: Docker

The image is not published to a public registry. Load it from your release bundle
(`docker load -i koldera-core-v0.1.0-linux-amd64.tar`, which gives `koldera-core:0.1.0`) or build it from source with
`docker build -f docker/Dockerfile -t koldera-core:0.1.0 .`.

```bash
export KOLDERA_TOKEN="$(openssl rand -base64 32)"
docker run -d --name koldera -p 8080:8080 -v koldera-data:/data \
  -e KOLDERA_TOKEN koldera-core:0.1.0
curl -H "Authorization: Bearer $KOLDERA_TOKEN" http://localhost:8080/v1/info
```

Web scans run page JavaScript. In this quick start the scanner refuses private destinations itself (see
[scanner/README.md](scanner/README.md#request-safety)); for anything beyond a trial use the compose setup with its
egress proxy ([docs/deployment.md](docs/deployment.md)), or switch scans off with `-e KOLDERA_FEATURES=pdf,html,document`.

Check a PDF and download a fixed copy (needs `curl`, `jq`, `sha256sum`):

```bash
BASE=http://localhost:8080
AUTH="Authorization: Bearer $KOLDERA_TOKEN"
FILE=report.pdf

# 1. Declare the upload and get a signed URL
TICKET=$(curl -sS -X POST "$BASE/v1/uploads" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"fileName\":\"$FILE\",\"sizeBytes\":$(wc -c < "$FILE"),\"sha256\":\"$(sha256sum "$FILE" | cut -d' ' -f1)\",\"contentType\":\"application/pdf\"}")

# 2. Send the bytes
curl -sS -X PUT "$(echo "$TICKET" | jq -r .uploadUrl)" --data-binary @"$FILE"

# 3. Create a job and wait up to 60 seconds for it
JOB=$(curl -sS -X POST "$BASE/v1/pdf/jobs" -H "$AUTH" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" -H "Prefer: wait=60" \
  -d "{\"uploadId\":\"$(echo "$TICKET" | jq -r .uploadId)\",\"mode\":\"fix\",\"profile\":\"ua1\"}")
JOB_ID=$(echo "$JOB" | jq -r .id)
echo "$JOB" | jq '{state, validation, remediationStatus, manualReviewRequired}'

# 4. Findings, then the fixed copy
curl -sS -H "$AUTH" "$BASE/v1/jobs/$JOB_ID/findings" | jq '.items[] | {ruleId, message, needsHumanReview}'
URL=$(curl -sS -H "$AUTH" "$BASE/v1/jobs/$JOB_ID/artifacts" | jq -r '.items[] | select(.kind=="remediated") | .downloadUrl')
curl -sS -o report.fixed.pdf "$URL"
```

If the job is still running after the wait, the answer is `202`; poll `GET /v1/jobs/$JOB_ID`. The full lifecycle is in
[docs/api.md](docs/api.md) and the contract is [contracts/openapi.yaml](contracts/openapi.yaml).

## Command line

The image contains the command-line tool, so no server is needed:

```bash
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$PWD:/work" -w /work koldera-core:0.1.0 pdf report.pdf --fix
```

It writes `report.remediated.pdf` and the reports to the current directory (or to `--out <dir>`) and never touches the
original. Commands: `pdf`, `html`, `scan`, `document`; add `--json` for machine-readable output.

| Exit code | Meaning |
| --- | --- |
| `0` | The machine check passed. |
| `2` | The job completed but machine rules failed. |
| `3` | The job failed, the input is unsupported, or there is no machine verdict. |
| `64` | Usage error. |
| `130` | Interrupted (Ctrl+C or SIGINT). |

GitHub Actions:

```yaml
- name: Check PDFs for PDF/UA-1 machine rules
  run: |
    docker run --rm --user "$(id -u):$(id -g)" \
      -v "$PWD:/work" -w /work koldera-core:0.1.0 pdf docs/report.pdf --out koldera-out
```

The step fails on exit code 2 or 3. Remember that a pass is a machine check, not a review.

## Configuration

The most used options (all of them, with defaults, are in [docs/configuration.md](docs/configuration.md)):

| Variable | Default | Meaning |
| --- | --- | --- |
| `KOLDERA_TOKEN` | generated and logged once | Bearer token for `/v1`; comma-separate several. |
| `KOLDERA_DATA_DIR` | `/data` | Uploads, jobs and artifacts. Mount a volume. |
| `KOLDERA_PUBLIC_URL` | from the request | Base URL in signed links; set it behind a TLS proxy. |
| `KOLDERA_FEATURES` | `pdf,html,web,document` | Enabled features. Turn `web` off if you do not need scans. |
| `KOLDERA_RETENTION_HOURS` | `24` | How long results are kept. |
| `KOLDERA_PDF_MAX_BYTES` | no limit | Largest PDF upload; set it for untrusted users. |
| `KOLDERA_JOB_TIMEOUT_SECONDS` | `900` | Time one job may run, all its steps together. |
| `KOLDERA_DOCUMENT_MAX_BYTES` | `10485760` (10 MiB) | Largest structured-document request body. |
| `KOLDERA_IMAGE_MAX_BYTES` | `20971520` (20 MiB) | Largest image upload. |
| `KOLDERA_CONCURRENCY` | half the CPUs | Jobs that run at once. |

## Secure deployment

Call the API from your backend, not from the open internet. For browsers, mint short-lived client sessions bound to
one origin; for people, put single sign-on in front. `docker/compose.yaml` is a hardened single-host setup (read-only
root, no capabilities, an internal network, and an egress proxy that refuses private and metadata addresses for web
scans). Details, including the Chromium sandbox trade-off, are in [docs/deployment.md](docs/deployment.md).
To run on AWS Lambda see [docs/aws-lambda.md](docs/aws-lambda.md).

## SDKs and command-line client

These are separate projects under the Apache-2.0 licence, planned and not yet published:

- `koldera-cli`, a Rust command-line client for a remote server or the local Docker image
- `koldera-js` (TypeScript and Node.js, with React), `koldera-python`, `koldera-dotnet`, `koldera-go`

They will live under github.com/koldera. Until they are published, use the HTTP API directly.

## Components

| Directory | What it is |
| --- | --- |
| `server/` | .NET 10 HTTP server and command-line tool |
| `engine/` | Java 21 PDF engine on PDFBox and veraPDF ([engine/README.md](engine/README.md)) |
| `renderer/` | Node.js HTML and document renderer on Playwright and Typst ([renderer/README.md](renderer/README.md)) |
| `scanner/` | Node.js web scanner on Playwright and axe-core ([scanner/README.md](scanner/README.md)) |
| `contracts/` | OpenAPI file and the JSON schemas between the parts |
| `docker/` | Dockerfile, hardened compose files, egress proxy |

## Licensing

> Draft — pending legal review.

koldera-core is proprietary, commercial, source-available software ([LICENSE](LICENSE)). Using, modifying or deploying
it requires a signed Koldera Commercial License Agreement. Customers receive the source, may modify and build it for
their internal business purposes and run it on their own infrastructure; redistribution, resale and OEM embedding
need a separate agreement. See [COMMERCIAL.md](COMMERCIAL.md) or write to licensing@koldera-accessibility.dev.
Third-party components keep their own licences, listed in [NOTICE](NOTICE).

## Contributing and security

External contributions are not accepted without a separate written agreement; see [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately as
described in [SECURITY.md](SECURITY.md), not in a public issue. Participation is governed by the
[Code of Conduct](CODE_OF_CONDUCT.md).

Homepage: https://koldera-accessibility.dev
