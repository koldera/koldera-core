# koldera-pdf-engine

Command-line engine (Java 21) that analyzes one PDF against the veraPDF **PDF/UA-1** profile and, on
request, writes a separate remediated copy using a fixed set of deterministic rules. Every copy is
re-validated and compared with the original; if a gate fails, the copy is discarded and the run is
reported as `rolled_back`.

What it never does: modify the original file, guess alternative text, infer meaning the file does not
state, or claim conformance. `status=completed` means the run finished; `manualReviewRequired` is
always `true`. A written `pdfuaid:part=1` identifier is a machine-check declaration (veraPDF and the
engine's own structure checks report nothing left), not a conformance claim. Signed and encrypted files are
never rewritten.

## Build and test

Requires JDK 21, Maven 3.9 and a veraPDF 1.30 launcher, found through `VERAPDF_CMD` (`verapdf` on
Linux, `verapdf.bat` on Windows) or `verapdf` on `PATH`.

```bash
mvn -B test      # unit tests, no veraPDF needed
mvn -B verify    # also builds target/koldera-pdf-engine.jar and runs the integration tests
```

The integration tests fail, rather than skip, when veraPDF is missing, so a green `verify` means the
real validator ran. All test PDFs are generated in-process from synthetic content.

## CLI

```
java -jar koldera-pdf-engine.jar process   --request request.json --result result.json [--work-root DIR]
java -jar koldera-pdf-engine.jar inspect   --input in.pdf --result inspect.json [--max-bytes N]
java -jar koldera-pdf-engine.jar structure --input in.pdf --result structure.json [--max-bytes N]
java -jar koldera-pdf-engine.jar reorder   --request request.json --result result.json [--work-root DIR]
```

Exit codes: `0` a contract-valid result was written (also for `unsupported` and `failed` results),
`1` the result could not be persisted, `2` usage error. The result status and the exit code are
independent. stderr carries one JSON line per operational event and never document content, titles,
text or host paths.

### `process`

The request validates against `contracts/pdf-worker-request.schema.json`: `mode` (`analyze` or `fix`;
`safe_fix` and `structure_fix` are accepted and deprecated), optional `explicitTitle`,
`explicitLanguage` and `sourceFileName`, `origin` (`upload`, or `html_generated` for PDFs produced by
an HTML renderer) and `limits` (`maxBytes`, `maxPages`, `timeoutSeconds`). The input is
`/work/in/document.pdf` and output goes to `/work/out`; `--work-root` maps these canonical paths to a
real directory (`<root>/in`, `<root>/out`, private `<root>/scratch`), default `/work`. The result
(`contracts/pdf-worker-result.schema.json`) always reports canonical paths.

Mode `analyze` only validates. Mode `fix` applies, where they apply: a document title (from the
caller, the document's own metadata or the file name, labelled for review), language and metadata
repairs, structure tags for untagged input built from page content, repairs of existing tags (links,
tab order, lists, tables, figures, headings, role map), font repairs, and the PDF/UA identifier when no
machine-checkable failure is left. Tagged input is kept and repaired; tags judged unusable by
measured criteria are rebuilt. Alternative text is never invented; figures without it are marked as
artifacts.

| Result `status` | Meaning |
|---|---|
| `completed` | The run finished (findings may remain). See `remediationStatus`. |
| `unsupported` | Encrypted or password-protected, signed (for mutation), corrupt, empty or over the limits. `error.code`: `UNSUPPORTED_ENCRYPTED`, `UNSUPPORTED_SIGNED_MUTATION`, `INVALID_INPUT`, `LIMIT_EXCEEDED`. |
| `failed` | Invalid request, validator missing or erroring (`VALIDATOR_UNAVAILABLE`, `VALIDATOR_ERROR`), or an integrity regression (`INTEGRITY_REGRESSION`). |

`remediationStatus` is one of `not_requested`, `no_applicable_fix`, `applied`, `skipped`,
`rolled_back`. A `rolled_back` run publishes no remediated PDF; the validation reports are still
written. Artifacts (original, reports, remediated copy) are listed with SHA-256 digests.

### `inspect`, `structure`, `reorder`

`inspect` is a read-only probe of what a PDF declares and contains: marked content flag, structure
tree presence, element and content-reference counts, roles, `/Lang`, Info title
(`contracts/pdf-inspect-result.schema.json`).

`structure` is read-only too: it walks the structure tree in its stored order (never re-sorted by
geometry), resolves marked content to bounded text previews and bounding boxes, and records per-page
invariants (`contracts/pdf-structure-result.schema.json`).

`reorder` permutes the structure-element kids of one parent, as approved in the request, on a copy
(`contracts/pdf-reorder-{request,result}.schema.json`). The copy is published only if page count,
content streams, text multisets, annotations, marked-content inventory, parent tree and a pixel
render are unchanged and the reparsed sequence is the approved one. Untagged, signed and encrypted
files, mixed or annotated parents, cross-page siblings, table rows and cells, and incomplete or
identity permutations are refused (`ORDER_FIX_UNSUPPORTED`).

## Layout

```
cli/        Main (envelope, exit codes, logging)
contracts/  request and result types, JSON Schema validation
preflight/  limits, parse, encryption and signature detection, digests
validation/ veraPDF adapter (fail closed)
rules/      remediation rules and the PDF/UA identifier step
structure/  content tagging, structure extraction
inference/  structure strategy and layout inference for uploads
generated/  normalization of tags written by an HTML renderer
repair/     repairs of existing tags
fonts/      font repairs
integrity/  copy writer and integrity checks
pipeline/   orchestration, gates, rollback ladder
report/     result assembly and findings
```
