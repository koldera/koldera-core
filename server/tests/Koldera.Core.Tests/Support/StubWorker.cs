using Koldera.Core.Workers;

namespace Koldera.Core.Tests.Support;

/// <summary>
/// An <see cref="IWorkerProcess"/> that runs nothing: it writes a caller-supplied result document and
/// output files into the scratch directory, as a real worker would, and records what it was asked.
/// </summary>
public sealed class StubWorker : IWorkerProcess
{
    private readonly Func<string, string> _result;
    private readonly IReadOnlyDictionary<string, byte[]> _outputs;
    private readonly int _exitCode;
    private readonly bool _timedOut;
    private readonly string _stderr;

    public List<(string RequestJson, TimeSpan Timeout)> Calls { get; } = [];

    /// <param name="result">Builds the result JSON from the request JSON (so it can echo the job id).</param>
    /// <param name="outputs">File name (inside the scratch "out" directory) to bytes.</param>
    public StubWorker(Func<string, string> result, IReadOnlyDictionary<string, byte[]>? outputs = null, int exitCode = 0, bool timedOut = false, string stderr = "")
    {
        _result = result;
        _outputs = outputs ?? new Dictionary<string, byte[]>();
        _exitCode = exitCode;
        _timedOut = timedOut;
        _stderr = stderr;
    }

    public static StubWorker Fixed(string resultJson, IReadOnlyDictionary<string, byte[]>? outputs = null, int exitCode = 0) =>
        new(_ => resultJson, outputs, exitCode);

    /// <summary>The result document this stub would write for the request, so another stub can reuse a declaration.</summary>
    public string ResultFor(string requestJson) => _result(requestJson);

    public async Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
    {
        var request = await File.ReadAllTextAsync(requestPath, ct);
        Calls.Add((request, timeout));
        if (_timedOut) return new WorkerRun(-1, true, _stderr);
        var outDir = Path.Combine(scratchRoot, "out");
        Directory.CreateDirectory(outDir);
        foreach (var (name, bytes) in _outputs) await File.WriteAllBytesAsync(Path.Combine(outDir, name), bytes, ct);
        await File.WriteAllTextAsync(resultPath, _result(request), ct);
        return new WorkerRun(_exitCode, false, _stderr);
    }
}
