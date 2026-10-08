import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { findSecrets, parseArgs } from "./package-release.mjs";

test("parseArgs accepts release tags and options, rejects anything else", () => {
  assert.deepEqual(parseArgs(["v1.2.3"]), { tag: "v1.2.3", out: "dist/release", image: null, skipImage: false });
  assert.equal(parseArgs(["v0.1.0-rc.1", "--skip-image", "--out", "x"]).out, "x");
  assert.equal(parseArgs(["v1.0.0", "--image", "koldera-core:ci"]).image, "koldera-core:ci");
  for (const bad of [[], ["1.2.3"], ["v1.2"], ["latest"], ["v1.2.3", "--nope"], ["v1.2.3", "--image"]]) {
    assert.throws(() => parseArgs(bad), undefined, JSON.stringify(bad));
  }
});

test("findSecrets flags credential file names and private keys, not ordinary files", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "pkg-secrets-"));
  try {
    mkdirSync(path.join(dir, "a", "b"), { recursive: true });
    writeFileSync(path.join(dir, "README.md"), "no secrets; the word PRIVATE KEY alone is fine\n");
    writeFileSync(path.join(dir, "a", ".env"), "TOKEN=x\n");
    writeFileSync(path.join(dir, "a", "b", "server.pem"), "x\n");
    writeFileSync(path.join(dir, "a", "b", "key.txt"), "-----BEGIN RSA " + "PRIVATE KEY-----\nabc\n");
    assert.deepEqual(findSecrets(dir), ["a/.env: secret-like file name", "a/b/key.txt: private key", "a/b/server.pem: secret-like file name"]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
