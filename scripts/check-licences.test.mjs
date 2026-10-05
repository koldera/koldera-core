import test from "node:test";
import assert from "node:assert/strict";
import { evaluate, normalizeLicence, parseNuspecLicence, parsePomLicences } from "./check-licences.mjs";

const entry = (name, license, ecosystem = "npm") => ({ ecosystem, name, version: "1.0.0", license });

test("MIT and Apache-2.0 pass, GPL-2.0-only is reported", () => {
  const bad = evaluate([entry("a", "MIT"), entry("b", "Apache-2.0"), entry("c", "GPL-2.0-only")]);
  assert.deepEqual(bad.map((v) => v.name), ["c"]);
  assert.equal(bad[0].reason, "licence not allowed: GPL-2.0-only");
});

test("an OR expression passes when one alternative is allowed, AND needs all", () => {
  assert.deepEqual(evaluate([entry("a", "(MIT OR GPL-3.0-only)")]), []);
  assert.deepEqual(evaluate([entry("a", "MIT AND GPL-3.0-only")]).map((v) => v.name), ["a"]);
  assert.deepEqual(evaluate([entry("a", "(MIT AND Zlib) OR Apache-2.0")]), []);
});

test("missing or unknown licences fail, a reviewed exception passes", () => {
  assert.equal(evaluate([entry("a", "")])[0].reason, "licence unknown");
  assert.equal(evaluate([entry("a", "SEE LICENSE IN LICENSE.txt")])[0].reason, "licence not allowed: SEE LICENSE IN LICENSE.txt");
  assert.deepEqual(evaluate([entry("a", "")], { exceptions: { "npm:a": "reviewed: MIT text" } }), []);
});

test("free-form names and URLs from POMs are normalised", () => {
  assert.equal(normalizeLicence("The Apache Software License, Version 2.0"), "Apache-2.0");
  assert.equal(normalizeLicence("https://www.apache.org/licenses/LICENSE-2.0.txt"), "Apache-2.0");
  assert.equal(normalizeLicence("Eclipse Public License v2.0"), "EPL-2.0");
  assert.equal(normalizeLicence("MIT-0"), "MIT-0");
});

test("pom licences are read from the pom text", () => {
  const pom = "<project><licenses><license><name>The MIT License</name><url>https://x</url></license></licenses></project>";
  assert.deepEqual(parsePomLicences(pom), ["The MIT License"]);
  assert.deepEqual(parsePomLicences("<project></project>"), []);
});

test("nuspec licence expression and url forms", () => {
  assert.equal(parseNuspecLicence('<package><metadata><license type="expression">MIT</license></metadata></package>'), "MIT");
  assert.equal(parseNuspecLicence("<package><metadata><licenseUrl>https://licenses.nuget.org/MIT</licenseUrl></metadata></package>"), "MIT");
  assert.equal(parseNuspecLicence("<package><metadata></metadata></package>"), "");
});
