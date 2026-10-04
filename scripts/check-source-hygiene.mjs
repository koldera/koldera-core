import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RULES = [
  ["milestone", /\b(M[1-5][ab]?|UF-\d+)\b/],
  ["owner-decision", /owner decision|plan 20\d\d-/i],
  ["internal-doc", /docs\/superpowers|claudedocs|docs\/1\d-|docs\/m\d-/],
  ["hosted-host", /koldera-accessibility\.vercel\.app|railway\.app|supabase\.co|r2\.cloudflarestorage|ap_sk_|ap_pk_/i],
  ["claim-word", /\b(compliant|compliance|certified)\b|100\s*%\s*accessible/i],
  ["claim-word", /\bcertificates?\b/i, /\.md$/],
];
const SLASH = /^\s*(\/\/|\*|\/\*)/;
const HASH = /^\s*#(?!!)/;
const MARKUP = /^\s*<!--/;
const SKIP_DIRS = new Set([".git", "node_modules", "target", "bin", "obj", "dist", ".gradle"]);
const SKIP_FILES = new Set(["LICENSE", "NOTICE", "check-source-hygiene.mjs", "check-source-hygiene.test.mjs", "pnpm-lock.yaml", "package-lock.json", "packages.lock.json"]);
const DOCKERFILE = /^Dockerfile(\..*)?$|\.Dockerfile$/;
const EXTENSIONS = /\.(java|cs|ts|tsx|mjs|js|md|yaml|yml|json|xml|properties|toml|conf|sh|csproj|props|slnx|kts|gradle|html|css|sql|ps1|cmd|txt)$/;

function commentPattern(base) {
  if (DOCKERFILE.test(base) || /\.(yaml|yml|properties|toml|conf|sh)$/.test(base)) return HASH;
  if (/\.(java|cs|ts|tsx|js|mjs|kts|gradle|css)$/.test(base)) return SLASH;
  if (/\.(md|xml|html|csproj|props)$/.test(base)) return MARKUP;
  return null;
}

export function findViolations(file, text) {
  const out = [];
  const base = path.basename(file);
  const comment = commentPattern(base);
  let run = 0;
  text.split("\n").forEach((line, i) => {
    for (const [rule, re, only] of RULES) if ((!only || only.test(base)) && re.test(line)) out.push({ file, line: i + 1, rule });
    run = comment && comment.test(line) ? run + 1 : 0;
    if (run === 7) out.push({ file, line: i + 1, rule: "long-comment" });
  });
  return out;
}

function isScanned(p) {
  const base = path.basename(p);
  return !SKIP_FILES.has(base) && (EXTENSIONS.test(base) || DOCKERFILE.test(base));
}

function* walk(p) {
  if (statSync(p).isDirectory()) {
    for (const name of readdirSync(p)) if (!SKIP_DIRS.has(name)) yield* walk(path.join(p, name));
  } else if (isScanned(p)) {
    yield p;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const roots = process.argv.length > 2 ? process.argv.slice(2) : ["."];
  const missing = roots.filter((r) => !existsSync(r));
  if (missing.length) {
    for (const m of missing) console.error(`${m}: not found`);
    process.exit(2);
  }
  const violations = roots.flatMap((r) => [...walk(r)].flatMap((f) => findViolations(f, readFileSync(f, "utf8"))));
  for (const v of violations) console.log(`${v.file}:${v.line}: ${v.rule}`);
  process.exit(violations.length ? 1 : 0);
}
