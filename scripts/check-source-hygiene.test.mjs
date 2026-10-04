import test from "node:test";
import assert from "node:assert/strict";
import { findViolations } from "./check-source-hygiene.mjs";

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
