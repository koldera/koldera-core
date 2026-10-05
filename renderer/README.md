# HTML renderer

Renders one self-contained static HTML document to a tagged PDF with Playwright and Chromium: scripts
off, no network. `page.pdf({ tagged: true })` only requests tags; whether the output carries usable
structure is decided afterwards by the PDF engine and veraPDF, never here. The result claims nothing
beyond `taggedRequested`, and `manualReviewRequired` is always `true`.

Requires Node 22 and the Chromium build that matches the pinned Playwright version.

```
pnpm install --frozen-lockfile && pnpm build && pnpm test
```

## CLI

```
node dist/cli.js render   --request request.json --result result.json [--work-root DIR]
node dist/cli.js semantic --request request.json --result result.json [--work-root DIR]
node dist/cli.js reorder  --request request.json --result result.json [--work-root DIR]
```

Contracts (validated on input and output), in `contracts/` at the repository root:

| Verb | Request | Result |
|------|---------|--------|
| `render` | `html-renderer-request.schema.json` | `html-renderer-result.schema.json` |
| `semantic` | `html-semantic-request.schema.json` | `html-semantic-result.schema.json` |
| `reorder` | `html-reorder-request.schema.json` | `html-reorder-result.schema.json` |

The input is `<work-root>/in/source.html`; `render` writes `<work-root>/out/generated.pdf` and reports its
sha256 and size. Exit codes: 0 result written (including `unsupported` and `failed`), 1 result could not
be persisted, 2 usage error. stderr carries JSON events without document content or host paths.

### render

Status mapping: content refused by preflight is `unsupported` / `UNSUPPORTED_CONTENT` with blocking
diagnostics and line numbers; a request the page still attempted is `unsupported` /
`EXTERNAL_RESOURCE_BLOCKED`; exceeding `maxBytes` or `maxPages` is `failed` / `LIMIT_EXCEEDED`; the time
budget is `failed` / `TIMEOUT`; anything else is `failed` / `RENDER_FAILED`. Advisory diagnostics
(`HTML_LANG_MISSING`, `HTML_TITLE_MISSING`, `HTML_IMAGE_ALT_MISSING`) accompany a completed result.

Before printing, the rendered copy (never the source file) is prepared:

- links that resolve to the render host, or to a missing fragment, lose their `href` and keep their text
  (`HTML_RELATIVE_LINK_REMOVED`);
- images that did not load become their alt text in an inline `<span>`, or are removed when they have none
  (`HTML_IMAGE_NOT_LOADED`);
- an `<html>` without a non-blank `lang` takes the single tag of `<meta http-equiv="content-language">`, else
  `lang=""`, so Chromium writes no `/Lang` instead of inventing `en-US`;
- an inline `<svg>` named by `aria-label`, `aria-labelledby` or a direct `<title>` child gets `role="img"`; one
  with no name and no text of its own is marked `aria-hidden="true"` (`HTML_SVG_NAME_MISSING`); an `<svg>`
  already hidden or carrying another `role` is left as authored;
- an `<img>` without `alt` (or with blank `alt`) is printed as decorative and reported;
- empty table cells keep a place in the tag tree and `rowspan` values are clamped to the row group, without
  moving the layout.

`content.selector` keeps only the outermost elements matching a CSS selector as the children of `<body>`
(`HTML_CONTENT_SELECTED`). No match is `failed` / `CONTENT_NOT_FOUND`, an invalid selector `failed` /
`INVALID_INPUT`; nothing is written in either case.

### semantic

Reports the DOM in document order with opaque node ids, bounded previews, rendered boxes, focusability and
the CSS that makes orders disagree (`order`, reversed flex direction, `tabindex`), the keyboard focus
sequence measured with real Tab presses, and a deliberately naive geometry candidate (reported only as a
candidate). Findings: `CSS_ORDER_REORDERS_SIBLINGS`, `FLEX_DIRECTION_REVERSES_SIBLINGS`,
`POSITIVE_TABINDEX`, `FOCUS_ORDER_DIFFERS_FROM_DOM`, `VISUAL_ORDER_DIFFERS_FROM_DOM` and
`OFFSCREEN_TEXT_NOT_IN_PDF`, all for manual review.

### reorder

The only source change it makes: permute the element children of one parent in a copy, remove those
siblings' own inline `order` declarations when they merely encoded the approved visual order, re-render, and
check on the corrected copy that the DOM sequence is the approved one, focus follows it, and the screenshot
differs from the original by at most `maxVisualDiffRatio`. Refused: identity or incomplete permutations,
table structure, positive `tabindex` in the group, `order` from a stylesheet, an approved order that differs
from the CSS visual order. Rolled back (`ORDER_FIX_REGRESSION`, no artifact) when the visual result changes.
The original is never modified.

## Isolation

Content boundary (`src/preflight`, allow-lists):

- Elements: document, sectioning, heading, text, list, table, figure, `img`, `a`, `details`, `label`, `style`,
  `meta`, `title`. No `script`, `noscript`, `iframe`, `object`, `embed`, `link`, `base`, form controls,
  media, `svg`, `math` or `template`.
- Attributes: a small global set plus per-element lists; any `on*` handler is refused.
- Images: inline `data:image/{png,jpeg,gif,webp,bmp};base64` only, with per-image and total byte caps.
- Links: `http`, `https`, `mailto`, `tel`, fragments and relative references.
- CSS: no `@import`, no `@font-face`; `url()`, `src()` and `image-set()` must be inline data images; no
  `expression()`, `behavior:` or `-moz-binding`.
- Invalid UTF-8, NUL bytes and non-documents are refused. Preflight does not rewrite a refused document.

Browser layers (`src/render`): JavaScript disabled, service workers blocked, downloads refused, the
document served from a routed `https://render.koldera.invalid/` URL with
`Content-Security-Policy: default-src 'none'; img-src data:; style-src 'unsafe-inline'`, every other
request aborted and counted (CSP refusals are counted from the console), Chromium started with
`--host-resolver-rules=MAP * ~NOTFOUND` and `--disable-remote-fonts`. Run it in a container or sandbox
without network egress as well; the in-process layers are not a substitute.

## What it never does

It never fetches a URL, runs author scripts, loads remote assets, guesses alternative text or reading order,
or claims that the PDF is accessible or conformant. It never modifies the source file.

## Document verb

`node dist/cli.js document --request R --result S --work-root DIR` renders a structured document
(`contracts/document.schema.json`) with Typst as a tagged PDF/UA-1 file. Request and result:
`contracts/document-render-{request,result}.schema.json`. Typst runs with only the bundled Noto fonts
in `fonts/` (see `fonts/fonts.json`), the work root as its project root, a fixed creation timestamp
and an environment holding only PATH. Row-header cells are set in bold; Typst 0.15 cannot tag a
body cell as a header cell. Tests that need Typst run when `TYPST_CMD` is set.
