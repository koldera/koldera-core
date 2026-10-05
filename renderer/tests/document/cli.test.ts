import { execFile } from "node:child_process";
import { cp, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { describe, expect, it } from "vitest";
import { validateDocumentRenderResult } from "../../src/contracts/types.js";

const run = promisify(execFile);
const cli = path.resolve("dist", "cli.js");
const typst = process.env["TYPST_CMD"];

async function workspace() {
  const dir = await mkdtemp(path.join(tmpdir(), "koldera-doccli-"));
  await mkdir(path.join(dir, "in", "images"), { recursive: true });
  await mkdir(path.join(dir, "out"));
  await cp(path.join(import.meta.dirname, "fixtures", "invoice.json"), path.join(dir, "in", "document.json"));
  return dir;
}

const req = (extra: Record<string, unknown> = {}) => ({
  specVersion: "1.0",
  jobId: "123e4567-e89b-42d3-a456-426614174000",
  documentPath: "/work/in/document.json",
  imagesDirectory: "/work/in/images",
  outputDirectory: "/work/out",
  typstCommand: typst ?? "typst",
  ...extra,
});

/** Exercises the packaged CLI (requires `pnpm build`). */
describe("cli document verb", () => {
  it("answers a request that violates the schema with a failed INVALID_INPUT result and exit 0", async () => {
    const dir = await workspace();
    await writeFile(path.join(dir, "request.json"), JSON.stringify(req({ documentPath: "/etc/passwd" })));
    await run(process.execPath, [cli, "document", "--request", path.join(dir, "request.json"), "--result", path.join(dir, "result.json"), "--work-root", dir]);
    const result = JSON.parse(await readFile(path.join(dir, "result.json"), "utf8"));
    expect(validateDocumentRenderResult(result)).toEqual([]);
    expect(result.status).toBe("failed");
    expect(result.error.code).toBe("INVALID_INPUT");
  });

  it.skipIf(!typst)("renders the invoice and writes a completed result without paths in the logs", async () => {
    const dir = await workspace();
    await writeFile(path.join(dir, "request.json"), JSON.stringify(req()));
    const { stderr } = await run(process.execPath, [cli, "document", "--request", path.join(dir, "request.json"), "--result", path.join(dir, "result.json"), "--work-root", dir]);
    const result = JSON.parse(await readFile(path.join(dir, "result.json"), "utf8"));
    expect(validateDocumentRenderResult(result)).toEqual([]);
    expect(result.status).toBe("completed");
    expect(result.artifacts[0].path).toBe("/work/out/document.pdf");
    expect(stderr).toContain('"event":"result_written"');
    expect(stderr).not.toContain(dir);
  });
});
