import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const profile = JSON.parse(readFileSync(path.join(root, "docker/seccomp-chromium.json"), "utf8"));
const unconditionalAllow = new Set(
  profile.syscalls.filter((r) => r.action === "SCMP_ACT_ALLOW" && !r.args && !r.includes && !r.excludes).flatMap((r) => r.names),
);

// docker/seccomp-chromium.json is Docker's default profile plus one rule; keep it that way, so a reviewer can diff it
// against moby/profiles seccomp/default.json and see only what the sandbox needs.
test("denies by default and allows what Chromium's user-namespace sandbox needs", () => {
  assert.equal(profile.defaultAction, "SCMP_ACT_ERRNO");
  for (const name of ["clone", "unshare", "setns", "chroot"]) assert.ok(unconditionalAllow.has(name), `${name} is not allowed`);
});

test("everything else that Docker gates behind a capability stays gated", () => {
  for (const name of ["mount", "umount2", "pivot_root", "reboot", "kexec_load", "init_module", "finit_module", "delete_module", "swapon", "swapoff", "settimeofday", "bpf", "perf_event_open", "open_by_handle_at"]) {
    assert.ok(!unconditionalAllow.has(name), `${name} must not be allowed without a capability`);
  }
});

test("the compose override turns the sandbox on with that profile", () => {
  const compose = readFileSync(path.join(root, "docker/compose.sandbox.yaml"), "utf8");
  assert.match(compose, /KOLDERA_CHROMIUM_SANDBOX: "true"/);
  assert.match(compose, /seccomp=\.\/seccomp-chromium\.json/);
});
