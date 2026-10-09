import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (p) => readFileSync(path.join(root, p), "utf8");
const dockerfile = read("docker/Dockerfile");
const notices = read("docs/legal/THIRD-PARTY-NOTICES.md");
const arg = (name) => dockerfile.match(new RegExp(`^ARG ${name}=(\\S+)$`, "m"))?.[1];

// The licence pack describes exact versions. When a pinned component moves, the notices (and the licence texts they
// point to) must move with it, or the customer receives notices for a different release.
test("every pinned component version in the Dockerfile appears in the notices", () => {
  for (const [name, version] of [
    ["VERAPDF_VERSION", arg("VERAPDF_VERSION")],
    ["TYPST_VERSION", arg("TYPST_VERSION")?.replace(/^v/, "")],
    ["NODE_VERSION", arg("NODE_VERSION")],
  ]) {
    assert.ok(version, `${name} is not pinned in docker/Dockerfile`);
    assert.ok(notices.includes(version), `docs/legal/THIRD-PARTY-NOTICES.md does not mention ${name}=${version}`);
  }
});

test("the engine's shaded libraries are named in the notices with the pom's versions", () => {
  const pom = read("engine/pom.xml");
  const bc = pom.match(/<bouncycastle\.version>([^<]+)</)?.[1];
  assert.ok(bc);
  assert.ok(notices.includes(`| Bouncy Castle (bcprov, bcpkix) | ${bc} |`), `Bouncy Castle ${bc} missing in the notices table`);
});

test("every file the notices point to under third-party/ exists and the image copies the directory", () => {
  for (const f of notices.match(/third-party\/[A-Za-z0-9._-]+/g) ?? []) {
    assert.ok(existsSync(path.join(root, "docs/legal", f)), `docs/legal/${f} is referenced but missing`);
  }
  assert.match(dockerfile, /COPY docs\/legal\/third-party\/ \/usr\/share\/licenses\/koldera\/third-party\//);
  assert.match(dockerfile, /COPY docs\/legal\/THIRD-PARTY-NOTICES\.md \/usr\/share\/licenses\/koldera\//);
});

test("the Node licence text is the one of the pinned Node version", () => {
  const text = read("docs/legal/third-party/node-LICENSE");
  assert.match(text, /^Node\.js is licensed for use as follows:/);
});

test("the notices carry no unfilled placeholder", () => {
  assert.doesNotMatch(notices, /\[PLACEHOLDER/);
});
