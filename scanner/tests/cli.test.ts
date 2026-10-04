import { execFile } from "node:child_process";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { describe, expect, it } from "vitest";
import { validateResult } from "../src/contracts/types.js";

const run = promisify(execFile);
const cli = path.resolve("dist", "cli.js");

/** Exercises the packaged CLI (requires `pnpm build`); loopback is never allowed through the CLI. */
describe("cli", () => {
  it("writes a schema-valid unsupported result for a blocked destination and exits 0", async () => {
    const dir = await mkdtemp(path.join(tmpdir(), "koldera-cli-"));
    const request = { specVersion: "1.0", jobId: "123e4567-e89b-42d3-a456-426614174000", url: "https://169.254.169.254/latest/meta-data/", outputDirectory: "/work/out", limits: { timeoutSeconds: 10, maxResponseBytes: 65536, maxRequests: 10 }, policy: { allowHttp: false } };
    await writeFile(path.join(dir, "request.json"), JSON.stringify(request));

    const { stderr } = await run(process.execPath, [cli, "scan", "--request", path.join(dir, "request.json"), "--result", path.join(dir, "result.json"), "--work-root", dir]);

    const result = JSON.parse(await readFile(path.join(dir, "result.json"), "utf8"));
    expect(validateResult(result)).toEqual([]);
    expect(result.status).toBe("unsupported");
    expect(result.error.code).toBe("BLOCKED_DESTINATION");
    expect(stderr).toContain('"event":"result_written"');
    expect(stderr).not.toContain("169.254");
    expect(stderr).not.toContain(dir);
  });

  it("rejects an invalid request with a failed result, exit 0, and a usage error with exit 2", async () => {
    const dir = await mkdtemp(path.join(tmpdir(), "koldera-cli-"));
    await writeFile(path.join(dir, "request.json"), JSON.stringify({ specVersion: "1.0", jobId: "123e4567-e89b-42d3-a456-426614174000", url: "javascript:alert(1)" }));

    await run(process.execPath, [cli, "scan", "--request", path.join(dir, "request.json"), "--result", path.join(dir, "result.json"), "--work-root", dir]);
    const result = JSON.parse(await readFile(path.join(dir, "result.json"), "utf8"));
    expect(result.status).toBe("failed");
    expect(result.error.code).toBe("INVALID_INPUT");

    await expect(run(process.execPath, [cli, "serve"])).rejects.toMatchObject({ code: 2 });
  });
});
