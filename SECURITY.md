# Security policy

## Supported versions

koldera-core is an early release. Only the latest 0.x release receives security fixes. Once 1.0 is out, this page will
name the supported lines.

## Reporting a vulnerability

Please do not open a public issue.

- Write to security@koldera-accessibility.dev.
- GitHub's private vulnerability reporting (Security tab, "Report a vulnerability") can be used as well once it is
  enabled for the repository.

Include the version or image tag, what you did, what you expected and what happened. A proof of concept helps. Do not
send real customer documents; a synthetic file that reproduces the problem is enough.

We aim to confirm receipt within a few working days and will tell you what we decide. We credit reporters who want
credit once a fix is released.

## Scope

In scope:

- the HTTP server and command-line tool: authentication, client sessions, signed URLs, idempotency, separation
  between sessions, input validation;
- the PDF engine, the HTML and document renderer and the web scanner, including the SSRF protections of the scanner and
  the isolation of the renderer;
- the container image, the compose files and the egress proxy as shipped in `docker/`.

Out of scope:

- vulnerabilities in third-party components themselves (report them upstream); we want to hear about a vulnerable
  version we ship, though;
- findings that need an operator to disable a documented protection (for example `KOLDERA_AUTH=none` on a public
  address);
- denial of service by sending very large inputs to an instance that has no limits set (see
  [docs/configuration.md](docs/configuration.md));
- the known residual risk that Chromium runs without its own sandbox in the supplied container, described in
  [docs/deployment.md](docs/deployment.md#browser-sandbox). Reports of a way to exploit it are still welcome.
