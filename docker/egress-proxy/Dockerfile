# Squid with the SSRF-oriented policy baked in, so compose needs no bind mount into a read-only container.
# Ubuntu 24.04 LTS Squid 6.6 (the project publishes no "stable" tag); pinned by digest, bumped by Dependabot.
FROM ubuntu/squid:6.6-24.04_edge@sha256:8a3baed477e2c282ab8aa5edad442f69873246964f225c5c2ae8364b6610963c
COPY squid.conf /etc/squid/squid.conf
EXPOSE 3128
