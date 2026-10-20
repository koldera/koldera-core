# Installing a koldera-core release

A release reaches you as `koldera-core-vX.Y.Z.tar.gz` with `koldera-core-vX.Y.Z.tar.gz.sha256`, delivered privately.
You need Docker Engine 24+ with Docker Compose 2.x on a linux/amd64 host.

## 1. Check and unpack

```bash
sha256sum -c koldera-core-vX.Y.Z.tar.gz.sha256
tar -xzf koldera-core-vX.Y.Z.tar.gz
cd koldera-core-vX.Y.Z
sha256sum -c SHA256SUMS          # every file in the bundle; any FAILED line means do not install
```

`release-manifest.json` names the version, the git commit both the source and the image were built from, and the
image ID. `image/image-digest.txt` holds the same ID.

## 2. Load the image

```bash
docker load -i image/koldera-core-vX.Y.Z-linux-amd64.tar
docker image inspect koldera-core:X.Y.Z --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
```

The ID must match `image/image-digest.txt` and the revision must match `commit` in `release-manifest.json`.

## 3. Run it

The hardened single-host setup is `source/docker/compose.yaml`; [DEPLOYMENT.md](DEPLOYMENT.md) explains it and
[CONFIGURATION.md](CONFIGURATION.md) lists every setting.

```bash
export KOLDERA_IMAGE=koldera-core:X.Y.Z
export KOLDERA_TOKEN="$(openssl rand -base64 32)"   # keep it in your secret store
docker compose -f source/docker/compose.yaml up -d --build --wait
curl -H "Authorization: Bearer $KOLDERA_TOKEN" http://localhost:8080/v1/info
```

`--build` builds the egress proxy from `source/docker/egress-proxy`, which pulls its pinned base image from Docker Hub
once. The API is described in [API.md](API.md) and `openapi.yaml`.

## What else is in the bundle

- `source/`: the source of this version, without git history. You can rebuild the image from it with
  `docker build -f docker/Dockerfile .`.
- `sbom.spdx.json`: SPDX software bill of materials of the image archive.
- `licenses/`: the koldera-core licence and notice, and the licence texts of third-party components in the image.
- `docs/`: this guide, deployment, configuration, upgrade, security and API documentation, and the changelog.
