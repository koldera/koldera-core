import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { chromium } from "playwright";
import { afterEach, describe, expect, it, vi } from "vitest";
import { validateRequest, validateReorderRequest, validateSemanticRequest } from "../src/contracts/types.js";
import { render } from "../src/render/renderer.js";
import { collectSemantic } from "../src/semantic/semantic.js";

const JOB = "123e4567-e89b-42d3-a456-426614174000";
const HTML = `<!doctype html><html lang="en"><head><title>T</title></head><body><main><h1>T</h1><p>x</p></main></body></html>`;

async function workspace(): Promise<string> {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-sandbox-"));
  await mkdir(path.join(root, "in"));
  await writeFile(path.join(root, "in", "source.html"), HTML);
  return root;
}

/** Records the launch options and stops there: only the option is under test, not a started browser. */
function spyOnLaunch() {
  return vi.spyOn(chromium, "launch").mockRejectedValue(new Error("launch intercepted"));
}

afterEach(() => vi.restoreAllMocks());

describe("policy.chromiumSandbox", () => {
  it("is optional and accepted by the render, semantic and reorder request schemas", () => {
    const render = { specVersion: "1.0", jobId: JOB, inputPath: "/work/in/source.html", outputDirectory: "/work/out", page: { format: "A4" } };
    expect(validateRequest({ ...render, policy: { chromiumSandbox: true } })).toEqual([]);
    expect(validateRequest({ ...render, policy: {} })).toEqual([]);
    expect(validateRequest({ ...render, policy: { chromiumSandbox: "yes" } })).not.toEqual([]);
    expect(validateRequest({ ...render, policy: { other: true } })).not.toEqual([]);
    expect(validateSemanticRequest({ specVersion: "1.0", jobId: JOB, inputPath: "/work/in/source.html", policy: { chromiumSandbox: false } })).toEqual([]);
    expect(validateReorderRequest({ ...render, baseSha256: "a".repeat(64), parentPath: "/0", orderedChildIndexes: [1, 0], policy: { chromiumSandbox: true } })).toEqual([]);
  });

  it("render launches with chromiumSandbox false by default and true when the policy says so", async () => {
    const spy = spyOnLaunch();
    const base = { specVersion: "1.0", jobId: JOB, inputPath: "/work/in/source.html", outputDirectory: "/work/out", page: { format: "A4" } } as const;
    await render({ ...base }, { workRoot: await workspace() });
    await render({ ...base, policy: { chromiumSandbox: true } }, { workRoot: await workspace() });
    expect(spy.mock.calls.map((c) => c[0]?.chromiumSandbox)).toEqual([false, true]);
  });

  it("the semantic collector passes the policy to the launch too", async () => {
    const spy = spyOnLaunch();
    const base = { specVersion: "1.0", jobId: JOB, inputPath: "/work/in/source.html" } as const;
    await collectSemantic({ ...base }, { workRoot: await workspace() });
    await collectSemantic({ ...base, policy: { chromiumSandbox: true } }, { workRoot: await workspace() });
    expect(spy.mock.calls.map((c) => c[0]?.chromiumSandbox)).toEqual([false, true]);
  });
});
