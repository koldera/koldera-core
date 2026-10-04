import { execFile } from "node:child_process";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { describe, expect, it } from "vitest";
import { validateReorderResult, validateResult, validateSemanticResult } from "../src/contracts/types.js";

const run = promisify(execFile);
const cli = path.resolve("dist", "cli.js");

const request = (overrides: Record<string, unknown> = {}) => ({
  specVersion: "1.0",
  jobId: "123e4567-e89b-42d3-a456-426614174000",
  inputPath: "/work/in/source.html",
  outputDirectory: "/work/out",
  limits: { timeoutSeconds: 30, maxBytes: 1048576, maxPages: 5, maxInlineImageBytes: 65536, maxTotalInlineImageBytes: 131072 },
  page: { format: "A4", printBackground: true },
  ...overrides,
});

async function workspace(html: string) {
  const dir = await mkdtemp(path.join(tmpdir(), "koldera-cli-"));
  await mkdir(path.join(dir, "in"));
  await mkdir(path.join(dir, "out"));
  await writeFile(path.join(dir, "in", "source.html"), html);
  return dir;
}

/** Exercises the packaged CLI (requires `pnpm build`). */
describe("cli", () => {
  it("renders a static document and writes a schema-valid completed result, exit 0, no paths or content in logs", async () => {
    const dir = await workspace(`<!doctype html><html lang="en"><head><title>CLI secret-title</title></head><body><h1>Hello secret-body</h1></body></html>`);
    await writeFile(path.join(dir, "request.json"), JSON.stringify(request()));

    const { stderr } = await run(process.execPath, [cli, "render", "--request", path.join(dir, "request.json"), "--result", path.join(dir, "result.json"), "--work-root", dir]);

    const result = JSON.parse(await readFile(path.join(dir, "result.json"), "utf8"));
    expect(validateResult(result)).toEqual([]);
    expect(result.status).toBe("completed");
    expect(result.artifacts[0].kind).toBe("generated_pdf");
    expect((await readFile(path.join(dir, "out", "generated.pdf"))).length).toBe(result.artifacts[0].bytes);
    expect(stderr).toContain('"event":"result_written"');
    expect(stderr).not.toContain("secret");
    expect(stderr).not.toContain(dir);
  });

  it("writes an unsupported result for refused content and exits 0", async () => {
    const dir = await workspace(`<!doctype html><html lang="en"><head><title>x</title></head><body><img src="data:image/png;base64,${Buffer.alloc(70 * 1024, 1).toString("base64")}" alt="big"></body></html>`);
    await writeFile(path.join(dir, "request.json"), JSON.stringify(request()));

    await run(process.execPath, [cli, "render", "--request", path.join(dir, "request.json"), "--result", path.join(dir, "result.json"), "--work-root", dir]);

    const result = JSON.parse(await readFile(path.join(dir, "result.json"), "utf8"));
    expect(result.status).toBe("unsupported");
    expect(result.error.code).toBe("UNSUPPORTED_CONTENT");
    expect(result.diagnostics[0].code).toBe("HTML_INLINE_IMAGE_TOO_LARGE");
    expect(result.artifacts).toEqual([]);
  });

  it("runs the semantic and reorder verbs through the same envelope", async () => {
    const html = `<!doctype html><html lang="en"><head><title>x</title></head><body><div style="display:flex"><p style="order:2">A</p><p style="order:1">B</p></div></body></html>`;
    const dir = await workspace(html);
    const semantic = { specVersion: "1.0", jobId: "123e4567-e89b-42d3-a456-426614174000", inputPath: "/work/in/source.html", limits: { timeoutSeconds: 30, maxBytes: 1048576, maxNodes: 5000, maxInlineImageBytes: 65536, maxTotalInlineImageBytes: 131072 } };
    await writeFile(path.join(dir, "semantic.json"), JSON.stringify(semantic));
    await run(process.execPath, [cli, "semantic", "--request", path.join(dir, "semantic.json"), "--result", path.join(dir, "semantic-result.json"), "--work-root", dir]);
    const collected = JSON.parse(await readFile(path.join(dir, "semantic-result.json"), "utf8"));
    expect(validateSemanticResult(collected)).toEqual([]);
    expect(collected.status).toBe("completed");
    expect(collected.findings.some((f: { code: string }) => f.code === "CSS_ORDER_REORDERS_SIBLINGS")).toBe(true);

    const { createHash } = await import("node:crypto");
    const reorder = { specVersion: "1.0", jobId: "123e4567-e89b-42d3-a456-426614174000", inputPath: "/work/in/source.html", outputDirectory: "/work/out", baseSha256: createHash("sha256").update(html).digest("hex"), parentPath: "1/0", orderedChildIndexes: [1, 0], limits: { timeoutSeconds: 60, maxBytes: 1048576, maxPages: 5, maxInlineImageBytes: 65536, maxTotalInlineImageBytes: 131072, maxVisualDiffRatio: 0.02 }, page: { format: "A4", printBackground: true } };
    await writeFile(path.join(dir, "reorder.json"), JSON.stringify(reorder));
    const { stderr } = await run(process.execPath, [cli, "reorder", "--request", path.join(dir, "reorder.json"), "--result", path.join(dir, "reorder-result.json"), "--work-root", dir]);
    const applied = JSON.parse(await readFile(path.join(dir, "reorder-result.json"), "utf8"));
    expect(validateReorderResult(applied)).toEqual([]);
    expect(applied.status).toBe("applied");
    expect(applied.artifacts.map((a: { kind: string }) => a.kind).sort()).toEqual(["generated_pdf", "source_html"]);
    expect(stderr).toContain('"verb":"reorder"');
    expect(stderr).not.toContain(dir);
  });

  it("rejects an invalid request with a failed result, exit 0, and a usage error with exit 2", async () => {
    const dir = await workspace("<p>x</p>");
    await writeFile(path.join(dir, "request.json"), JSON.stringify(request({ page: { format: "Tabloid" } })));

    await run(process.execPath, [cli, "render", "--request", path.join(dir, "request.json"), "--result", path.join(dir, "result.json"), "--work-root", dir]);
    const result = JSON.parse(await readFile(path.join(dir, "result.json"), "utf8"));
    expect(result.status).toBe("failed");
    expect(result.error.code).toBe("INVALID_INPUT");

    await expect(run(process.execPath, [cli, "scan"])).rejects.toMatchObject({ code: 2 });
  });
});
