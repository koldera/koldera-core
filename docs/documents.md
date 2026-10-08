# Structured documents

A structured document is JSON that declares semantic blocks (headings, paragraphs, lists, tables, images, links,
key-value pairs). koldera-core renders it to a tagged PDF with Typst and checks the result against the PDF/UA-1
machine rules with veraPDF. Structure is **declared by the author, never inferred**: headings, table headers, image
descriptions and languages are what you wrote.

The formal schema is [`contracts/document.schema.json`](../contracts/document.schema.json). A validator adds the checks
a schema cannot express. Submit with `POST /v1/documents/jobs` (the body is the document) or
`koldera document file.json`.

## Complete example

```json
{
  "title": "Invoice 2026-0042",
  "language": "en",
  "page": { "size": "A4", "orientation": "portrait", "margins": "20mm" },
  "theme": { "font": "Noto Sans", "fontSize": 11, "accent": "#1f3a5f" },
  "header": [
    { "type": "paragraph", "content": [{ "strong": "Example Co." }, " - 1 Sample Street, Exampleville"] }
  ],
  "footer": [
    { "type": "paragraph", "content": ["Questions: ", { "link": { "text": "billing@example.com", "url": "mailto:billing@example.com" } }] },
    { "type": "pageNumber" }
  ],
  "body": [
    { "type": "heading", "level": 1, "text": "Invoice 2026-0042" },
    { "type": "keyValue", "rows": [["Issued", "2026-10-01"], ["Due", "2026-10-31"], ["Customer", "Sample Customer Ltd."]] },
    { "type": "heading", "level": 2, "text": "Line items" },
    {
      "type": "table",
      "caption": "Items billed on invoice 2026-0042",
      "header": [["Description", "Quantity", "Unit price", "Amount"]],
      "rows": [
        ["Consulting, hours", "10", "100.00", "1000.00"],
        ["Hosting, months", "3", "25.00", "75.00"],
        ["Total", "", "", "1075.00"]
      ],
      "columnAlign": ["left", "right", "right", "right"]
    },
    { "type": "list", "ordered": false, "items": [["Payment within 30 days."], ["Bank details are in your ", { "emphasis": "customer portal" }, "."]] },
    { "type": "image", "uploadId": "3f1c8d5e-0f0e-4f0e-9d2c-5c1d6f9b7a10", "alt": "Company logo: a blue square with the letters EC" },
    { "type": "link", "text": "Payment terms", "url": "https://example.com/terms" },
    { "type": "pageBreak" },
    { "type": "paragraph", "lang": "ar", "font": "Noto Sans Arabic", "content": ["شكرا لكم"] }
  ]
}
```

The `uploadId` must come from an image upload you completed first (`image/png`, `image/jpeg` or `image/svg+xml`).

## Blocks

| `type` | Required | Notes |
| --- | --- | --- |
| `heading` | `level` (1-6), `text` | The first heading in the body is level 1 or 2; each later one is at most one level deeper than the previous. Headings in `header` and `footer` do not count. |
| `paragraph` | `content` | Inline pieces: a string, `{"strong": ...}`, `{"emphasis": ...}` or `{"link": {"text", "url"}}`. Optional `lang` and `font` for a passage in another language. |
| `list` | `items` | `ordered` for numbers. An item is an array of inline pieces, optionally ending with `{"list": {...}}` for a nested list. |
| `table` | `caption`, `header`, `rows` | At least one header row. `columnAlign` per column. `rowHeaders: true` is refused for now (see below); omit it or set `false`. |
| `image` | one of `uploadId` or `file`, and `alt` or `decorative: true` | `file` is for the command-line tool only (path relative to the JSON file). Over HTTP use `uploadId`. |
| `link` | `text`, `url` | A paragraph consisting of one link. URLs are `http`, `https` or `mailto`. |
| `keyValue` | `rows` | Pairs of `[label, value]`. |
| `spacer` | `height` | Vertical space, for example `8mm`. Not announced by assistive technology. |
| `pageBreak` | none | |
| `pageNumber` | none | Footer only; filled in on every page. |

Page sizes: `A4`, `A5`, `Letter`, `Legal`. Lengths use `mm`, `cm`, `in` or `pt`. Bundled fonts: `Noto Sans`,
`Noto Serif`, `Noto Sans Arabic`, `Noto Sans Hebrew`.

## Validation codes

A failing document answers `422 VALIDATION_ERROR`; `errors[]` has one entry per problem with `path` (JSON Pointer),
`code` and `message`. The command-line tool reports the same entries.

| Code | Meaning |
| --- | --- |
| `SCHEMA` | The document does not match the schema (missing or unknown property, wrong type, bad value, an image with both or neither source, an image with both `alt` and `decorative`), or a table sets `rowHeaders: true` (path `.../rowHeaders`). |
| `HEADING_LEVEL_SKIPPED` | A heading is deeper than one level below the previous one, or the first is deeper than level 2. |
| `TABLE_HEADER_EMPTY` | A table has no header row, or a header row has only blank cells. |
| `TABLE_ROW_LENGTH_MISMATCH` | A header or body row has a different number of cells from the first header row, or `columnAlign` has a different number of entries from the column count. |
| `IMAGE_ALT_MISSING` | An image has neither a non-blank `alt` nor `decorative: true`. |
| `LINK_TEXT_EMPTY` | A link's text is blank. |
| `LANGUAGE_INVALID` | `language` or a `lang` is not a well-formed BCP 47 tag. |
| `FONT_UNKNOWN` | `theme.font` or a paragraph `font` is not a bundled font. |
| `FILE_IMAGE_NOT_ALLOWED` | An image uses `file` over HTTP. Upload it and use `uploadId`. |
| `IMAGE_UPLOAD_INVALID` | The `uploadId` is unknown, expired, not completed, not an image, or the bytes are not the declared image type. |

## What the Typst path does and does not do

- The PDF is checked after rendering. If veraPDF reports a failed rule, the job fails with
  `DOCUMENT_VALIDATION_FAILED` and the findings list the rules; if the validator cannot run, with
  `DOCUMENT_VALIDATION_UNAVAILABLE`. A passing machine check is not a statement that the document is accessible:
  `manualReviewRequired` stays `true`.
- Typst 0.15.1 cannot tag a body cell as a header cell: it would tag a row header as an ordinary data cell (TD), and
  veraPDF does not detect that. So **`rowHeaders: true` is refused** with `SCHEMA` at `/body/<n>/rowHeaders` (the
  renderer refuses it too, as `DOCUMENT_REFUSED`). The property stays in the schema and will be accepted once the pinned
  Typst tags those cells as TH. Column headers are real header cells (TH); a test checks this against real Typst output.
- The renderer runs only the pinned Typst 0.15.x: any other `typst --version` fails the job with `WORKER_ERROR`.
- **Links in the header and footer are rendered as plain text**, not as links.
- No alternative text is generated and no structure is guessed: what you do not declare is not added.
- Typst runs as a separate process with only the bundled Noto fonts, the job directory as its root, a fixed creation
  timestamp and a minimal environment. It cannot read other files or fetch anything; `file` images are refused over HTTP.
- Only the four bundled font families are available. A script they do not cover is not supported.
