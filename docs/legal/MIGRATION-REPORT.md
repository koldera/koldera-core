# Licensing migration report: AGPL-3.0-only + commercial → proprietary commercial

> **Draft — pending legal review. Not legally approved.** This report collects facts from the repository for the
> lawyer reviewing the change. It is not legal advice. Facts marked "to confirm" could not be checked from the
> repository.

Prepared: 2026-10-08. Branch: `docs/commercial-licensing-migration`.

## 1. Change

| | Before | After (proposed) |
| --- | --- | --- |
| Licence of koldera-core | AGPL-3.0-only, plus a commercial licence on request | Proprietary, source-available to customers under a signed Koldera Commercial License Agreement |
| Licensor | Roman Lakusta (individual) | Roman Lakusta (individual) |
| Contributions | CLA required (never exercised) | No external contributions without a separate written agreement; CLA and its workflow removed |
| SDKs and CLI | Apache-2.0 | Unchanged (Apache-2.0) |

The original AGPL-3.0 licence text stays in the git history: it was added in commit `5e3c4b4`
(`chore: repository skeleton and source hygiene check`, 2026-10-05). History is not rewritten.

## 2. Copyright ownership

`git shortlog -sne HEAD` on `origin/main` (commit `9779d9c`, 2026-10-08):

```text
    65  rlakusta <makulatura78@gmail.com>
     5  dependabot[bot] <49699333+dependabot[bot]@users.noreply.github.com>
     2  Rlakusta <makulatura78@gmail.com>
```

- Both human identities are Roman Lakusta. No other human author.
- Dependabot commits only change dependency version pins and lockfiles; they carry no creative authorship.
- No vendored third-party source code. Bundled third-party files are fonts (OFL-1.1) with their licence texts.
- The CLA ([removed in this change](#5-files-changed-by-this-proposal)) was never signed by anyone: there were no
  outside pull requests and the CLA workflow never ran successfully.
- To confirm: no employer, client or other party has a claim on the work (employment contract, IP assignment
  clause, work done for hire, public funding).

## 3. Historical AGPL distribution

Evidence from GitHub (`gh repo view`, `gh release list`, `git ls-remote --tags`, `gh api`), 2026-10-08:

- Repository `koldera/koldera-core` created 2026-10-04 as **private**; still private.
- No tags, no GitHub releases, no published container packages in the organisation, no forks.
- Only collaborator: `makulatura78` (Roman Lakusta).

So no copy is known to have been distributed under AGPL-3.0-only. **To confirm off-repo:** nobody else received
the source, a build, or an image (e-mail, USB, demo installs); no website, SDK page or package listing stated that
koldera-core is AGPL-licensed. If any copy was distributed, its recipients keep their AGPL-3.0 rights for that
version. The new LICENSE says so and does not claim revocation.

## 4. Third-party components and obligations

Verified against `engine/pom.xml`, `renderer/package.json`, `scanner/package.json`, `server/Directory.Packages.props`,
`docker/Dockerfile` and `docker/egress-proxy/Dockerfile` on 2026-10-08. The full list is in [NOTICE](../../NOTICE)
(attributions unchanged by this proposal).

| Component | Licence | How it is used | Obligation when the image or source is delivered |
| --- | --- | --- | --- |
| veraPDF 1.30.2 | GPL-3.0-or-later OR MPL-2.0 | Unmodified CLI, separate process | Elect MPL-2.0; ship licence text (done: `verapdf-LICENSE.MPL`/`.GPL`); point to source |
| axe-core, @axe-core/playwright 4.13.0 | MPL-2.0 | Unmodified, in `node_modules`, injected into scanned pages | Ship licence (done: `axe-core-LICENSE`); point to source (NOTICE) |
| Eclipse Temurin JRE 21 | GPL-2.0 with Classpath Exception | Runtime in image | Ship licence texts (inside the JRE directory); source available from Adoptium |
| Ubuntu 24.04 packages (base, `curl`, the CA bundle package, Chromium dependencies installed by Playwright) | Various GPL/LGPL/permissive | Unmodified distro packages | Copyright files under `/usr/share/doc`; source via Ubuntu archive |
| Squid 6.14 (Ubuntu package) | GPL-2.0-or-later | Separate egress-proxy image | Written source offer in `docker/egress-proxy/README.md` |
| Typst 0.15.1 | Apache-2.0 | Unmodified binary, separate process | Ship licence (done: `typst-LICENSE`) |
| Chromium headless shell | BSD-3-Clause plus its dependencies' licences | Installed by Playwright, separate process | Ship credits / licence notices (gap, see below) |
| Playwright 1.63.0 | Apache-2.0 | Library | Licence + NOTICE in `node_modules` |
| Apache PDFBox, xmpbox | Apache-2.0 | Linked, shaded into the engine jar | Licence + NOTICE (shade merges them with `ApacheLicense`/`ApacheNotice` transformers) |
| Jackson, json-schema-validator, Commons Logging | Apache-2.0 | Linked, shaded | As above |
| Bouncy Castle (bcprov, bcpkix) | MIT-style | Linked, shaded | Keep copyright notice |
| SLF4J, Ajv, ajv-formats, parse5, pdf-lib, pngjs | MIT | Linked | Keep copyright notice (in package files) |
| .NET / ASP.NET Core runtime | MIT | Runtime in image | Licence in runtime directory |
| Node.js 22 | MIT plus bundled components | Only the `node` binary is copied | Ship Node's LICENSE (gap, see below) |
| Noto, Liberation fonts | OFL-1.1 | Bundled | Licence texts shipped (done) |
| Caddy, oauth2-proxy | Apache-2.0, MIT | Separate upstream images in compose files | Not redistributed by Koldera unless images are delivered offline |

No AGPL-licensed dependency was found among direct dependencies. Proprietary licensing of koldera-core is
compatible with the components above as long as GPL components stay unmodified and in separate processes, and their
licence texts and source pointers are delivered.

## 5. Gaps found

1. **Node.js LICENSE not shipped**: the Dockerfile copies only `/usr/local/bin/node`; Node's `LICENSE` (which also
   lists its bundled components) is not in `/usr/share/licenses/koldera`.
2. **Chromium credits not shipped**: the headless shell's third-party notices are not collected.
3. **Typst embedded fonts**: the Typst binary embeds fonts whose licences are not shipped separately.
4. **veraPDF bundled jars unverified**: the installer bundles third-party jars whose licences were not inventoried.
5. **Shaded engine jar**: confirm the merged `META-INF/LICENSE`/`NOTICE` in `koldera-pdf-engine.jar` is complete.
6. **Licence gate**: `scripts/check-licences.mjs` still describes koldera-core as AGPL-3.0-only (line 1) and admits
   AGPL/LGPL/EPL. Owned by a separate pull request; not changed here.
7. **Copies of `contracts/openapi.yaml`** in the SDK repositories and `sdk-contract-kit` still say AGPL-3.0-only in
   `info.license`; they need a sync after this change.

## 6. Files changed by this proposal

LICENSE, NOTICE (line 3 only), COMMERCIAL.md, README.md, CONTRIBUTING.md, CLA.md (removed),
`.github/workflows/cla.yml` (removed), `.github/ISSUE_TEMPLATE/config.yml`, `.github/ISSUE_TEMPLATE/feature.yml`,
`.github/pull_request_template.md`, `renderer/package.json`, `scanner/package.json`, `contracts/openapi.yaml`,
`docker/Dockerfile`, `docker/egress-proxy/Dockerfile`, `docker/egress-proxy/README.md`, `docs/commercial/*`,
this report.

## 7. Required approvals (all open)

- [ ] Licensor entity confirmed: Roman Lakusta personally. If a company will license, a written copyright
      assignment or exclusive licence from Roman Lakusta to it first.
- [ ] Copyright ownership confirmed (section 2, "to confirm").
- [ ] No prior AGPL distribution confirmed (section 3, "to confirm").
- [ ] Lawyer review of LICENSE, COMMERCIAL.md and `docs/commercial/*`, and of the agreement template.
- [ ] Placeholders filled: [GOVERNING LAW], [LIABILITY CAP], [WARRANTY], affiliates, contractors, deployments,
      internal vs customer-facing use, termination, OEM terms.
- [ ] Third-party gaps 1–5 closed before the first delivery.
- [ ] SDK / contract-kit OpenAPI copies synced.
