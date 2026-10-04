import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RULES = [
  ["milestone", /\b(M[1-5][ab]?|UF-\d+)\b/],
  ["owner-decision", /owner decision|plan 20\d\d-/i],
  ["internal-doc", /docs\/superpowers|claudedocs|docs\/1\d-|docs\/m\d-/],
  ["hosted-host", /koldera-accessibility\.vercel\.app|railway\.app|supabase|r2\.cloudflarestorage|ap_sk_|ap_pk_/i],
  ["claim-word", /\b(compliant|compliance|certified|certificate)\b|100\s*%\s*accessible/i],
];
const COMMENT = /^\s*(\/\/|\*|\/\*|#(?!!)|<!--)/;
const SKIP_DIRS = new Set([".git", "node_modules", "target", "bin", "obj", "dist", ".gradle"]);
const EXTENSIONS = /\.(java|cs|ts|tsx|mjs|js|md|yaml|yml|json|xml|properties|toml|conf|sh|Dockerfile)$|Dockerfile$/;

export function findViolations(file, text) {
  const out = [];
  const lines = text.split("\n");
  let run = 0;
  lines.forEach((line, i) => {
    for (const [rule, re] of RULES) if (re.test(line)) out.push({ file, line: i + 1, rule });
    run = COMMENT.test(line) ? run + 1 : 0;
    if (run === 7) out.push({ file, line: i + 1, rule: "long-comment" });
  });
  return out;
}

function* walk(p) {
  if (statSync(p).isDirectory()) {
    for (const name of readdirSync(p)) if (!SKIP_DIRS.has(name)) yield* walk(path.join(p, name));
  } else if (EXTENSIONS.test(p) && !/LICENSE|NOTICE|check-source-hygiene/.test(p)) {
    yield p;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const roots = process.argv.length > 2 ? process.argv.slice(2) : ["."];
  const violations = roots.flatMap((r) => [...walk(r)].flatMap((f) => findViolations(f, readFileSync(f, "utf8"))));
  for (const v of violations) console.log(`${v.file}:${v.line}: ${v.rule}`);
  process.exit(violations.length ? 1 : 0);
}
