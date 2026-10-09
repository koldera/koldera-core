# Changelog

All notable changes to koldera-core. Versions follow [Semantic Versioning](https://semver.org/); a release is
dated on the day its tag is created.

## Unreleased

- Licence pack in the image: `docs/legal/THIRD-PARTY-NOTICES.md` (components, versions, licences, licence elections,
  source locations, written offer, open points for the lawyer) and the licence texts that were missing: Node.js,
  Chromium's credits file, Typst's NOTICE and the typst-assets NOTICE (embedded fonts), and Apache-2.0, Bouncy Castle
  and SLF4J for the shaded engine jar. The unused ffmpeg build Playwright installs (LGPL) is removed. The Node version
  is checked at build time so the Node licence text cannot go stale unnoticed.

- Commercial release bundle: `scripts/package-release.mjs` builds the source archive, image archive, SBOM, licence
  texts, release manifest and `SHA256SUMS` for one tag; the release workflow attaches the bundle to the private
  GitHub Release and no longer pushes images to a registry.

- Structured documents: a table with `rowHeaders: true` is refused (`SCHEMA` at `.../rowHeaders`) because Typst
  0.15.1 tags row headers as TD, not TH; the renderer also refuses any Typst other than the pinned 0.15.x.

## 0.1.0 (not released yet)

- First version. Release notes will be written when the `v0.1.0` tag is created.
