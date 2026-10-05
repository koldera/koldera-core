import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
// Binary and document fixtures under a test or fixture directory; source files (.ts, .cs, .java) are not fixtures.
const FIXTURE = /(^|\/)(tests?|fixtures?|Fixtures)\/.*\.(pdf|png|jpe?g|gif|webp|svg|html?|json)$/i;

export function unlistedFixtures(trackedPaths, fixturesDoc) {
  return trackedPaths.filter((p) => FIXTURE.test(p) && !fixturesDoc.includes("`" + p + "`"));
}

test("every tracked fixture is listed in docs/fixtures.md with its full path", () => {
  const tracked = execFileSync("git", ["ls-files"], { cwd: root, encoding: "utf8" }).split("\n").filter(Boolean);
  const doc = readFileSync(path.join(root, "docs", "fixtures.md"), "utf8");
  assert.ok(tracked.some((p) => FIXTURE.test(p)), "no fixture found: the pattern or the checkout is wrong");
  assert.deepEqual(unlistedFixtures(tracked, doc), []);
});

test("the check catches a new fixture and ignores test sources", () => {
  const doc = "| `renderer/tests/document/fixtures/logo.png` | logo |";
  assert.deepEqual(
    unlistedFixtures(["renderer/tests/document/fixtures/logo.png", "renderer/tests/document/fixtures/new.png", "server/tests/X/Fixtures/a.json", "renderer/tests/render.test.ts", "contracts/openapi.yaml"], doc),
    ["renderer/tests/document/fixtures/new.png", "server/tests/X/Fixtures/a.json"],
  );
});
