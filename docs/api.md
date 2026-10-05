# HTTP API

The complete, machine-readable description is [`contracts/openapi.yaml`](../contracts/openapi.yaml) (OpenAPI 3.1).
A test compares it with the routes the server registers, so it lists exactly what exists. This page explains how the
pieces fit together.

Every result is a machine check: PDF/UA-1 rules through veraPDF, WCAG 2.2 AA automated rules through axe-core.
`manualReviewRequired` is always `true`, because a person still has to review what machines cannot decide.

## Authentication

`/v1` needs `Authorization: Bearer <token>`. Two kinds of token exist:

- a **server token** (`KOLDERA_TOKEN`), for your backend;
- a **client-session token** from `POST /v1/client-sessions`, for one browser origin, with scopes (`jobs:create`,
  `jobs:read`), a job and upload budget and a lifetime of at most 60 minutes. A session sees only the jobs it created
  and cannot mint further sessions.

`/healthz`, `PUT /v1/uploads/{id}/content` and `GET /v1/artifacts/{id}/content` need no bearer token: their signed
query string (`exp`, `sig`) is the credential. See [deployment.md](deployment.md) for where each token belongs.

## Job lifecycle

1. **Declare an upload.** `POST /v1/uploads` with `fileName`, `sizeBytes`, `sha256` and `contentType`
   (`application/pdf`, `text/html`, `image/png`, `image/jpeg` or `image/svg+xml`). The answer carries a signed
   `uploadUrl` valid for ten minutes.
2. **Send the bytes.** `PUT` the file to `uploadUrl`. The server checks size and SHA-256 against the declaration.
3. **Create a job.**
   - `POST /v1/pdf/jobs` with `mode` `analyze` or `fix` and `profile` `ua1`.
   - `POST /v1/html-to-pdf/jobs` with `mode` `generate_analyze` or `generate_fix`.
   - `POST /v1/web/jobs` with a `url` (no upload).
   - `POST /v1/documents/jobs` with a structured document as the body (see [documents.md](documents.md)).

   The answer is `202` with a `Location` header. Add `Prefer: wait=60` (1 to 900) and the server holds the request:
   `200` with the finished job if it finishes in time, otherwise `202`, and `Preference-Applied: wait=60` whenever it
   waited. Add an `Idempotency-Key` so a retry returns the stored answer instead of creating a second job.
4. **Poll** `GET /v1/jobs/{jobId}` until `state` is `completed` or `failed` (it sends `Retry-After` meanwhile).
   `completed` means the run finished, not that the document passed: read `validation.status` (`passed`, `failed`,
   `error`, `not_run`) and `failedRules`.
5. **Read the results.**
   - `GET /v1/jobs/{jobId}/findings`: what the checks found; `needsHumanReview` marks what a person must look at.
   - `GET /v1/jobs/{jobId}/actions`: which repairs ran, were skipped or were rolled back, with reasons.
   - `GET /v1/jobs/{jobId}/artifacts`: files with signed `downloadUrl` (the fixed copy is the `remediated` kind, a
     generated PDF is `generated_pdf`, a page scan `web_report`). Links last ten minutes; request the list again for
     fresh ones.

The input file is never modified. A fixed copy that fails an integrity or regression gate is discarded and the job
reports `remediationStatus: rolled_back`.

## Reading order

Reading order, focus order and visual geometry are different things, and no machine check decides which sequence
is right.

- `GET /v1/jobs/{jobId}/reading-order` returns evidence: nodes with roles, previews and boxes, bound to the SHA-256 of
  the artifact they describe. `readingOrderReview` is always `needs_review`.
- `POST /v1/jobs/{jobId}/reading-order/remediations` applies one reordering that a person approved: the complete
  sibling group of exactly one parent, in a new order, with `acknowledged: true`, the artifact hash and the snapshot
  version you reviewed. A stale snapshot answers `409 STALE_READING_ORDER_SNAPSHOT`. The worker applies the change to
  a copy and rolls back on any regression. The result is a new job (`order_pdf` or `order_html`).

## Errors

Errors are `application/problem+json` with `type`, `title`, `status`, `code` and usually `detail`. Switch on `code`:
the list is the `Problem` schema in the OpenAPI file and in [`contracts/problem.schema.json`](../contracts/problem.schema.json).
A structured document that fails validation answers `422 VALIDATION_ERROR` with `errors[]`, one entry per problem
(`path` as a JSON Pointer, `code`, `message`).

`503 QUEUE_FULL` carries `Retry-After`. Only 2xx answers are stored for idempotent replay, so a validation error or a
full queue stays retryable under the same key. Reusing a key with a different body is `409 IDEMPOTENCY_KEY_REUSED`.

## Limits of this API

Idempotency keys and session budgets are held in memory; uploads, jobs and artifacts are kept for
`KOLDERA_RETENTION_HOURS`. The server is a single instance. See [configuration.md](configuration.md).
