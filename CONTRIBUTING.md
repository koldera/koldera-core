# Contributing

This page describes how koldera-core is developed. Please read it and the [Code of Conduct](CODE_OF_CONDUCT.md) first.

## Before you start

- For a bug, open an issue or write to the address in your agreement.
- For a new feature or a change to the API, open an issue first so we can agree on the shape before you write code.
- Report security problems privately, see [SECURITY.md](SECURITY.md).
- What the project promises, and what it never does, is in the [README](README.md). Changes must not add claims
  of conformance, generated alternative text or guessed structure.

## External contributions

koldera-core is proprietary software ([LICENSE](LICENSE)). Pull requests and patches from outside the Koldera team are
not accepted without a separate written agreement with the licensor that covers the rights in the contribution. Bug
reports and feature requests are welcome. Customers may keep their own modifications as their licence allows; see
[COMMERCIAL.md](COMMERCIAL.md).

## Repository layout and setup

| Component | Directory | Needs |
| --- | --- | --- |
| PDF engine | `engine/` | JDK 21, Maven 3.9, a veraPDF 1.30 launcher (`VERAPDF_CMD` or `verapdf` on `PATH`) |
| HTML and document renderer | `renderer/` | Node.js 22, pnpm, Chromium from Playwright; Typst 0.15.1 for the document tests (`TYPST_CMD`) |
| Web scanner | `scanner/` | Node.js 22, pnpm, Chromium from Playwright |
| Server and command-line tool | `server/` | .NET 10 SDK |

### Engine

```bash
cd engine
mvn -B test      # unit tests, no veraPDF needed
mvn -B verify    # also builds target/koldera-pdf-engine.jar and runs the integration tests (veraPDF required)
```

### Renderer and scanner

```bash
cd renderer     # or: cd scanner
pnpm install --frozen-lockfile
pnpm exec playwright install chromium-headless-shell
pnpm build && pnpm test
pnpm lint       # type check
```

The renderer's document tests run when `TYPST_CMD` points at a Typst 0.15.1 binary; without it they are skipped.

### Server

```bash
cd server
dotnet test
```

Some server tests drive the real tools and are skipped unless you point them at a built engine and renderer:
`KOLDERA_TEST_ENGINE_JAR` (the engine jar), `KOLDERA_TEST_RENDERER_CLI` (`renderer/dist/cli.js`), `VERAPDF_CMD` and
`KOLDERA_TYPST_CMD`. CI runs them and fails on any skip.

### Source hygiene

```bash
node scripts/check-source-hygiene.mjs
```

It must exit 0. It scans the repository for internal planning references, hosted-service hostnames, long comment
blocks and wording that claims more than a machine check can (conformance and certification claims). Say what was
checked and that human review is still required.

### Everything in the container

```bash
docker build -f docker/Dockerfile -t koldera-core:dev .
docker run --rm -p 8080:8080 -e KOLDERA_TOKEN=dev-token koldera-core:dev
```

## Making a change

- **Test first.** Write a failing test for the behaviour, then the smallest change that passes it.
- **Keep contracts in sync.** The HTTP API is described in `contracts/openapi.yaml`, and a server test compares it with
  the registered routes. If you add or change a route, a field or a problem code, update the OpenAPI file,
  `contracts/problem.schema.json` and the docs in the same commit. JSON schemas the workers share live in `contracts/`;
  the engine keeps byte-identical copies that a test enforces.
- **Never loosen a safety gate to make a test pass.** The original file stays untouched, a copy that regresses is
  rolled back, and ambiguity fails closed.
- **No real documents.** Fixtures are synthetic. Never commit customer files, tokens, keys or personal data. See
  [docs/fixtures.md](docs/fixtures.md).
- **Run what you touched** (the commands above) and the hygiene check before you push.

## Commit style

Conventional commits, small and focused, written in the imperative:

```text
fix(server): refuse a reused idempotency key with a different body
feat(engine): carry /Alt to rebuilt figures
docs: explain Prefer wait in the API guide
test(scanner): cover IPv6 documentation ranges
```

Types in use: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`. Say why in the body when the reason is not
obvious. Avoid messages like "update" or "fix".

## Pull requests

Describe what changed and why, list the commands you ran with their results, and note anything you could not run. Keep
a pull request to one concern. The Koldera team reviews it; expect questions about tests and about what a change could
make the tool claim.
