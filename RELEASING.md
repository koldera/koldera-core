# Releasing koldera-core

Core is licensed under AGPL-3.0-only. The release workflow builds a source and image bundle and attaches it to a
**draft** GitHub Release for verification. Publishing the draft makes the bundle publicly downloadable. No image is
pushed to GHCR or Docker Hub, and there is no `latest` tag.

Do not tag a release until the third-party image licence pack has been reviewed and merged, the full CI is green,
and the bundle has been tested on a clean host. See the open release issues and the [NOTICE](NOTICE).

## Bundle layout

`scripts/package-release.mjs vX.Y.Z` writes `dist/release/koldera-core-vX.Y.Z.tar.gz` plus its `.sha256`:

```
koldera-core-vX.Y.Z/
  source/                 git archive of the tag (no history; .gitattributes export-ignore paths left out)
  image/koldera-core-vX.Y.Z-linux-amd64.tar   docker save of the image
  image/image-digest.txt  image ID, tag and platform
  docs/                   INSTALL, DEPLOYMENT, CONFIGURATION, UPGRADE, SECURITY, API, openapi.yaml, CHANGELOG
  licenses/               LICENSE, NOTICE, and licence texts copied out of the image
  sbom.spdx.json          SPDX SBOM of the image archive (syft)
  release-manifest.json   version, tag, commit, build time, image ID, every file with SHA-256 and size
  SHA256SUMS              every file in the bundle; check with `sha256sum -c SHA256SUMS`
```

The script refuses a dirty tree (including untracked files, which the Docker build context would pick up), refuses
when `HEAD` is not the tag, and refuses an image whose `org.opencontainers.image.revision` label is not `HEAD`, so the
source and the image always come from the same commit. It also refuses a source tree with secret-like files
(`.env`, `*.pem`, `*.key`, private keys) and runs `gitleaks dir` on it when gitleaks is installed.

## Steps

1. On `main`, with CI green: move the `Unreleased` entries of `CHANGELOG.md` under `## X.Y.Z (YYYY-MM-DD)` (today's
   date), set the version in `contracts/openapi.yaml` (`info.version`), `docker/Dockerfile` (`ARG KOLDERA_VERSION`),
   `server/Directory.Build.props` (`<Version>`), `engine/pom.xml` and the renderer/scanner project files, and open a
   PR with that change. Merge it after review. `scripts/version-alignment.test.mjs` fails when the Dockerfile, server
   and engine versions differ; the image build also sets all three from the tag (`KOLDERA_VERSION`).
2. Tag the merge commit and push only that tag:

   ```bash
   git switch main && git pull --ff-only
   git tag -a vX.Y.Z -m "koldera-core vX.Y.Z"
   git push origin vX.Y.Z
   ```

3. The `Release` workflow runs CI on the tag, checks the tag is on `main`, builds the linux/amd64 image, runs
   `scripts/package-release.mjs`, verifies the checksums and creates a **draft** release with
   `koldera-core-vX.Y.Z.tar.gz`, its `.sha256`, and copies of `SHA256SUMS`, `release-manifest.json` and the SBOM.
4. Check the draft: download the tarball, run `sha256sum -c` on it and on `SHA256SUMS`, and follow
   `docs/release/INSTALL.md` on a clean host (`/v1/info` must report `X.Y.Z`). Check that the bundle contains the
   corresponding source and the required third-party notices. Then publish the draft.
5. Announce the public release with a link to the tag, release assets, checksums and any known limitations.

## Building a bundle locally

```bash
git switch --detach vX.Y.Z
node scripts/package-release.mjs vX.Y.Z                      # builds the image with the revision label
node scripts/package-release.mjs vX.Y.Z --image koldera-core:X.Y.Z   # reuse an image built from the same commit
node scripts/package-release.mjs vX.Y.Z --skip-image         # dry run: source, docs, licences, manifest, checksums
```

Needs git, tar, Node 22, Docker (linux/amd64 builds) and syft (or Docker to run the `anchore/syft` image). A
`--skip-image` bundle has `"deliverable": false` in its manifest and must not be sent to anyone.

## If something goes wrong

- A failed workflow run publishes nothing. Fix the problem on `main`, delete the tag (`git push origin :refs/tags/vX.Y.Z`
  and `git tag -d vX.Y.Z`) only if no bundle was delivered, and tag again; otherwise release the next patch version.
- A bad published bundle is never replaced under the same version: ship a new patch version and identify the old
  SHA-256 in the release notes.
