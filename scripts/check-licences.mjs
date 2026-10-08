// Licence gate over the Maven, pnpm and NuGet dependency lists. koldera-core is distributed under proprietary terms,
// so every dependency must be compatible with proprietary distribution: permissive licences only. Anything else
// (copyleft, weak copyleft or unknown) fails until a person reviews that one package into EXCEPTIONS.
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

// No GPL/AGPL, and no LGPL/MPL/EPL here: weak-copyleft packages are allowed one by one in EXCEPTIONS.
export const ALLOWED = new Set([
  "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD", "OFL-1.1",
  "CC0-1.0", "Unlicense", "BlueOak-1.0.0", "Python-2.0",
  // Plain MIT/zlib-style grants found in the tree.
  "MIT-0", "Zlib",
]);

// Reviewed by hand: "ecosystem:name" -> why it is acceptable. Keep short.
const TEST_ONLY_EPL = "EPL-2.0, test scope only: not in the engine jar or the image";
const DEV_ONLY_MPL = "MPL-2.0, build/test tool (vitest -> vite) only: pruned from the image, never distributed";
export const EXCEPTIONS = {
  "nuget:xunit.abstractions": "test-only; nuspec has a licenseUrl only, the xunit project is Apache-2.0",
  "npm:axe-core": "MPL-2.0, shipped unmodified as a separate file; licence text in the image, source at github.com/dequelabs/axe-core",
  "npm:@axe-core/playwright": "MPL-2.0, shipped unmodified as a separate file; same terms and source as axe-core",
  ...Object.fromEntries(["junit-jupiter", "junit-jupiter-api", "junit-jupiter-params", "junit-jupiter-engine"].map((a) => [`maven:org.junit.jupiter:${a}`, TEST_ONLY_EPL])),
  ...Object.fromEntries(["junit-platform-commons", "junit-platform-launcher", "junit-platform-engine"].map((a) => [`maven:org.junit.platform:${a}`, TEST_ONLY_EPL])),
  // lightningcss publishes one native package per platform; pnpm installs only the host's.
  ...Object.fromEntries(["", "-darwin-arm64", "-darwin-x64", "-linux-arm64-gnu", "-linux-x64-gnu", "-linux-x64-musl", "-win32-x64-msvc"].map((p) => [`npm:lightningcss${p}`, DEV_ONLY_MPL])),
};

const ALIASES = [
  [/apache/i, "Apache-2.0"],
  [/eclipse public license.*2/i, "EPL-2.0"],
  [/bouncy ?castle/i, "MIT"],
  [/^(the )?mit license$|opensource\.org\/licen[sc]es\/mit/i, "MIT"],
  [/new bsd|bsd[- ]3|3-clause/i, "BSD-3-Clause"],
  [/simplified bsd|bsd[- ]2|2-clause/i, "BSD-2-Clause"],
  [/mozilla public license.*2/i, "MPL-2.0"],
  [/gnu lesser general public license.*3/i, "LGPL-3.0"],
  [/gnu affero general public license.*3/i, "AGPL-3.0"],
  [/licenses\.nuget\.org\/(.+)$/i, null],
];

const SPDX_ALIASES = {
  "AGPL-3.0-only": "AGPL-3.0", "AGPL-3.0-or-later": "AGPL-3.0",
  "LGPL-3.0-only": "LGPL-3.0", "LGPL-3.0-or-later": "LGPL-3.0", "LGPL-2.1+": "LGPL-2.1-or-later",
};

export function normalizeLicence(raw) {
  const text = String(raw ?? "").trim();
  if (!text) return "";
  if (/^[A-Za-z0-9.+-]+$/.test(text)) return SPDX_ALIASES[text] ?? text;
  for (const [re, id] of ALIASES) {
    const m = re.exec(text);
    if (m) return id ?? normalizeLicence(m[1]);
  }
  return text;
}

const EXPRESSION = /^\(?[A-Za-z0-9.+-]+\)?(\s+(AND|OR)\s+\(?[A-Za-z0-9.+-]+\)?)*$/;

// "(A OR B) AND C": OR binds looser than AND, parentheses nest. Returns the first disallowed id, or null if it passes.
function failing(expression) {
  const tokens = expression.replace(/\(/g, " ( ").replace(/\)/g, " ) ").split(/\s+/).filter(Boolean);
  let i = 0;
  const orExpr = () => {
    let bad = andExpr();
    while (tokens[i] === "OR") { i++; const next = andExpr(); bad = bad === null || next === null ? null : bad; }
    return bad;
  };
  const andExpr = () => {
    let bad = atom();
    while (tokens[i] === "AND") { i++; const next = atom(); bad = bad ?? next; }
    return bad;
  };
  const atom = () => {
    const t = tokens[i++];
    if (t === "(") { const r = orExpr(); i++; return r; }
    const id = normalizeLicence(t);
    return ALLOWED.has(id) ? null : id;
  };
  const result = orExpr();
  return i < tokens.length ? expression : result;
}

export function evaluate(entries, { exceptions = EXCEPTIONS } = {}) {
  const out = [];
  for (const e of entries) {
    if (exceptions[`${e.ecosystem}:${e.name}`]) continue;
    const expr = (e.license ?? "").trim();
    if (!expr) { out.push({ ...e, reason: "licence unknown" }); continue; }
    // A multi-word free-form name ("Eclipse Public License v2.0") is not an SPDX expression; normalise it whole.
    const bad = EXPRESSION.test(expr) ? failing(expr) : (ALLOWED.has(normalizeLicence(expr)) ? null : expr);
    if (bad !== null) out.push({ ...e, reason: `licence not allowed: ${bad}` });
  }
  return out;
}

export function parsePomLicences(pom) {
  const block = /<licenses>([\s\S]*?)<\/licenses>/.exec(pom);
  if (!block) return [];
  return [...block[1].matchAll(/<license>([\s\S]*?)<\/license>/g)].map((m) => {
    const name = /<name>([\s\S]*?)<\/name>/.exec(m[1])?.[1].trim();
    const url = /<url>([\s\S]*?)<\/url>/.exec(m[1])?.[1].trim();
    return name || url || "";
  }).filter(Boolean);
}

export function parseNuspecLicence(nuspec) {
  const expr = /<license\s+type="expression"[^>]*>([\s\S]*?)<\/license>/.exec(nuspec)?.[1].trim();
  if (expr) return expr;
  const url = /<licenseUrl>([\s\S]*?)<\/licenseUrl>/.exec(nuspec)?.[1].trim();
  if (!url) return "";
  const n = normalizeLicence(url);
  return n === url ? "" : n;
}

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const run = (cmd, args, cwd) => execFileSync(cmd, args, { cwd, encoding: "utf8", maxBuffer: 1 << 28, shell: process.platform === "win32" });

function mavenEntries() {
  const m2 = process.env.MAVEN_REPO ?? path.join(homedir(), ".m2", "repository");
  const outFile = path.join(mkdtempSync(path.join(tmpdir(), "licences-")), "deps.txt");
  run("mvn", ["-B", "-q", "dependency:list", `-DoutputFile=${outFile}`, "-DappendOutput=false"], path.join(root, "engine"));
  const pomOf = (g, a, v) => path.join(m2, ...g.split("."), a, v, `${a}-${v}.pom`);
  const licencesOf = (g, a, v, depth = 0) => {
    const file = pomOf(g, a, v);
    if (!existsSync(file) || depth > 5) return [];
    const text = readFileSync(file, "utf8");
    const own = parsePomLicences(text);
    if (own.length) return own;
    const parent = /<parent>([\s\S]*?)<\/parent>/.exec(text)?.[1];
    if (!parent) return [];
    const pick = (t) => new RegExp(`<${t}>([\\s\\S]*?)</${t}>`).exec(parent)?.[1].trim();
    return licencesOf(pick("groupId"), pick("artifactId"), pick("version"), depth + 1);
  };
  return readFileSync(outFile, "utf8").split(/\r?\n/).map((l) => l.trim()).filter((l) => /^[^\s:]+:[^\s:]+:[^\s:]+:[^\s:]+:[a-z]+/.test(l)).map((l) => {
    const [g, a, , v] = l.split(":");
    const found = licencesOf(g, a, v).map(normalizeLicence);
    return { ecosystem: "maven", name: `${g}:${a}`, version: v, license: found.join(" OR ") };
  });
}

function npmEntries(dir) {
  const json = JSON.parse(run("pnpm", ["licenses", "list", "--json"], path.join(root, dir)));
  return Object.entries(json).flatMap(([license, pkgs]) => pkgs.map((p) => ({ ecosystem: "npm", name: p.name, version: (p.versions ?? [p.version]).join(","), license })));
}

function nugetEntries() {
  const cache = process.env.NUGET_PACKAGES ?? path.join(homedir(), ".nuget", "packages");
  const seen = new Map();
  for (const proj of ["src/Koldera.Core", "tests/Koldera.Core.Tests"]) {
    const dirPath = path.join(root, "server", proj);
    const csproj = readdirSync(dirPath).find((f) => f.endsWith(".csproj"));
    const json = JSON.parse(run("dotnet", ["list", path.join(dirPath, csproj), "package", "--include-transitive", "--format", "json"], root));
    for (const p of json.projects ?? []) for (const f of p.frameworks ?? []) for (const pkg of [...(f.topLevelPackages ?? []), ...(f.transitivePackages ?? [])]) seen.set(`${pkg.id}@${pkg.resolvedVersion}`, pkg);
  }
  return [...seen.values()].map((pkg) => {
    const id = pkg.id.toLowerCase();
    const nuspec = path.join(cache, id, pkg.resolvedVersion, `${id}.nuspec`);
    return { ecosystem: "nuget", name: pkg.id, version: pkg.resolvedVersion, license: existsSync(nuspec) ? parseNuspecLicence(readFileSync(nuspec, "utf8")) : "" };
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const entries = [...mavenEntries(), ...npmEntries("renderer"), ...npmEntries("scanner"), ...nugetEntries()];
  const bad = evaluate(entries);
  const count = (eco) => entries.filter((e) => e.ecosystem === eco).length;
  console.log(`${entries.length} dependencies checked (maven ${count("maven")}, npm ${count("npm")}, nuget ${count("nuget")})`);
  for (const v of bad) console.error(`${v.ecosystem}:${v.name}@${v.version}: ${v.reason}`);
  process.exit(bad.length ? 1 : 0);
}
