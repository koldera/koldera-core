# Egress proxy (Squid)

This directory builds a small container image around Squid with the koldera-core SSRF policy (`squid.conf`). It is a
separate image from koldera-core and is not part of it.

## Licence and source (written offer)

Squid is licensed under GPL-2.0-or-later. The image uses the unmodified Ubuntu 24.04 Squid package from the
`ubuntu/squid` base image, pinned by digest in the [Dockerfile](Dockerfile). At the time of writing the pinned
image contains Squid `6.14-0ubuntu0.24.04.2`, although the image tag says 6.6. To check the version in the image you
run, use `docker run --rm --entrypoint dpkg-query <image> -W squid`.

The corresponding source of that package, including Ubuntu's patches and the licence texts, is available for as long
as the image is distributed:

- Launchpad: https://launchpad.net/ubuntu/+source/squid/6.14-0ubuntu0.24.04.2
- On an Ubuntu 24.04 system: `apt-get source squid=6.14-0ubuntu0.24.04.2`
- Upstream: https://github.com/squid-cache/squid

If you cannot get the source from these places, write to licensing@koldera-accessibility.dev and name the image
digest; the Koldera team will send you the source. The files in this directory (`squid.conf`, the error template and the
Dockerfile) are part of koldera-core and are proprietary (see [LICENSE](../../LICENSE)); Squid itself stays
under GPL-2.0-or-later.
