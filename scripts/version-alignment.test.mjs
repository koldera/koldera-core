import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (p) => readFileSync(path.join(root, p), "utf8");

// The release tag overrides all three at image build time; without it they must agree, so /v1/info and the label match.
test("server, engine and image default to the same version", () => {
  const server = read("server/Directory.Build.props").match(/<Version>([^<]+)<\/Version>/)?.[1];
  const engine = read("engine/pom.xml").match(/<artifactId>koldera-pdf-engine<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
  const image = read("docker/Dockerfile").match(/^ARG KOLDERA_VERSION=(\S+)$/m)?.[1];
  assert.ok(server, "server/Directory.Build.props has no <Version>");
  assert.equal(engine, server);
  assert.equal(image, server);
});
