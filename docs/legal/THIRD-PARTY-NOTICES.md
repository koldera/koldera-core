# Third-party notices

> **Draft — pending legal review. Not legal advice.** This file describes what the koldera-core image and source
> archive contain from other authors, under which licences, and where the licence texts and source code are. It is
> verified against the image built from the same commit (versions and paths below are the ones checked by
> `scripts/third-party-notices.test.mjs`). The short list in [NOTICE](../../NOTICE) stays the summary.

koldera-core itself is proprietary (see [LICENSE](../../LICENSE)). Everything below keeps its own licence. Nothing in
the Koldera Commercial License Agreement changes those licences.

## Where the texts are

In the image, everything is under `/usr/share/licenses/koldera/` unless a path says otherwise. In the customer bundle
(`koldera-core-vX.Y.Z.tar.gz`) the same files are in `licenses/image-usr-share-licenses/`.

| File | What it is |
| --- | --- |
| `THIRD-PARTY-NOTICES.md` | this file |
| `third-party/apache-2.0.txt`, `third-party/bouncycastle-LICENSE.txt`, `third-party/slf4j-LICENSE.txt` | licence texts of the libraries shaded into the engine jar (the shade plugin drops them from the jar) |
| `third-party/node-LICENSE` | Node.js licence, including the licences of the components Node bundles |
| `chromium-headless-shell-LICENSE` | Chromium's credits file: the licence of every third-party component in the browser build |
| `typst-LICENSE`, `typst-NOTICE`, `typst-assets-NOTICE` | Typst, its own notices, and the licences of the fonts and data embedded in the Typst binary |
| `verapdf-LICENSE.GPL`, `verapdf-LICENSE.MPL`, `verapdf-bundled-components.txt` | veraPDF's two licence texts and the exact list of libraries inside its jars |
| `axe-core-LICENSE` | axe-core (MPL-2.0) |
| `fonts-noto-OFL.txt`, `fonts-liberation-LICENSE` | bundled fonts |
| `LICENSE`, `NOTICE` | koldera-core |

Per-package licences of the Ubuntu packages are in `/usr/share/doc/<package>/copyright`; those of the Node packages in
`/app/renderer/node_modules/<package>/` and `/app/scanner/node_modules/<package>/`; those of the .NET runtime in
`/usr/share/dotnet/LICENSE.txt` and `ThirdPartyNotices.txt`; those of the Java runtime in `/opt/java/openjdk/legal/`.

## Components

Licence elections are stated where a component offers a choice.

### Run as separate processes

| Component | Version | Licence | Used as | Source |
| --- | --- | --- | --- | --- |
| veraPDF | 1.30.2 | GPL-3.0-or-later **or** MPL-2.0 (dual). **MPL-2.0 is elected.** | command line `verapdf --flavour ua1 --format json`, no linking | https://github.com/veraPDF/veraPDF-apps and https://github.com/veraPDF/veraPDF-library, tag `v1.30.2` |
| Typst | 0.15.1 | Apache-2.0 | command line, no linking | https://github.com/typst/typst, tag `v0.15.1` |
| Chromium headless shell | Chrome for Testing 153.0.8010.12 (Playwright 1.63.0, build 1243) | BSD-3-Clause and the licences of its dependencies, listed in `chromium-headless-shell-LICENSE` (some are LGPL or MPL) | started by Playwright | https://chromium.googlesource.com/chromium/src, tag `153.0.8010.12` |
| Node.js | 22.23.3 | MIT and the licences of its bundled components, in `node-LICENSE` | runs the renderer and scanner workers | https://github.com/nodejs/node, tag `v22.23.3` |
| Eclipse Temurin JRE | 21 (image digest pinned in `docker/Dockerfile`) | GPL-2.0 with Classpath Exception | runs the PDF engine and veraPDF | https://github.com/adoptium/jdk21u |
| .NET and ASP.NET Core runtime | 10.0 | MIT | runs the server | https://github.com/dotnet/runtime |
| Ubuntu 24.04 base and packages (Chromium's system libraries, fonts, `curl`, the CA trust store) | as installed in the image | various, mostly GPL, LGPL and permissive; each package's `copyright` file | unmodified distribution packages | https://archive.ubuntu.com/ubuntu/ (`apt-get source <package>`) |

veraPDF is used as shipped upstream, with one change: the Jackson libraries inside its fat jar may be replaced by
newer patch releases of the same library (the versions are in `verapdf-bundled-components.txt`). No veraPDF class is
modified.

### Inside veraPDF's jars

From `verapdf-bundled-components.txt` and the licence files inside `cli-1.30.2.jar`:

| Component | Licence | Evidence |
| --- | --- | --- |
| Jackson (core, databind, annotations) | Apache-2.0 | `META-INF/LICENSE` and `NOTICE` in the jar; FastDoubleParser (MIT) is shaded into jackson-core |
| JAXB runtime (`jaxb-runtime`, `jaxb-core`, `txw2`) | EDL-1.0 (BSD-3-Clause) | `META-INF/NOTICE.md`, `META-INF/LICENSE.md` |
| `javax.activation-api`, `jaxb-api`, `istack-commons-runtime` | not stated in the jar (see open points) | Maven coordinates only |
| Saxon-HE | MPL-2.0 | `net/sf/saxon` classes; Saxon-HE is published under MPL-2.0 |
| Mozilla Rhino | MPL-2.0 | `META-INF/LICENSE.txt`, `NOTICE.txt` |
| JCommander, XML Resolver, StAX utilities | see open points | XML Resolver carries only a W3C notice; JCommander and the StAX utilities no licence file |

MPL-2.0 requires that the source of the MPL-licensed files stay available; see "Source code" below. The MPL-2.0 text
is `verapdf-LICENSE.MPL`.

### Linked into the PDF engine (`koldera-pdf-engine.jar`)

| Component | Version | Licence |
| --- | --- | --- |
| Apache PDFBox, FontBox, xmpbox | 3.0.8 | Apache-2.0 (copyright and attribution lines in the merged `META-INF/NOTICE`) |
| Apache Commons Logging | 1.4.0 | Apache-2.0 |
| Jackson (core, databind, annotations, dataformat-yaml) | 2.22.x | Apache-2.0 |
| SnakeYAML | 2.5 | Apache-2.0 |
| networknt json-schema-validator | 1.5.9 | Apache-2.0 |
| Ethlo ITU | 1.14.0 | Apache-2.0 |
| Bouncy Castle (bcprov, bcpkix) | 1.86 | MIT, `third-party/bouncycastle-LICENSE.txt` |
| SLF4J API and nop binding | 2.0.x | MIT, `third-party/slf4j-LICENSE.txt` |
| Liberation Sans and Serif (font resources) | bundled | OFL-1.1, `fonts-liberation-LICENSE` |

The Apache-2.0 text is `third-party/apache-2.0.txt`. Test-only libraries (JUnit and others) are not in the jar or the
image.

### Node workers (renderer and scanner)

Playwright 1.63.0 (Apache-2.0), axe-core and @axe-core/playwright 4.13.0 (**MPL-2.0**, used unmodified), Ajv 8.20.0 and
ajv-formats 3.0.1 (MIT), parse5 8.0.1 (MIT), pdf-lib 1.17.1 (MIT), pngjs 7.0.0 (MIT) and their dependencies. Each
package directory under `node_modules` carries its own licence file. The `licences` job of CI fails when a dependency
has a licence outside the permitted list (`scripts/check-licences.mjs`).

### Fonts and data embedded in Typst

The Typst binary embeds fonts and data. Their licences are in `typst-assets-NOTICE` (typst-assets 0.15.1):
Libertinus Serif (OFL-1.1), New Computer Modern (GUST Font License 1.0; the NewCM10-Regular file is GPL-3.0-or-later
with Font Exception and Distribution Exception), DejaVu Sans Mono (Bitstream Vera licence), Foxit fonts (BSD-style,
PDFium authors), ICC profiles (CC0-1.0). Typst's own third-party notices are in `typst-NOTICE`.

### Fonts bundled by koldera-core

Noto Sans, Noto Serif, Noto Sans Arabic and Noto Sans Hebrew in `renderer/fonts` (OFL-1.1, `fonts-noto-OFL.txt`);
Liberation Sans and Serif in the engine jar (OFL-1.1).

### Not part of the koldera-core image

`docker/compose*.yaml` also start Caddy (Apache-2.0), Squid (GPL-2.0-or-later, built from `docker/egress-proxy`) and,
for single sign-on, oauth2-proxy (MIT). They are separate images that the customer pulls or builds; Koldera does not
redistribute them. The written source offer for Squid is in `docker/egress-proxy/README.md`.

## Source code

For the components above that are under the GPL, LGPL or MPL, the corresponding source code is available from the
upstream locations in the tables. For the Ubuntu packages it is available from the Ubuntu archive with
`apt-get source <package>`, for the exact versions listed by `dpkg -l` inside the image.

**Written offer.** For three years from the date a customer receives a koldera-core release, Koldera will, on
request, send the corresponding source code of any GPL, LGPL or MPL component in that release that is not available
from the locations above, for the cost of media and shipping. Contact: licensing@koldera-accessibility.dev (the
address already named in `docker/egress-proxy/README.md`; the licensor confirms it is monitored).

## Open points for the legal review

1. **veraPDF election.** The licensor elects MPL-2.0 for veraPDF (dual licence). Confirm this wording is acceptable
   in the customer agreement and that no GPL-3.0 obligation remains for the unmodified jar run as a separate process.
2. **Jars inside veraPDF.** The fat jar has no licence text for `javax.activation-api`, `jaxb-api`,
   `istack-commons-runtime`, JCommander, XML Resolver (only a W3C notice) and the StAX utilities. Their upstream
   licences are expected to be permissive or CDDL/GPL-with-Classpath-Exception, but this has not been verified; confirm
   each and, if needed, add the text.
3. **New Computer Modern in Typst.** `NewCM10-Regular` is GPL-3.0-or-later with a Font Exception and a Distribution
   Exception. Confirm that distributing the Typst binary that embeds it, and documents rendered with it, needs
   nothing beyond `typst-assets-NOTICE`.
4. **Chromium and LGPL parts.** Confirm the pointer to the Chromium source tag satisfies the LGPL parts listed in
   `chromium-headless-shell-LICENSE`. The unused ffmpeg build that Playwright installs next to the browser is removed
   from the image.
5. **Written offer** wording, duration and contact address.
6. **Ubuntu packages.** Confirm that pointing to the Ubuntu archive is enough for the GPL/LGPL packages in the image.
