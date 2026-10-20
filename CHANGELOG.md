# Changelog

All notable changes to koldera-core. Versions follow [Semantic Versioning](https://semver.org/); a release is
dated on the day its tag is created.

## Unreleased

- Security: `docker/compose.sandbox.yaml` and `docker/seccomp-chromium.json` run Chromium with its own sandbox inside
  the hardened container (Docker's default seccomp profile plus `clone`, `unshare`, `setns`, `chroot`). Recommended
  when the renderer or the web scanner handle untrusted input; it fails closed on hosts without user namespaces.

- Release bundle: `scripts/package-release.mjs` builds the source archive, image archive, SBOM, licence
  texts, release manifest and `SHA256SUMS` for one tag; the release workflow attaches the bundle to the private
  GitHub Release and no longer pushes images to a registry.

- Structured documents: a table with `rowHeaders: true` is refused (`SCHEMA` at `.../rowHeaders`) because Typst
  0.15.1 tags row headers as TD, not TH; the renderer also refuses any Typst other than the pinned 0.15.x.

## 0.1.0 (not released yet)

- First version. Release notes will be written when the `v0.1.0` tag is created.
