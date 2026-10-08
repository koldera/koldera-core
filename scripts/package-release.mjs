// Builds the private customer bundle for one release tag. Usage and the bundle layout: RELEASING.md.
//   node scripts/package-release.mjs vX.Y.Z [--out dir] [--image ref] [--skip-image]
// The source archive and the image come from the same commit: the tree must be clean, HEAD must be the tag,
// and the image must carry that commit in org.opencontainers.image.revision.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, createReadStream, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const TAG = /^v(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/;
const SECRET_NAME = /^(\.env(\..+)?|id_(rsa|dsa|ecdsa|ed25519)|.*\.(pem|key|p12|pfx|jks|keystore))$/i;
const SECRET_TEXT = /-----BEGIN [A-Z ]*PRIVATE KEY-----/;
// Licence texts and notices that the image carries; a path the image lacks is reported and skipped.
const IMAGE_LICENCE_PATHS = [
  ["/usr/share/licenses", "image-usr-share-licenses"],
  ["/licenses", "image-licenses"],
  ["/usr/share/doc", "os-packages"], // Ubuntu keeps only the per-package copyright files in container images
  ["/opt/java/openjdk/legal", "temurin-jre"],
  ["/usr/share/dotnet/LICENSE.txt", "dotnet/LICENSE.txt"],
  ["/usr/share/dotnet/ThirdPartyNotices.txt", "dotnet/ThirdPartyNotices.txt"],
];
const DOCS = [
  ["docs/release/INSTALL.md", "INSTALL.md"],
  ["docs/deployment.md", "DEPLOYMENT.md"],
  ["docs/configuration.md", "CONFIGURATION.md"],
  ["docs/release/UPGRADE.md", "UPGRADE.md"],
  ["SECURITY.md", "SECURITY.md"],
  ["docs/api.md", "API.md"],
  ["contracts/openapi.yaml", "openapi.yaml"],
  ["CHANGELOG.md", "CHANGELOG.md"],
];

export function parseArgs(argv) {
  const opts = { tag: null, out: "dist/release", image: null, skipImage: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--skip-image") opts.skipImage = true;
    else if (a === "--out" || a === "--image") opts[a.slice(2)] = argv[++i];
    else if (!a.startsWith("-") && !opts.tag) opts.tag = a;
    else throw new Error(`unknown argument: ${a}`);
  }
  if (!opts.tag || !TAG.test(opts.tag)) throw new Error("first argument must be a release tag like v1.2.3");
  if (opts.out === undefined || opts.image === undefined) throw new Error("--out and --image need a value");
  return opts;
}

// Files in a directory tree that look like credentials, by name or by content.
export function findSecrets(dir) {
  const hits = [];
  for (const f of listFiles(dir)) {
    const base = path.basename(f);
    if (SECRET_NAME.test(base)) hits.push(`${f}: secret-like file name`);
    else if (statSync(path.join(dir, f)).size < 5_000_000 && SECRET_TEXT.test(readFileSync(path.join(dir, f), "latin1"))) hits.push(`${f}: private key`);
  }
  return hits;
}

export function listFiles(dir, rel = "") {
  return readdirSync(path.join(dir, rel), { withFileTypes: true }).flatMap((e) => {
    const p = rel ? `${rel}/${e.name}` : e.name;
    return e.isDirectory() ? listFiles(dir, p) : e.isFile() ? [p] : []; // symlinks: their targets are listed
  }).sort();
}

const run = (cmd, args, o = {}) => (execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "inherit"], maxBuffer: 1 << 26, ...o }) ?? "").trim();
const has = (cmd) => { try { execFileSync(cmd, ["version"], { stdio: "ignore" }); return true; } catch { return false; } };
const sha256 = (file) => new Promise((resolve, reject) => {
  const h = createHash("sha256");
  createReadStream(file).on("data", (d) => h.update(d)).on("end", () => resolve(h.digest("hex"))).on("error", reject);
});
const log = (m) => console.error(`package-release: ${m}`);

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const root = run("git", ["rev-parse", "--show-toplevel"]);
  process.chdir(root);
  const version = opts.tag.match(TAG)[1];

  // 1. Same commit for source and image.
  if (run("git", ["status", "--porcelain"])) throw new Error("working tree is not clean (tracked changes or untracked files); commit or remove them");
  const commit = run("git", ["rev-parse", "HEAD"]);
  let tagCommit;
  try { tagCommit = run("git", ["rev-parse", "--verify", "--quiet", `refs/tags/${opts.tag}^{commit}`]); } catch { throw new Error(`tag ${opts.tag} does not exist locally`); }
  if (tagCommit !== commit) throw new Error(`HEAD ${commit} is not ${opts.tag} (${tagCommit}); check out the tag first`);

  const name = `koldera-core-${opts.tag}`;
  const outDir = path.resolve(opts.out);
  const bundle = path.join(outDir, name);
  rmSync(bundle, { recursive: true, force: true });
  rmSync(`${bundle}.tar.gz`, { force: true });
  for (const d of ["source", "image", "docs", "licenses"]) mkdirSync(path.join(bundle, d), { recursive: true });

  // 2. Source: git archive of the tag (no history; export-ignore paths in .gitattributes are left out).
  const srcTar = path.join(outDir, `${name}-source.tar`);
  run("git", ["archive", "--format=tar", `--prefix=${name}/`, "-o", srcTar, opts.tag]);
  run("tar", ["-xf", path.basename(srcTar), "-C", path.relative(outDir, path.join(bundle, "source")), "--strip-components=1"], { cwd: outDir });
  rmSync(srcTar);
  const secrets = findSecrets(path.join(bundle, "source"));
  if (secrets.length) throw new Error(`refusing to package, possible secrets in source:\n  ${secrets.join("\n  ")}`);
  if (has("gitleaks")) run("gitleaks", ["dir", path.join(bundle, "source"), "--config", ".gitleaks.toml", "--redact", "--no-banner"], { stdio: "inherit" });
  else log("gitleaks not on PATH: only the built-in secret check ran on source/");

  // 3. Image: build it (or take --image), check its revision label, save it, extract its licence texts, SBOM.
  let image = null;
  if (!opts.skipImage) {
    const ref = opts.image ?? `koldera-core:${version}`;
    if (!opts.image) {
      run("docker", ["build", "-f", "docker/Dockerfile", "--platform", "linux/amd64", "-t", ref,
        "--label", `org.opencontainers.image.revision=${commit}`,
        "--build-arg", `KOLDERA_VERSION=${version}`,
        "--label", `org.opencontainers.image.version=${version}`,
        "--label", `org.opencontainers.image.created=${new Date().toISOString()}`, "."], { stdio: "inherit" });
    }
    const info = JSON.parse(run("docker", ["image", "inspect", ref]))[0];
    const revision = info.Config?.Labels?.["org.opencontainers.image.revision"];
    if (revision !== commit) throw new Error(`image ${ref} has revision label ${revision ?? "(none)"}, expected ${commit}`);
    if (`${info.Os}/${info.Architecture}` !== "linux/amd64") throw new Error(`image ${ref} is ${info.Os}/${info.Architecture}, expected linux/amd64`);
    const imageTar = `image/${name}-linux-amd64.tar`;
    run("docker", ["save", "-o", path.join(bundle, imageTar), ref]);
    image = { ref, id: info.Id, repoDigests: info.RepoDigests ?? [], platform: "linux/amd64", revision, file: imageTar };
    writeFileSync(path.join(bundle, "image", "image-digest.txt"), `${info.Id}  ${ref}  linux/amd64\n`);

    const ctr = run("docker", ["create", ref]);
    try {
      for (const [from, to] of IMAGE_LICENCE_PATHS) {
        const dest = path.join(bundle, "licenses", to);
        mkdirSync(path.dirname(dest), { recursive: true });
        try { run("docker", ["cp", "-L", `${ctr}:${from}`, dest], { stdio: "ignore" }); } catch { log(`image has no ${from}, skipped`); }
      }
    } finally {
      run("docker", ["rm", ctr]);
    }

    // The SBOM describes the saved archive, which is exactly what the customer loads.
    const sbomArgs = [`docker-archive:${imageTar}`, "-o", "spdx-json=sbom.spdx.json"];
    if (has("syft")) run("syft", sbomArgs, { cwd: bundle, stdio: "inherit" });
    else run("docker", ["run", "--rm", "-v", `${bundle}:/bundle`, "-w", "/bundle", "anchore/syft:v1.54.1@sha256:3eb5379ba7b409c3f4069b686110527af0c47df993fa5c10d13e7cf34f49b1aa", ...sbomArgs], { stdio: "inherit" });
  } else {
    log("--skip-image: no image, image licence texts or SBOM in this bundle (not deliverable)");
  }

  // 4. Docs and licences from the tagged source.
  const src = path.join(bundle, "source");
  for (const [from, to] of DOCS) {
    if (!existsSync(path.join(src, from))) throw new Error(`missing doc ${from} in the tag`);
    cpSync(path.join(src, from), path.join(bundle, "docs", to));
  }
  for (const f of ["LICENSE", "NOTICE"]) cpSync(path.join(src, f), path.join(bundle, "licenses", f));

  // 5. Manifest (every file but itself and SHA256SUMS), then SHA256SUMS over everything.
  const files = [];
  for (const f of listFiles(bundle)) files.push({ path: f, sha256: await sha256(path.join(bundle, f)), size: statSync(path.join(bundle, f)).size });
  const manifest = {
    name: "koldera-core", version, tag: opts.tag, commit, buildTime: new Date().toISOString(),
    deliverable: !opts.skipImage, image, sbom: opts.skipImage ? null : "sbom.spdx.json", licence: "licenses/LICENSE", notice: "licenses/NOTICE", files,
  };
  writeFileSync(path.join(bundle, "release-manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  const sums = [];
  for (const f of listFiles(bundle)) sums.push(`${await sha256(path.join(bundle, f))}  ${f}`);
  writeFileSync(path.join(bundle, "SHA256SUMS"), sums.join("\n") + "\n");

  // 6. One archive plus its checksum.
  const archive = `${name}.tar.gz`;
  run("tar", ["-czf", archive, name], { cwd: outDir });
  writeFileSync(path.join(outDir, `${archive}.sha256`), `${await sha256(path.join(outDir, archive))}  ${archive}\n`);
  log(`wrote ${path.join(outDir, archive)} (${files.length + 2} files, commit ${commit})`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((e) => { console.error(`package-release: ${e.message}`); process.exit(1); });
}
