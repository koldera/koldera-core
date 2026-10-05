# Deployment

The image is built from `docker/Dockerfile`; `docker/compose.yaml` is the hardened single-host setup.

```bash
export KOLDERA_IMAGE=ghcr.io/koldera/core:0.1.0   # required, no default tag
export KOLDERA_TOKEN="$(openssl rand -base64 32)"
docker compose -f docker/compose.yaml up -d
curl -H "Authorization: Bearer $KOLDERA_TOKEN" http://localhost:8080/v1/info
```

Only `front` (Caddy) publishes a port (8080). `core` is on an internal network with no route out; its only way to
the internet is the `egress` Squid proxy, which refuses private, loopback, link-local and metadata addresses.
For TLS see `docker/Caddyfile`; for single sign-on `docker/compose.sso.yaml` (needs Docker Compose 2.x; every OIDC
variable is required and has no default). Base images are pinned by digest in the Dockerfile and compose files.

## Who calls the API

koldera-core is built to be called by your backend. Pick one of three patterns and do not publish the server to
the open internet without it.

1. **Backend only (recommended).** Your server holds `KOLDERA_TOKEN` and calls the API; the server is on a private
   network or behind your own gateway. Browsers never see the token.
2. **Browser through client sessions.** Your backend mints a short-lived token with `POST /v1/client-sessions` for one
   origin, with scopes, a job and upload budget and a lifetime of at most 60 minutes, and hands only that token to
   the browser. The token is useless from another origin. Origin and CORS checks are secondary controls, not secrets:
   the session token is the credential. Budgets are counted in memory and reset on restart.
3. **Single sign-on in front.** `docker/compose.sso.yaml` puts oauth2-proxy (OIDC) before every request. People sign
   in through the proxy; the server still requires its own bearer token on `/v1`, so the proxy proves who the person
   is and your backend still decides what it may call.

Whatever you choose: terminate TLS in front of the server and set `KOLDERA_PUBLIC_URL`, set the limits in
[configuration.md](configuration.md), and keep `KOLDERA_AUTH=none` for a loopback address or a proxy that
authenticates every request. The server is one instance with a local data directory; do not put several behind one
load balancer. Serverless: see [aws-lambda.md](aws-lambda.md).

## Browser sandbox

The renderer and the web scanner drive Chromium through Playwright, which starts it with `--no-sandbox`
(`chromiumSandbox: false` is Playwright's default). Chromium's own sandbox needs unprivileged user namespaces,
which Docker's default seccomp profile blocks, so the process runs without it. A browser exploit would therefore
not be stopped by Chromium's sandbox. This is a known residual risk, not something the image hides.

Compensating controls in the supplied setup:

- the whole container runs as uid 10001, never root;
- `cap_drop: [ALL]` and `no-new-privileges`, read-only root filesystem, `/tmp` as tmpfs, memory and pid limits;
- `core` sits on an internal network without a gateway; outbound traffic can only pass the egress proxy;
- the renderer opens documents with JavaScript off, service workers and downloads refused, a `default-src 'none'`
  CSP and every request except the document itself aborted, so hostile page script does not run;
- the scanner does run page JavaScript (axe needs it) against a URL you supply. Use it only through the compose
  setup (egress proxy in front, public destinations only), not with `docker run` on a host network, and leave the
  `web` feature off (`KOLDERA_FEATURES`) if you do not need it.

If you run the image outside this setup, apply the same controls. A custom seccomp profile that allows user
namespaces would let Chromium sandbox itself, but that weakens the container boundary and is not done here.

### `KOLDERA_CHROMIUM_SANDBOX`

The server passes the choice to the renderer and the scanner in each request's `policy` block as
`chromiumSandbox` (their process environment is cleared, so an environment variable alone would not reach them).
Playwright's `chromiumSandbox` launch option is always set explicitly:

| `KOLDERA_CHROMIUM_SANDBOX` | `policy.chromiumSandbox` | Chromium |
| --- | --- | --- |
| unset or `false` (default) | absent (false) | started with `--no-sandbox`, as described above |
| `true` | `true` | uses its own sandbox; only works where unprivileged user namespaces are allowed |

AWS Lambda and Docker's default seccomp profile do not allow those namespaces, so leave it at the default there; a
value of `true` makes Chromium fail to start on such hosts. See [aws-lambda.md](aws-lambda.md) for running on Lambda.
