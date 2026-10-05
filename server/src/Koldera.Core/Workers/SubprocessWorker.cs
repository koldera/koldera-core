using System.Diagnostics;

namespace Koldera.Core.Workers;

public sealed record WorkerRun(int ExitCode, bool TimedOut, string StderrTail);

/// <summary>Which paths the CLI verb takes: the job envelope (<c>--request/--result/--work-root</c>) or the probe (<c>--input/--result</c>).</summary>
public enum WorkerArgumentStyle
{
    RequestResult,
    InputResult,
}

/// <summary>Runs a worker CLI once for a prepared scratch directory.</summary>
public interface IWorkerProcess
{
    /// <param name="timeout">Use <see cref="Timeout.InfiniteTimeSpan"/> for no timeout.</param>
    Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct);
}

/// <summary>
/// Spawns a worker with a minimal, explicit environment: only OS plumbing plus the variables the
/// worker documents, so server secrets (KOLDERA_TOKEN, signing key, proxy credentials) never reach
/// a process that handles untrusted documents. stdout/stderr are bounded and never contain
/// document content (the workers guarantee that on their side).
/// </summary>
public sealed class SubprocessWorker(string[] command, IReadOnlyDictionary<string, string> workerEnvironment, WorkerArgumentStyle style = WorkerArgumentStyle.RequestResult) : IWorkerProcess
{
    private const int StderrTailLimit = 4096;
    /// <summary>The only parent environment variables a worker child inherits.</summary>
    public static IReadOnlyList<string> PassThroughNames => PassThrough;

    private static readonly string[] PassThrough = ["PATH", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "LANG", "LC_ALL", "TEMP", "TMP", "HOME", "USERPROFILE", "SystemRoot", "windir", "ComSpec", "PATHEXT", "OS", "NODE_OPTIONS", "PLAYWRIGHT_BROWSERS_PATH", "LOCALAPPDATA"];

    /// <summary>A request/result verb that needs VERAPDF_CMD (the PDF <c>process</c> verb).</summary>
    public static SubprocessWorker ForPdfProcess(string[] command, string? veraPdfCommand)
    {
        var env = new Dictionary<string, string>();
        if (!string.IsNullOrWhiteSpace(veraPdfCommand)) env["VERAPDF_CMD"] = veraPdfCommand;
        return new SubprocessWorker(command, env);
    }

    /// <summary>A request/result verb with no environment beyond OS plumbing (PDF <c>reorder</c>).</summary>
    public static SubprocessWorker ForRequestResult(string[] command) =>
        new(command, new Dictionary<string, string>());

    /// <summary>A read-only <c>--input/--result</c> probe (<c>inspect</c>, <c>structure</c>): no environment beyond OS plumbing.</summary>
    public static SubprocessWorker ForProbe(string[] command) =>
        new(command, new Dictionary<string, string>(), WorkerArgumentStyle.InputResult);

    /// <summary>A Node verb (renderer render/semantic/reorder) that reads the contract schemas.</summary>
    public static SubprocessWorker ForNodeVerb(string[] command, string? contractsDir)
    {
        var env = new Dictionary<string, string>();
        if (!string.IsNullOrWhiteSpace(contractsDir)) env["KOLDERA_CONTRACTS_DIR"] = contractsDir;
        return new SubprocessWorker(command, env);
    }

    public static SubprocessWorker ForWeb(string[] command, string? contractsDir, bool allowLoopbackForTests)
    {
        var env = new Dictionary<string, string>();
        if (!string.IsNullOrWhiteSpace(contractsDir)) env["KOLDERA_CONTRACTS_DIR"] = contractsDir;
        if (allowLoopbackForTests) env["KOLDERA_SCANNER_ALLOW_LOOPBACK_FOR_TESTS"] = "1";
        return new SubprocessWorker(command, env);
    }

    public async Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
    {
        if (command.Length == 0) throw new InvalidOperationException("worker command is not configured");
        var psi = new ProcessStartInfo
        {
            FileName = command[0],
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
            WorkingDirectory = scratchRoot,
        };
        foreach (var arg in command.Skip(1)) psi.ArgumentList.Add(arg);
        var tail = style == WorkerArgumentStyle.InputResult
            ? new[] { "--input", requestPath, "--result", resultPath }
            : new[] { "--request", requestPath, "--result", resultPath, "--work-root", scratchRoot };
        foreach (var arg in tail) psi.ArgumentList.Add(arg);
        psi.Environment.Clear();
        foreach (var name in PassThrough)
        {
            var value = Environment.GetEnvironmentVariable(name);
            if (value is not null) psi.Environment[name] = value;
        }
        foreach (var (name, value) in workerEnvironment) psi.Environment[name] = value;

        using var process = new Process { StartInfo = psi };
        process.Start();
        process.StandardInput.Close();
        // Not tied to the caller's token: a cancelled read would abandon the pipes while the child still runs.
        // Both pipes are drained to the end (a full pipe would block the child), but only the last 4 KiB of stderr is kept.
        var stdout = DrainTailAsync(process.StandardOutput, 0);
        var stderr = DrainTailAsync(process.StandardError, StderrTailLimit);
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        if (timeout != Timeout.InfiniteTimeSpan) timeoutCts.CancelAfter(timeout);
        var timedOut = false;
        try
        {
            await process.WaitForExitAsync(timeoutCts.Token);
        }
        catch (OperationCanceledException)
        {
            KillTree(process);
            await process.WaitForExitAsync(CancellationToken.None);
            if (ct.IsCancellationRequested) throw;
            timedOut = true;
        }
        await stdout;
        var err = await stderr;
        return new WorkerRun(timedOut ? -1 : process.ExitCode, timedOut, err);
    }

    /// <summary>Reads <paramref name="reader"/> to its end and returns only its last <paramref name="keep"/> characters.</summary>
    public static async Task<string> DrainTailAsync(TextReader reader, int keep)
    {
        var buffer = new char[8192];
        var tail = new System.Text.StringBuilder(keep * 2);
        int read;
        while ((read = await reader.ReadAsync(buffer, CancellationToken.None)) > 0)
        {
            if (keep == 0) continue;
            tail.Append(buffer, 0, read);
            if (tail.Length > keep * 2) tail.Remove(0, tail.Length - keep);
        }
        return tail.Length > keep ? tail.ToString(tail.Length - keep, keep) : tail.ToString();
    }

    private static void KillTree(Process process)
    {
        try
        {
            process.Kill(entireProcessTree: true);
        }
        catch (InvalidOperationException)
        {
            // already exited
        }
    }
}
