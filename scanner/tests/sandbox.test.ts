import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { chromium } from "playwright";
import { afterEach, describe, expect, it, vi } from "vitest";
import { validateRequest, type WebWorkerRequest } from "../src/contracts/types.js";
import { scan } from "../src/scan/scanner.js";

const base: WebWorkerRequest = { specVersion: "1.0", jobId: "123e4567-e89b-42d3-a456-426614174000", url: "http://127.0.0.1:9/", outputDirectory: "/work/out", policy: { allowHttp: true } };

afterEach(() => vi.restoreAllMocks());

describe("policy.chromiumSandbox", () => {
  it("is optional in the request schema and must be a boolean", () => {
    expect(validateRequest({ ...base, policy: { allowHttp: true, chromiumSandbox: true } })).toEqual([]);
    expect(validateRequest(base)).toEqual([]);
    expect(validateRequest({ ...base, policy: { allowHttp: true, chromiumSandbox: 1 } })).not.toEqual([]);
  });

  it("the launch option equals the policy value, false when absent", async () => {
    const spy = vi.spyOn(chromium, "launch").mockRejectedValue(new Error("launch intercepted"));
    const outputDirectory = await mkdtemp(path.join(tmpdir(), "koldera-scan-sandbox-"));
    for (const policy of [{ allowHttp: true }, { allowHttp: true, chromiumSandbox: true }, { allowHttp: true, chromiumSandbox: false }]) {
      await scan({ ...base, policy }, { outputDirectory, allowLoopbackForTests: true }).catch(() => undefined);
    }
    expect(spy.mock.calls.map((c) => c[0]?.chromiumSandbox)).toEqual([false, true, false]);
  });
});
