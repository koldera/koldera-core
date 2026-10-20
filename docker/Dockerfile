# syntax=docker/dockerfile:1.7
#
# koldera-core all-in-one image: ASP.NET server + PDF engine (Java) + veraPDF + renderer/scanner (Node,
# Playwright Chromium) + Typst. Build from the repository root:  docker build -f docker/Dockerfile .
# Multi-arch (amd64/arm64) is built in CI; TARGETARCH selects the Typst archive.

# Pinned upstream downloads (verified 2026-10-05):
#   veraPDF greenfield installer 1.30.2
#     https://software.verapdf.org/releases/1.30/verapdf-greenfield-1.30.2-installer.zip
#     sha256 6cc6341cb1af644044054b81f00a6590a7918abb18f762243de115258bcad838

#   Typst 0.15.1 (musl, static), https://github.com/typst/typst/releases/tag/v0.15.1
#     typst-x86_64-unknown-linux-musl.tar.xz   sha256 a6d077d0a95eed5a2eba715b2dae06be954f624ccbf85758a03f389ded33118c
#     typst-aarch64-unknown-linux-musl.tar.xz  sha256 5aa8d74a3d906e60ea12a66ac2f37f8eef1b14cbad7182a745e393a10c23dcee
#   Licenses: veraPDF GPLv3+/MPLv2+ (dual) and Typst Apache-2.0 run as separate processes; PDFBox Apache-2.0;
#   Noto fonts and Liberation fonts SIL OFL 1.1. koldera-core is AGPL-3.0-only (see LICENSE).

# The one version of the image (server, engine, label): the default for local builds; release.yml and
# scripts/package-release.mjs pass the release tag. It must match <Version> in server/Directory.Build.props and engine/pom.xml.
ARG KOLDERA_VERSION=0.1.0

# ---------- PDF engine jar ----------
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS engine
ARG KOLDERA_VERSION
WORKDIR /src
COPY engine/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY engine/src ./src
RUN mvn -q -B -DskipTests -Dkoldera.version="${KOLDERA_VERSION}" package

# ---------- renderer and scanner: build, then keep production dependencies only ----------
FROM node:22-bookworm@sha256:363e1587494626837fa7f9a23bdb453d13b0ff3c67c705c2805cfc69c2d2fad7 AS node
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN npm install -g pnpm@10 --no-fund --no-audit
# Each package has its own lockfile (no workspace), so each is installed, built and pruned on its own.
WORKDIR /src/renderer
COPY renderer/package.json renderer/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY renderer/tsconfig.json ./
COPY renderer/src ./src
RUN pnpm run build && pnpm prune --prod
WORKDIR /src/scanner
COPY scanner/package.json scanner/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY scanner/tsconfig.json ./
COPY scanner/src ./src
RUN pnpm run build && pnpm prune --prod
# Third-party licence text that the final image ships next to the licence of koldera-core.
RUN mkdir -p /licenses && cp -L node_modules/axe-core/LICENSE /licenses/axe-core-LICENSE

# ---------- ASP.NET server ----------
FROM mcr.microsoft.com/dotnet/sdk:10.0.402@sha256:0d99ebf1ccad15037b5c98c9fe8979d6b8dbac23812feae2277a0b277b5c5ffa AS server
ARG KOLDERA_VERSION
WORKDIR /src
COPY server/Directory.Build.props server/Directory.Packages.props ./server/
COPY server/src ./server/src
COPY contracts ./contracts
RUN dotnet publish server/src/Koldera.Core/Koldera.Core.csproj -c Release -o /out/server --no-self-contained -p:UseAppHost=false -p:Version="${KOLDERA_VERSION}"

# ---------- veraPDF (unattended IzPack install) ----------
FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87 AS verapdf
ARG VERAPDF_VERSION=1.30.2
ARG VERAPDF_URL=https://software.verapdf.org/releases/1.30/verapdf-greenfield-${VERAPDF_VERSION}-installer.zip
ARG VERAPDF_SHA256=6cc6341cb1af644044054b81f00a6590a7918abb18f762243de115258bcad838
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /tmp/verapdf
RUN curl -fsSL -o installer.zip "${VERAPDF_URL}" \
    && echo "${VERAPDF_SHA256}  installer.zip" | sha256sum -c - \
    && unzip -q installer.zip
COPY docker/verapdf-auto-install.xml auto-install.xml
RUN java -jar "verapdf-greenfield-${VERAPDF_VERSION}/verapdf-izpack-installer-${VERAPDF_VERSION}.jar" auto-install.xml \
    && /opt/verapdf/verapdf --version

# The image runs the command line only: drop the Swing GUI and the installer's uninstaller (two more jars to patch and
# to list in the notices for nothing).
RUN rm -rf "/opt/verapdf/bin/gui-${VERAPDF_VERSION}.jar" /opt/verapdf/verapdf-gui /opt/verapdf/Uninstaller

# veraPDF 1.30.2 is the latest release and its fat jar bundles Jackson 2.21.1 (eight HIGH CVEs, fixed in 2.21.7).
# veraPDF only uses Jackson to write its JSON report, but the fix is cheap: cut jackson-core and jackson-databind out of
# the fat jar and add the patched jars beside it (the launcher puts bin/* on the classpath). No veraPDF class is
# touched. Remove this block when a veraPDF release bundles Jackson >= 2.21.7 (docs/legal/THIRD-PARTY-NOTICES.md).
ARG JACKSON_VERSION=2.21.7
ARG JACKSON_CORE_SHA256=8c5623b98f32d5f7e1287ff61ed50e08ab7b11381e991fff89327faabba42261
ARG JACKSON_DATABIND_SHA256=1290c2795e93e8a6861a6c4d9ff0d844d32f5ea178362cb2721edf5561e828b1
RUN set -eu; \
    cd /opt/verapdf/bin; \
    for a in core databind; do \
      case "$a" in core) sha="${JACKSON_CORE_SHA256}" ;; databind) sha="${JACKSON_DATABIND_SHA256}" ;; esac; \
      curl -fsSL -o "jackson-$a-${JACKSON_VERSION}.jar" "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-$a/${JACKSON_VERSION}/jackson-$a-${JACKSON_VERSION}.jar"; \
      echo "$sha  jackson-$a-${JACKSON_VERSION}.jar" | sha256sum -c -; \
    done; \
    mkdir /tmp/cli && cd /tmp/cli && jar xf "/opt/verapdf/bin/cli-${VERAPDF_VERSION}.jar"; \
    rm -rf com/fasterxml/jackson/core com/fasterxml/jackson/databind \
           META-INF/maven/com.fasterxml.jackson.core/jackson-core META-INF/maven/com.fasterxml.jackson.core/jackson-databind \
           META-INF/services/com.fasterxml.jackson.core.JsonFactory META-INF/services/com.fasterxml.jackson.core.ObjectCodec; \
    rm -rf META-INF/versions/*/com/fasterxml/jackson/core META-INF/versions/*/com/fasterxml/jackson/databind; \
    mv META-INF/MANIFEST.MF /tmp/MANIFEST.MF; \
    jar --create --file "/opt/verapdf/bin/cli-${VERAPDF_VERSION}.jar" --manifest /tmp/MANIFEST.MF -C /tmp/cli .; \
    cd / && rm -rf /tmp/cli /tmp/MANIFEST.MF; \
    /opt/verapdf/verapdf --version

# What the fat jar and the added jars bundle, for docs/legal/THIRD-PARTY-NOTICES.md and the customer's own audit.
RUN mkdir -p /licenses && cd /opt/verapdf/bin && for j in *.jar; do \
      echo "== $j"; \
      unzip -Z1 "$j" 'META-INF/maven/*/*/pom.properties' | while read -r p; do unzip -p "$j" "$p" | tr -d '\r' | awk -F= '/^groupId=/{g=$2} /^artifactId=/{a=$2} /^version=/{v=$2} END{print "  " g ":" a ":" v}'; done | sort; \
    done > /licenses/verapdf-bundled-components.txt

# The installer ships no licence files; the dual licence texts come from the tagged upstream source, checksum-pinned.
RUN mkdir -p /licenses \
    && curl -fsSL -o /licenses/verapdf-LICENSE.GPL https://raw.githubusercontent.com/veraPDF/veraPDF-library/v${VERAPDF_VERSION}/LICENSE.GPL \
    && curl -fsSL -o /licenses/verapdf-LICENSE.MPL https://raw.githubusercontent.com/veraPDF/veraPDF-library/v${VERAPDF_VERSION}/LICENSE.MPL \
    && echo "d62f065830aa3739cc031156b9690805c7b2e811b4a178c8b4acd8725d561c94  /licenses/verapdf-LICENSE.GPL" | sha256sum -c - \
    && echo "af175b9d96ee93c21a036152e1b905b0b95304d4ae8c2c921c7609100ba8df7e  /licenses/verapdf-LICENSE.MPL" | sha256sum -c -

# ---------- Typst (static musl binary, checksum per architecture) ----------
FROM debian:bookworm-slim@sha256:3783cc01769c7b2b1b83a5c5ad96c815348e28ed7da68e2e3687004faa906251 AS typst
ARG TARGETARCH
ARG TYPST_VERSION=v0.15.1
ARG TYPST_SHA256_AMD64=a6d077d0a95eed5a2eba715b2dae06be954f624ccbf85758a03f389ded33118c
ARG TYPST_SHA256_ARM64=5aa8d74a3d906e60ea12a66ac2f37f8eef1b14cbad7182a745e393a10c23dcee
RUN apt-get update && apt-get install -y --no-install-recommends curl xz-utils ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /tmp/typst
RUN case "${TARGETARCH:-amd64}" in \
      amd64) arch=x86_64; sha="${TYPST_SHA256_AMD64}" ;; \
      arm64) arch=aarch64; sha="${TYPST_SHA256_ARM64}" ;; \
      *) echo "unsupported TARGETARCH ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL -o typst.tar.xz "https://github.com/typst/typst/releases/download/${TYPST_VERSION}/typst-${arch}-unknown-linux-musl.tar.xz" \
    && echo "${sha}  typst.tar.xz" | sha256sum -c - \
    && tar -xJf typst.tar.xz --strip-components=1 \
    && install -D -m 0755 typst /out/opt/typst/typst \
    && install -D -m 0644 LICENSE /out/licenses/typst-LICENSE \
    && /out/opt/typst/typst --version

# ---------- donor stages: runtimes copied instead of installed through apt ----------
FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c AS jre
FROM mcr.microsoft.com/dotnet/aspnet:10.0.13-noble@sha256:d5633778c754e89a386882eddf8dbee6942447a8a4b8267c19cf1e1158d0b0ac AS dotnet
FROM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS nodejs

# ---------- runtime ----------
# Plain Ubuntu plus Node and only the Chromium headless shell: the stock Playwright image ships Firefox and
# WebKit too, and deleting them in a later layer would not make the image smaller.
FROM ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55
ARG KOLDERA_VERSION
LABEL org.opencontainers.image.title="koldera-core" \
      org.opencontainers.image.description="Self-hosted PDF accessibility analysis and conservative remediation: HTTP server and CLI over PDFBox, veraPDF, Chromium and Typst" \
      org.opencontainers.image.source="https://github.com/koldera/koldera-core" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.documentation="https://github.com/koldera/koldera-core/blob/main/NOTICE" \
      org.opencontainers.image.version="${KOLDERA_VERSION}" \
      dev.koldera.third-party-licenses="/usr/share/licenses/koldera"

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=dotnet /usr/share/dotnet /usr/share/dotnet
COPY --from=nodejs /usr/local/bin/node /usr/local/bin/node
ENV JAVA_HOME=/opt/java/openjdk \
    DOTNET_ROOT=/usr/share/dotnet \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PATH=/opt/java/openjdk/bin:/usr/share/dotnet:$PATH

RUN groupadd --system --gid 10001 koldera \
    && useradd --system --uid 10001 --gid koldera --home-dir /home/koldera --create-home --shell /usr/sbin/nologin koldera \
    && mkdir -p /data && chown koldera:koldera /data \
    && apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=verapdf /opt/verapdf /opt/verapdf
COPY --from=typst /out/opt/typst /opt/typst
COPY --from=engine /src/target/koldera-pdf-engine.jar /app/engine/koldera-pdf-engine.jar
COPY --from=node /src/renderer/dist /app/renderer/dist
COPY --from=node /src/renderer/node_modules /app/renderer/node_modules
COPY --from=node /src/renderer/package.json /app/renderer/package.json
COPY renderer/fonts /app/renderer/fonts
COPY --from=node /src/scanner/dist /app/scanner/dist
COPY --from=node /src/scanner/node_modules /app/scanner/node_modules
COPY --from=node /src/scanner/package.json /app/scanner/package.json
COPY --from=server /out/server /app/server
COPY contracts /app/contracts
# Licence texts: the koldera-core licence notice and the third-party licences of what is bundled.
COPY LICENSE NOTICE /usr/share/licenses/koldera/
COPY renderer/fonts/OFL.txt /usr/share/licenses/koldera/fonts-noto-OFL.txt
COPY engine/src/main/resources/fonts/liberation/LICENSE /usr/share/licenses/koldera/fonts-liberation-LICENSE
COPY --from=node /licenses/ /usr/share/licenses/koldera/
COPY --from=verapdf /licenses/ /usr/share/licenses/koldera/
COPY --from=typst /out/licenses/ /usr/share/licenses/koldera/
# `koldera pdf|html|scan|document ...` runs the command-line tool from inside the container (the ENTRYPOINT does the same).
RUN printf '%s\n' '#!/bin/sh' 'exec dotnet /app/server/Koldera.Core.dll "$@"' > /usr/local/bin/koldera && chmod 0755 /usr/local/bin/koldera

# Residual risk: Playwright starts Chromium with --no-sandbox (Docker's default seccomp profile blocks the user
# namespaces its sandbox needs). The compensating controls are in docs/deployment.md, "Browser sandbox".
# The browser build must match the pinned playwright package, so the package's own CLI installs it. The
# headless shell is what headless:true launches; --with-deps adds the system libraries it needs.
RUN node /app/renderer/node_modules/playwright/cli.js install --with-deps --only-shell chromium \
    && rm -rf /var/lib/apt/lists/* /root/.cache /tmp/*

# The Node workers resolve ../contracts and ../fonts relative to their package; the variables make that explicit.
ENV ASPNETCORE_URLS=http://+:8080 \
    KOLDERA_DATA_DIR=/data \
    KOLDERA_CONTRACTS_DIR=/app/contracts \
    KOLDERA_FONTS_DIR=/app/renderer/fonts \
    VERAPDF_CMD=/opt/verapdf/verapdf \
    KOLDERA_TYPST_CMD=/opt/typst/typst \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -Djava.awt.headless=true"

# HOME is a tmpfs under the hardened compose setup (read-only root), so nothing a worker writes lands in /home/koldera.
ENV HOME=/tmp
USER koldera:koldera
WORKDIR /data
VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD curl -fsS http://localhost:8080/healthz || exit 1

# No arguments or `serve` start the HTTP server; `pdf`, `html`, `scan`, `document` run the CLI.
ENTRYPOINT ["dotnet", "/app/server/Koldera.Core.dll"]
CMD ["serve"]
