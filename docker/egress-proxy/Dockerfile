# Squid with the SSRF-oriented policy baked in, so compose needs no bind mount into a read-only container.
FROM ubuntu/squid:6.6-24.04_edge
COPY squid.conf /etc/squid/squid.conf
EXPOSE 3128
