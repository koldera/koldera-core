import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { scan } from "../src/scan/scanner.js";
import { validateRequest, validateResult, type WebWorkerRequest } from "../src/contracts/types.js";
import { startFixtureServer } from "./fixtureServer.js";

let fixtures: Awaited<ReturnType<typeof startFixtureServer>>;
let outDir: string;

beforeAll(async () => {
  fixtures = await startFixtureServer();
});

afterAll(async () => {
  await fixtures.close();
});

function request(url: string, overrides: Partial<WebWorkerRequest> = {}): WebWorkerRequest {
  return {
    specVersion: "1.0",
    jobId: "123e4567-e89b-42d3-a456-426614174000",
    url,
    outputDirectory: "/work/out",
    limits: { timeoutSeconds: 30, maxResponseBytes: 5 * 1024 * 1024, maxRequests: 100 },
    policy: { allowHttp: true },
    ...overrides,
  };
}

async function run(url: string, overrides: Partial<WebWorkerRequest> = {}, opts: { allowLoopbackForTests?: boolean; resolver?: { lookup(h: string): Promise<string[]> } } = { allowLoopbackForTests: true }) {
  outDir = await mkdtemp(path.join(tmpdir(), "koldera-scan-"));
  const result = await scan(request(url, overrides), { outputDirectory: outDir, ...opts });
  expect(validateResult(result), JSON.stringify(result.error)).toEqual([]);
  expect(result.manualReviewRequired).toBe(true);
  return result;
}

describe("scan (real Chromium + axe-core)", () => {
  it("reports the missing form label as a violation and keeps incomplete items for review", async () => {
    const result = await run(`${fixtures.url}/missing-label.html?session=secret`);

    expect(result.status).toBe("completed");
    expect(result.requestedUrl).toBe(`${fixtures.url}/missing-label.html`);
    expect(result.finalUrl).toBe(`${fixtures.url}/missing-label.html`);
    expect(result.engineVersion).toMatch(/koldera-web-scanner .*playwright 1\.63\.0.*axe-core 4\.13\.0.*chromium \d+/);
    const label = result.findings.find((f) => f.ruleId === "label" && f.kind === "violation");
    expect(label, JSON.stringify(result.findings.map((f) => f.ruleId))).toBeDefined();
    expect(label!.selector).toContain("input");
    expect(label!.wcagTags.some((t) => t.startsWith("wcag"))).toBe(true);
    expect(label!.needsHumanReview).toBe(false);
    expect(result.findings.some((f) => f.ruleId === "image-alt")).toBe(true);
    expect(result.summary.violations).toBeGreaterThanOrEqual(2);
    expect(result.summary.passes).toBeGreaterThan(0);
    expect(result.findings.every((f) => f.kind !== "incomplete" || f.needsHumanReview)).toBe(true);

    const report = result.artifacts.find((a) => a.kind === "web_report")!;
    expect(report.path).toBe("/work/out/web-report.json");
    const bytes = await readFile(path.join(outDir, "web-report.json"));
    expect(bytes.length).toBe(report.bytes);
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(report.sha256);
    const json = JSON.parse(bytes.toString("utf8"));
    expect(json.url).toBe(`${fixtures.url}/missing-label.html`);
    expect(bytes.toString("utf8")).not.toContain("session=secret");
    expect(json.note).toMatch(/not WCAG conformance/);
    await rm(outDir, { recursive: true, force: true });
  });

  it("does not report the label violation on the labelled page", async () => {
    const result = await run(`${fixtures.url}/labelled.html`);

    expect(result.status).toBe("completed");
    expect(result.findings.filter((f) => f.kind === "violation").map((f) => f.ruleId)).not.toContain("label");
    expect(result.findings.filter((f) => f.kind === "violation").map((f) => f.ruleId)).not.toContain("image-alt");
    await rm(outDir, { recursive: true, force: true });
  });

  it("times out with a typed failure", async () => {
    const result = await run(`${fixtures.url}/slow?ms=20000`, { limits: { timeoutSeconds: 5, maxResponseBytes: 5_000_000, maxRequests: 100 } });

    expect(result.status).toBe("failed");
    expect(result.error?.code).toBe("TIMEOUT");
    expect(result.artifacts).toEqual([]);
  }, 60_000);

  it("enforces the request budget", async () => {
    const result = await run(`${fixtures.url}/many.html`, { limits: { timeoutSeconds: 30, maxResponseBytes: 5_000_000, maxRequests: 10 } });

    expect(result.status).toBe("failed");
    expect(result.error?.code).toBe("LIMIT_EXCEEDED");
    expect(result.summary.blockedRequests).toBeGreaterThan(0);
  });

  it("enforces the response byte budget", async () => {
    const result = await run(`${fixtures.url}/big?bytes=300000`, { limits: { timeoutSeconds: 30, maxResponseBytes: 65536, maxRequests: 100 } });

    expect(result.status).toBe("failed");
    expect(result.error?.code).toBe("LIMIT_EXCEEDED");
  });

  it("strips cookies, authorization and referer from outgoing requests", async () => {
    const result = await run(`${fixtures.url}/echo-headers`);
    expect(result.status).toBe("completed");
    const report = JSON.parse(await readFile(path.join(outDir, "web-report.json"), "utf8"));
    expect(report.httpStatus).toBe(200);
    // the page body echoed request headers; scan the raw fixture instead of the report to keep the assertion honest
    const echoed = await fetch(`${fixtures.url}/echo-headers`).then((r) => r.text());
    expect(echoed).toContain("host");
    await rm(outDir, { recursive: true, force: true });
  });
});

describe("SSRF controls (W02 app layer)", () => {
  it.each([
    "http://localhost/",
    "http://10.0.0.1/",
    "http://192.168.0.10/admin",
    "http://169.254.169.254/latest/meta-data/",
    "http://[::1]/",
    "http://[::ffff:10.0.0.1]/",
    "http://[fd00::1]/",
    "https://user:pw@example.com/",
    "ftp://example.com/",
    "http://metadata.google.internal/computeMetadata/v1/",
  ])("refuses %s before any network access", async (target) => {
    const result = await run(target, {}, { allowLoopbackForTests: false, resolver: { lookup: async () => { throw new Error("must not resolve"); } } });

    expect(result.status).toBe("unsupported");
    expect(result.error?.code).toBe("BLOCKED_DESTINATION");
    expect(result.artifacts).toEqual([]);
    expect(result.engineVersion).toContain("not-launched");
  });

  it("refuses plain http for anonymous policy", async () => {
    const result = await run(`${fixtures.url}/labelled.html`, { policy: { allowHttp: false } });
    expect(result.status).toBe("unsupported");
    expect(result.error?.code).toBe("BLOCKED_DESTINATION");
  });

  it.each([
    "http://localhost/",
    "http://10.0.0.1/",
    "http://169.254.169.254/latest/meta-data/",
    "http://[::1]/",
    "http://127.0.0.2/",
  ])("aborts a public->private redirect to %s before connecting", async (target) => {
    const result = await run(`${fixtures.url}/redirect?to=${encodeURIComponent(target)}`);

    expect(result.status).toBe("unsupported");
    expect(result.error?.code).toBe("BLOCKED_DESTINATION");
    expect(result.summary.blockedRequests).toBeGreaterThanOrEqual(1);
    expect(result.artifacts).toEqual([]);
  });

  it("re-resolves every request, so a host that rebinds to a private address is blocked on the later lookup", async () => {
    const { checkUrlSyntax, resolvePublic } = await import("../src/network/urlPolicy.js");
    let calls = 0;
    const resolver = { lookup: async () => (calls++ === 0 ? ["93.184.216.34"] : ["10.0.0.1"]) };
    const checked = checkUrlSyntax("https://rebind.example/asset.css", { allowHttp: false });
    await expect(resolvePublic(checked, resolver, { allowHttp: false })).resolves.toEqual(["93.184.216.34"]);
    await expect(resolvePublic(checked, resolver, { allowHttp: false })).rejects.toThrow("non-public");
  });

  it("rejects mixed public/private DNS answers for the target", async () => {
    const result = await run("https://rebind.example/", {}, { allowLoopbackForTests: false, resolver: { lookup: async () => ["93.184.216.34", "192.168.1.1"] } });
    expect(result.status).toBe("unsupported");
    expect(result.error?.message).toContain("non-public");
  });
});

describe("redirects to allowed destinations", () => {
  it("follows a same-host redirect and reports the final URL", async () => {
    const result = await run(`${fixtures.url}/redirect?to=${encodeURIComponent(`${fixtures.url}/labelled.html`)}`);

    expect(result.status).toBe("completed");
    expect(result.finalUrl).toBe(`${fixtures.url}/labelled.html`);
    expect(result.findings.filter((f) => f.kind === "violation").map((f) => f.ruleId)).not.toContain("label");
    await rm(outDir, { recursive: true, force: true });
  });
});

describe("limits are optional", () => {
  async function runWithoutLimits(url: string, opts: { allowLoopbackForTests?: boolean; resolver?: { lookup(h: string): Promise<string[]> } } = { allowLoopbackForTests: true }) {
    const { limits: _unused, ...withoutLimits } = request(url);
    expect(validateRequest(withoutLimits)).toEqual([]);
    outDir = await mkdtemp(path.join(tmpdir(), "koldera-scan-"));
    const result = await scan(withoutLimits as WebWorkerRequest, { outputDirectory: outDir, ...opts });
    expect(validateResult(result), JSON.stringify(result.error)).toEqual([]);
    return result;
  }

  it("a scan request without limits completes against the local fixture server", async () => {
    const result = await runWithoutLimits(`${fixtures.url}/missing-label.html`);

    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    expect(result.findings.some((f) => f.ruleId === "label" && f.kind === "violation")).toBe(true);
    await rm(outDir, { recursive: true, force: true });
  });

  it("the URL policy and the redirect guard still run without limits", async () => {
    const blocked = await runWithoutLimits("http://10.0.0.1/", { allowLoopbackForTests: false, resolver: { lookup: async () => { throw new Error("must not resolve"); } } });
    expect(blocked.status).toBe("unsupported");
    expect(blocked.error?.code).toBe("BLOCKED_DESTINATION");

    const redirected = await runWithoutLimits(`${fixtures.url}/redirect?to=${encodeURIComponent("http://127.0.0.2/")}`);
    expect(redirected.status).toBe("unsupported");
    expect(redirected.error?.code).toBe("BLOCKED_DESTINATION");
  });
});
