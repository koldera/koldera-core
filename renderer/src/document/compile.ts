import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** Bundled Noto fonts; KOLDERA_FONTS_DIR lets a container put them elsewhere. */
export const fontsDir = process.env["KOLDERA_FONTS_DIR"] ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "fonts");

export type CompileOutcome = { ok: true } | { ok: false; code: "DOCUMENT_REFUSED" | "TIMEOUT" | "WORKER_ERROR"; message: string };

/** Nothing but PATH (and what Windows needs to start a process) reaches Typst: no package path, no proxy, no secrets. */
function typstEnv(): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { PATH: process.env["PATH"] ?? "" };
  if (process.platform === "win32") {
    for (const k of ["SystemRoot", "TEMP", "TMP"]) if (process.env[k]) env[k] = process.env[k];
  }
  return env;
}

interface RunResult {
  code: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
  spawnFailed: boolean;
}

function run(command: string, args: string[], cwd: string, timeoutMs: number): Promise<RunResult> {
  return new Promise((resolve) => {
    execFile(command, args, { cwd, env: typstEnv(), timeout: timeoutMs, maxBuffer: 1 << 20, windowsHide: true, shell: false }, (error, stdout, stderr) => {
      const e = error as (Error & { killed?: boolean; code?: number | string }) | null;
      resolve({
        code: e ? (typeof e.code === "number" ? e.code : null) : 0,
        stdout: String(stdout),
        stderr: String(stderr),
        timedOut: Boolean(e?.killed),
        spawnFailed: e !== null && typeof e.code === "string",
      });
    });
  });
}

/** First line of `typst --version`, or "unknown" when Typst cannot be started. */
export async function typstVersion(command: string, cwd: string): Promise<string> {
  const r = await run(command, ["--version"], cwd, 15_000);
  const line = r.stdout.split(/\r?\n/)[0]?.trim();
  return r.code === 0 && line ? line : "unknown";
}

/** Keeps Typst's first error line, without host paths. */
export function firstErrorLine(stderr: string, workRoot: string): string {
  const lines = stderr.split(/\r?\n/);
  let msg = lines.find((l) => l.startsWith("error:")) ?? lines.find((l) => l.trim()) ?? "Typst failed";
  const abs = path.resolve(workRoot);
  const roots = [workRoot, abs, "\\\\?\\" + abs].flatMap((r) => [r, r.replaceAll("\\", "/")]);
  for (const r of new Set(roots)) msg = msg.split(r).join("/work");
  return msg.replace(/[A-Za-z]:[\\/][^\s)]*/g, "<path>").slice(0, 300);
}

/**
 * typst compile as PDF/UA-1 with only the bundled fonts (system and embedded fonts ignored, so the
 * result does not depend on the host), the work root as the only readable root, and a fixed
 * creation timestamp so the same document gives the same bytes.
 */
export async function compileTypst(options: { command: string; workRoot: string; source: string; output: string; timeoutMs?: number }): Promise<CompileOutcome> {
  const args = [
    "compile",
    "--pdf-standard", "ua-1",
    "--ignore-system-fonts",
    "--ignore-embedded-fonts",
    "--font-path", fontsDir,
    "--root", options.workRoot,
    "--creation-timestamp", "0",
    options.source,
    options.output,
  ];
  const r = await run(options.command, args, options.workRoot, options.timeoutMs ?? 120_000);
  if (r.timedOut) return { ok: false, code: "TIMEOUT", message: "Typst did not finish in time" };
  if (r.spawnFailed) return { ok: false, code: "WORKER_ERROR", message: "Typst could not be started" };
  if (r.code !== 0) return { ok: false, code: "DOCUMENT_REFUSED", message: firstErrorLine(r.stderr, options.workRoot) };
  return { ok: true };
}
