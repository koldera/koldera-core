import test from "node:test";
import assert from "node:assert/strict";
import { findViolations } from "./check-source-hygiene.mjs";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

test("flags internal milestone names, dates of decisions and hosted hostnames", () => {
  const text = "// M3b: order evidence\n// owner decision 2026-09-27\nconst u = \"https://x.up.railway.app\";\n";
  const rules = findViolations("a.ts", text).map((v) => v.rule);
  assert.deepEqual(rules, ["milestone", "owner-decision", "hosted-host"]);
});

test("flags comment blocks longer than six lines", () => {
  const block = Array.from({ length: 7 }, (_, i) => `// line ${i}`).join("\n");
  assert.deepEqual(findViolations("a.java", block).map((v) => v.rule), ["long-comment"]);
});

test("flags claim words in strings and docs", () => {
  assert.deepEqual(findViolations("README.md", "This makes PDFs compliant.").map((v) => v.rule), ["claim-word"]);
});

test("clean code passes", () => {
  assert.deepEqual(findViolations("a.cs", "// Retry once: the first read can race the rename.\nvar x = 1;\n"), []);
});

const rulesOf = (file, text) => findViolations(file, text).map((v) => v.rule);
const script = fileURLToPath(new URL("./check-source-hygiene.mjs", import.meta.url));

test("markdown headings and list lines are not comment blocks", () => {
  const headings = Array.from({ length: 8 }, (_, i) => `# Heading ${i}`).join("\n");
  const stars = Array.from({ length: 8 }, (_, i) => `*item ${i}`).join("\n");
  assert.deepEqual(rulesOf("a.md", headings), []);
  assert.deepEqual(rulesOf("a.md", stars), []);
  assert.deepEqual(rulesOf("a.css", Array.from({ length: 8 }, (_, i) => `#id${i} {}`).join("\n")), []);
});

test("hash comment blocks are flagged only in hash-comment files", () => {
  const block = Array.from({ length: 7 }, (_, i) => `# line ${i}`).join("\n");
  assert.deepEqual(rulesOf("a.yaml", block), ["long-comment"]);
  assert.deepEqual(rulesOf("Dockerfile.worker", block), ["long-comment"]);
});

test("json never gets comment detection", () => {
  const block = Array.from({ length: 8 }, (_, i) => `// line ${i}`).join("\n");
  assert.deepEqual(rulesOf("a.json", block), []);
});

test("technical TLS certificate words pass in code but not in markdown", () => {
  assert.deepEqual(rulesOf("a.cs", "const certificate = loadTlsCertificate();"), []);
  assert.deepEqual(rulesOf("README.md", "Issue a certificate."), ["claim-word"]);
});

test("bare supabase word passes, hosted hostname is flagged", () => {
  assert.deepEqual(rulesOf("a.ts", "// adapter for supabase-like stores"), []);
  assert.deepEqual(rulesOf("a.ts", "const u = 'https://x.supabase.co';"), ["hosted-host"]);
});

test("Dockerfile variants, csproj and friends are scanned", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "hyg-"));
  for (const name of ["Dockerfile.worker", "x.Dockerfile", "a.csproj", "a.props", "a.slnx", "a.kts", "a.gradle", "a.html", "a.css", "a.sql", "a.ps1", "a.cmd", "a.txt"]) {
    writeFileSync(path.join(dir, name), "milestone M3b\n");
  }
  const r = spawnSync(process.execPath, [script, dir], { encoding: "utf8" });
  assert.equal(r.status, 1);
  assert.equal(r.stdout.trim().split("\n").length, 13);
});

test("skip list is anchored on the basename; lockfiles skipped", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "hyg-"));
  writeFileSync(path.join(dir, "pnpm-lock.yaml"), "M3b\n");
  writeFileSync(path.join(dir, "NOTICE"), "M3b\n");
  writeFileSync(path.join(dir, "LICENSE-notes.md"), "M3b\n");
  const r = spawnSync(process.execPath, [script, dir], { encoding: "utf8" });
  assert.equal(r.stdout.trim().split("\n").length, 1);
  assert.match(r.stdout, /LICENSE-notes\.md:1: milestone/);
});

test("missing path exits 2 with a message", () => {
  const r = spawnSync(process.execPath, [script, "no/such/path"], { encoding: "utf8" });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /no\/such\/path: not found/);
});
