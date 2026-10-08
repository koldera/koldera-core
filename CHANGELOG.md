# Changelog

All notable changes to koldera-core. Versions follow [Semantic Versioning](https://semver.org/); a release is
dated on the day its tag is created.

## Unreleased

- Security: the image no longer ships the eight HIGH Jackson CVEs bundled in veraPDF 1.30.2 (jackson-core and
  jackson-databind 2.21.1). The two Jackson libraries are cut out of veraPDF's fat jar and replaced by 2.21.7 jars
  (checksum-pinned) beside it; no veraPDF class changes. The veraPDF Swing GUI and installer uninstaller are removed
  from the image. `.trivyignore.yaml` is empty again.

- Commercial release bundle: `scripts/package-release.mjs` builds the source archive, image archive, SBOM, licence
  texts, release manifest and `SHA256SUMS` for one tag; the release workflow attaches the bundle to the private
  GitHub Release and no longer pushes images to a registry.

- Structured documents: a table with `rowHeaders: true` is refused (`SCHEMA` at `.../rowHeaders`) because Typst
  0.15.1 tags row headers as TD, not TH; the renderer also refuses any Typst other than the pinned 0.15.x.

## 0.1.0 (not released yet)

- First version. Release notes will be written when the `v0.1.0` tag is created.
