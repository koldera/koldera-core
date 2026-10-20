# Deployment

The image is built from `docker/Dockerfile`; `docker/compose.yaml` is the hardened single-host setup.

```bash
export KOLDERA_IMAGE=koldera-core:0.1.0   # the default; loaded from the release bundle (docker load) or built from source
export KOLDERA_TOKEN="$(openssl rand -base64 32)"
export KOLDERA_PUBLIC_URL=http://localhost:8080   # required: the address clients use (https://... behind TLS)
docker compose -f docker/compose.yaml up -d
curl -H "Authorization: Bearer $KOLDERA_TOKEN" http://localhost:8080/v1/info
```

Only `front` (Caddy) publishes a port (8080). `core` is on an internal network with no route out; its only way to
the internet is the `egress` Squid proxy, which refuses private, loopback, link-local and metadata addresses.
For TLS see `docker/Caddyfile`; for single sign-on `docker/compose.sso.yaml` (needs Docker Compose 2.x; every OIDC
variable is required and has no default). Base images are pinned by digest in the Dockerfile and compose files.

### Egress proxy limits

Independently of the server's own limits (see [Production profile](#production-profile)), the supplied egress proxy has one: `docker/egress-proxy/squid.conf`
sets `reply_body_max_size 5 MB`, so any single response a scanned page loads (the page, a script, an image) that is
larger than 5 MB is cut off by Squid, and a scan whose page is larger fails with `NAVIGATION_FAILED`. The proxy also
allows only ports 80 and 443 and keeps no cache. To change the cap, edit that line (for example `reply_body_max_size
20 MB`, or delete it for no cap) and rebuild the proxy with `docker compose -f docker/compose.yaml up -d --build egress`.
The scanner's own budgets (`KOLDERA_WEB_MAX_RESPONSE_BYTES` and friends in [configuration.md](configuration.md))
apply on top of it.

## Production profile

`docker/compose.yaml` is the recommended production profile. It requires `KOLDERA_TOKEN` and `KOLDERA_PUBLIC_URL`
(Compose refuses to start without them), sets every `KOLDERA_*` limit explicitly and caps each container:

| Setting | Value in compose.yaml | Server default without it |
| --- | --- | --- |
| `KOLDERA_CONCURRENCY` / `KOLDERA_QUEUE_CAPACITY` | `2` / `50` | half the CPUs / `100` |
| `KOLDERA_JOB_TIMEOUT_SECONDS` | `900` | `900` |
| `KOLDERA_PDF_MAX_BYTES` / `KOLDERA_PDF_MAX_PAGES` | 100 MiB / `500` | no limit |
| `KOLDERA_HTML_MAX_BYTES` | 10 MiB | no limit |
| `KOLDERA_IMAGE_MAX_BYTES` / `KOLDERA_DOCUMENT_MAX_BYTES` | 20 MiB / 10 MiB | 20 MiB / 10 MiB |
| `KOLDERA_WEB_MAX_RESPONSE_BYTES` / `KOLDERA_WEB_MAX_REQUESTS` | 50 MiB / `300` | no limit |
| `cpus` / `mem_limit` | core 2 / 4g, front 0.5 / 256m, egress 0.5 / 256m | none |

Each limit can be changed from the environment without editing the file (for example
`KOLDERA_PDF_MAX_PAGES=1000 docker compose -f docker/compose.yaml up -d`). Raise `KOLDERA_CONCURRENCY` only together
with `cpus` and `mem_limit`: a running job can hold a JVM and a Chromium at the same time.

**Never use these settings in production:**

- `KOLDERA_AUTH=none` on anything but a loopback address or behind a proxy that authenticates every request (and never
  with the single sign-on example, see below).
- `Workers__AllowLoopbackForTests=true`: lets the scanner reach loopback addresses. The server refuses to start with it
  unless `ASPNETCORE_ENVIRONMENT=Development`.
- `ASPNETCORE_ENVIRONMENT=Development`: development error pages and test-only switches.
- `KOLDERA_CORS_ORIGINS=*` does nothing (it allows no origin); list exact origins instead.
- Removing `KOLDERA_WEB_PROXY` or putting `core` on a network with a route out: the egress proxy is what refuses
  private, loopback and metadata addresses for page scans.
- `KOLDERA_WEB_ALLOW_HTTP=true` unless you need plain-HTTP targets.
- Publishing `core`'s port 8080 directly instead of going through `front`, or dropping `read_only`, `cap_drop`,
  `no-new-privileges`, `cpus` or `mem_limit` from the compose file.
- Very large or effectively unlimited values for the limits above on an instance that accepts files from people you
  do not trust.

## Who calls the API

koldera-core is built to be called by your backend. Pick one of three patterns and do not publish the server to
the open internet without it.

1. **Backend only (recommended).** Your server holds `KOLDERA_TOKEN` and calls the API; the server is on a private
   network or behind your own gateway. Browsers never see the token.
2. **Browser through client sessions.** Your backend mints a short-lived token with `POST /v1/client-sessions` for one
   origin, with scopes, a job and upload budget and a lifetime of at most 60 minutes, and hands only that token to
   the browser. The token is useless from another origin. Origin and CORS checks are secondary controls, not secrets:
   the session token is the credential. Budgets are counted in memory and reset on restart.
3. **Single sign-on in front (example).** `docker/compose.sso.yaml` with `docker/Caddyfile.sso` puts oauth2-proxy
   (OIDC) in front of browser traffic. What it does, exactly:
   - A request without core's bearer token and without a sign-in cookie is redirected to `/oauth2/start` and then to
     your identity provider.
   - Requests that carry `Authorization: Bearer …` (`KOLDERA_TOKEN` from your backend, or a client-session token
     your backend minted for a browser) and the signed `…/uploads/{id}/content` and `…/artifacts/{id}/content` links
     skip the sign-in and go straight to core, which checks the token or the signature itself.
   - Signing in does not replace core's credentials: a signed-in browser without a token still gets `401` on
     `/v1`. Use the sign-in to gate a page or tool of your own that you place behind the same Caddy, and give that
     page a client-session token (pattern 2) for its API calls.
   - **Never combine with `KOLDERA_AUTH=none`: bearer and signed requests bypass sign-in, so core's own `KOLDERA_TOKEN` must stay set.**
   - The session cookie is `Secure`, so serve the front over TLS, and set `KOLDERA_PUBLIC_URL` to that address.

   It was checked against a local mock OIDC issuer (redirect to sign-in, a completed sign-in, bearer calls, signed
   upload and download links, a wrong token and a broken signature refused by core), not against a production
   identity provider; test it with yours before you rely on it.

Whatever you choose: terminate TLS in front of the server and set `KOLDERA_PUBLIC_URL`, set the limits in
[configuration.md](configuration.md), and keep `KOLDERA_AUTH=none` for a loopback address only, or for a proxy that
authenticates every request without exceptions. **Never combine the single sign-on example (pattern 3) with KOLDERA_AUTH=none: bearer and signed requests bypass sign-in; core's own KOLDERA_TOKEN must stay set.** With `KOLDERA_AUTH=none` the server answers only the host names in `KOLDERA_ALLOWED_HOSTS`
(loopback names by default), so a proxy in front must forward one of them or you list its name there. The server is one instance with a local data directory; do not put several behind one
load balancer. Serverless: see [aws-lambda.md](aws-lambda.md).

## Browser sandbox

The renderer and the web scanner drive Chromium through Playwright, which starts it with `--no-sandbox`
(`chromiumSandbox: false` is Playwright's default). Chromium's own sandbox needs unprivileged user namespaces,
which Docker's default seccomp profile blocks, so the process runs without it. A browser exploit would therefore
not be stopped by Chromium's sandbox. This is a known residual risk, not something the image hides.

**If the renderer or the web scanner handle input you do not control, run with the sandbox.** Add the override file
and the seccomp profile that ship next to `compose.yaml`:

```bash
KOLDERA_TOKEN=... KOLDERA_PUBLIC_URL=... \
  docker compose -f docker/compose.yaml -f docker/compose.sandbox.yaml up -d
```

`compose.sandbox.yaml` sets `KOLDERA_CHROMIUM_SANDBOX=true` and uses `docker/seccomp-chromium.json`: Docker's own
default profile plus one rule that allows `clone`, `unshare`, `setns` and `chroot` without `CAP_SYS_ADMIN` or
`CAP_SYS_CHROOT`, which is what Chromium needs to build its sandbox from unprivileged user namespaces. The container
still runs as uid 10001 with `cap_drop: [ALL]`, `no-new-privileges`, a read-only root filesystem and the same limits.
It never falls back: if the host does not allow user namespaces, Chromium refuses to start and the job fails with
`RENDER_FAILED`.

What you trade: user namespaces give a process inside the container more kernel surface to reach (a kernel bug in
that code is reachable from the container), in exchange for a browser exploit having to escape Chromium's sandbox
first. Choose it where input is untrusted; the default stays without it because it works on every Docker host.

Checked on Docker Engine 29.8 (Docker Desktop, WSL2 kernel 5.15): the HTML renderer and a web scan through the egress
proxy run with the sandbox, and the same job without the profile fails closed. Not checked on a Linux host with
AppArmor restrictions on unprivileged user namespaces (Ubuntu 24.04 sets `kernel.apparmor_restrict_unprivileged_userns=1`);
there the sandbox may not start until the host policy is adjusted, and then it fails closed as above. Test with a
sample job before relying on it:

```bash
docker compose -f docker/compose.yaml -f docker/compose.sandbox.yaml exec core \
  sh -c 'printf "<!doctype html><html lang=en><title>t</title><main><h1>t</h1></main>" > /tmp/t.html && koldera html /tmp/t.html --out /tmp/out'
```

Compensating controls in the supplied setup:

- the whole container runs as uid 10001, never root;
- `cap_drop: [ALL]` and `no-new-privileges`, read-only root filesystem, `/tmp` as tmpfs, memory and pid limits;
- `core` sits on an internal network without a gateway; outbound traffic can only pass the egress proxy;
- the renderer opens documents with JavaScript off, service workers and downloads refused, a `default-src 'none'`
  CSP and every request except the document itself aborted, so hostile page script does not run;
- the scanner does run page JavaScript (axe needs it) against a URL you supply. Use it only through the compose
  setup (egress proxy in front, public destinations only), not with `docker run` on a host network, and leave the
  `web` feature off (`KOLDERA_FEATURES`) if you do not need it.

If you run the image outside this setup, apply the same controls. Outside compose, the same two settings are
`-e KOLDERA_CHROMIUM_SANDBOX=true --security-opt seccomp=docker/seccomp-chromium.json`.

### `KOLDERA_CHROMIUM_SANDBOX`

The server passes the choice to the renderer and the scanner in each request's `policy` block as
`chromiumSandbox` (their process environment is cleared, so an environment variable alone would not reach them).
Playwright's `chromiumSandbox` launch option is always set explicitly:

| `KOLDERA_CHROMIUM_SANDBOX` | `policy.chromiumSandbox` | Chromium |
| --- | --- | --- |
| unset or `false` (default) | absent (false) | started with `--no-sandbox`, as described above |
| `true` | `true` | uses its own sandbox; only works where unprivileged user namespaces are allowed |

AWS Lambda and Docker's default seccomp profile do not allow those namespaces, so leave it at the default there; a
value of `true` makes Chromium fail to start on such hosts. With Docker, `docker/compose.sandbox.yaml` sets the
variable together with a seccomp profile that allows them. See [aws-lambda.md](aws-lambda.md) for running on Lambda.
