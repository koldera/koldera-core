# Squid with the SSRF-oriented policy baked in, so compose needs no bind mount into a read-only container.
# Ubuntu 24.04 LTS Squid image (the project publishes no "stable" tag); pinned by digest, bumped by Dependabot.
# The tag says 6.6, but the pinned image contains the Ubuntu package squid 6.14-0ubuntu0.24.04.2 (see README.md).
FROM ubuntu/squid:6.6-24.04_edge@sha256:8a3baed477e2c282ab8aa5edad442f69873246964f225c5c2ae8364b6610963c
COPY squid.conf /etc/squid/squid.conf
COPY ERR_KOLDERA_REFUSED /usr/share/squid/errors/templates/ERR_KOLDERA_REFUSED
EXPOSE 3128
# Squid is GPL-2.0-or-later and ships unmodified from the Ubuntu package. Written offer for its source: see README.md.
LABEL org.opencontainers.image.title="koldera-core egress proxy (Squid)"       org.opencontainers.image.source="https://github.com/koldera/koldera-core"       org.opencontainers.image.licenses="GPL-2.0-or-later AND AGPL-3.0-only"       dev.koldera.source-offer="Squid source: Ubuntu source package squid 6.14-0ubuntu0.24.04.2, https://launchpad.net/ubuntu/+source/squid/6.14-0ubuntu0.24.04.2 (apt-get source squid=6.14-0ubuntu0.24.04.2); configuration: docker/egress-proxy in https://github.com/koldera/koldera-core"
