import { createHash } from "node:crypto";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { validateRequest, validateReorderRequest, validateReorderResult, validateResult, validateSemanticRequest, validateSemanticResult, type HtmlRendererRequest, type HtmlReorderRequest, type HtmlSemanticRequest } from "../src/contracts/types.js";
import { navigationTimeout } from "../src/limits.js";
import { reorderSource } from "../src/remediation/reorder.js";
import { render } from "../src/render/renderer.js";
import { collectSemantic } from "../src/semantic/semantic.js";

const JOB = "123e4567-e89b-42d3-a456-426614174000";
const LONG = `<!doctype html><html lang="en"><head><title>Long</title><style>section { page-break-after: always; height: 10cm }</style></head><body>${"<section><p>page</p></section>".repeat(12)}</body></html>`;
const ORDER = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Order</title><style>body{margin:0}</style></head><body><main><h1>Steps</h1><div style="display:flex;gap:16px"><p style="order:3">First in DOM</p><p style="order:2">Second in DOM</p><p style="order:1">Third in DOM</p></div></main></body></html>`;

async function workspace(html: string): Promise<string> {
  const root = await mkdtemp(path.join(tmpdir(), "koldera-limits-"));
  await mkdir(path.join(root, "in"));
  await mkdir(path.join(root, "out"));
  await writeFile(path.join(root, "in", "source.html"), html);
  return root;
}

describe("limits are optional", () => {
  it("navigationTimeout maps an unbounded budget to Playwright's 0 (no timeout) and keeps a finite one", () => {
    expect(navigationTimeout(Infinity)).toBe(0);
    expect(navigationTimeout(30_000)).toBe(30_000);
  });

  it("a render request without limits is schema-valid, completes and applies no page cap", async () => {
    const request: HtmlRendererRequest = { specVersion: "1.0", jobId: JOB, inputPath: "/work/in/source.html", outputDirectory: "/work/out", page: { format: "A4" } };
    expect(validateRequest(request)).toEqual([]);
    const root = await workspace(LONG);
    const result = await render(request, { workRoot: root });
    expect(validateResult(result), JSON.stringify(result)).toEqual([]);
    expect(result.status, JSON.stringify(result.error)).toBe("completed");
    expect(result.pages).toBeGreaterThan(10);
  });

  it("a limits object without timeoutSeconds still applies the other limits", async () => {
    const request: HtmlRendererRequest = { specVersion: "1.0", jobId: JOB, inputPath: "/work/in/source.html", outputDirectory: "/work/out", limits: { maxPages: 3 }, page: { format: "A4" } };
    expect(validateRequest(request)).toEqual([]);
    const result = await render(request, { workRoot: await workspace(LONG) });
    expect(result.status).toBe("failed");
    expect(result.error?.code).toBe("LIMIT_EXCEEDED");
  });

  it("a semantic request without limits completes", async () => {
    const request: HtmlSemanticRequest = { specVersion: "1.0", jobId: JOB, inputPath: "/work/in/source.html" };
    expect(validateSemanticRequest(request)).toEqual([]);
    const result = await collectSemantic(request, { workRoot: await workspace(ORDER) });
    expect(validateSemanticResult(result), JSON.stringify(result.error)).toEqual([]);
    expect(result.status, JSON.stringify(result.error)).toBe("completed");
  });

  it("a reorder request without limits applies the approved order (default visual tolerance stays 0.02)", async () => {
    const request: HtmlReorderRequest = {
      specVersion: "1.0",
      jobId: JOB,
      inputPath: "/work/in/source.html",
      outputDirectory: "/work/out",
      baseSha256: createHash("sha256").update(ORDER).digest("hex"),
      parentPath: "1/0/1",
      orderedChildIndexes: [2, 1, 0],
      page: { format: "A4" },
    };
    expect(validateReorderRequest(request)).toEqual([]);
    const result = await reorderSource(request, { workRoot: await workspace(ORDER) });
    expect(validateReorderResult(result), JSON.stringify(result.error)).toEqual([]);
    expect(result.status, JSON.stringify(result.error)).toBe("applied");
  });
});
