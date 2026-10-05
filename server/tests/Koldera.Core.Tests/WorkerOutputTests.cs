using System.Text;
using System.Text.RegularExpressions;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>Worker output is untrusted: a result file is read only up to a fixed size, never whole into memory.</summary>
public sealed class WorkerOutputTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "koldera-out-" + Guid.NewGuid().ToString("N"));

    public WorkerOutputTests() => Directory.CreateDirectory(_dir);

    public void Dispose() => Directory.Delete(_dir, true);

    [Fact]
    public async Task A_result_within_the_limit_is_read_as_utf8_text()
    {
        var path = Path.Combine(_dir, "result.json");
        await File.WriteAllTextAsync(path, "{\"title\":\"Größe\"}", new UTF8Encoding(false));

        Assert.Equal("{\"title\":\"Größe\"}", await WorkerOutput.ReadResultAsync(path, CancellationToken.None, maxBytes: 64));
    }

    [Fact]
    public async Task A_result_larger_than_the_limit_is_a_contract_error_without_reading_it()
    {
        var path = Path.Combine(_dir, "result.json");
        await File.WriteAllTextAsync(path, new string('x', 65));

        var e = await Assert.ThrowsAsync<WorkerContractException>(() => WorkerOutput.ReadResultAsync(path, CancellationToken.None, maxBytes: 64));
        Assert.Equal("worker result exceeds 64 bytes", e.Message);
    }

    [Fact]
    public void The_default_limit_is_64_MiB()
    {
        Assert.Equal(64L * 1024 * 1024, WorkerOutput.MaxResultBytes);
    }

    [Fact]
    public void Executors_read_worker_results_only_through_the_bounded_reader()
    {
        // Guards against a new executor going back to File.ReadAllTextAsync on a worker-written file.
        var workers = Path.Combine(RepoRoot(), "server", "src", "Koldera.Core", "Workers");
        var offenders = Directory.EnumerateFiles(workers, "*.cs")
            .SelectMany(f => File.ReadAllLines(f).Select((line, i) => (f, i, line)))
            .Where(x => Regex.IsMatch(x.line, @"File\.ReadAllText(Async)?\((resultPath|inspectPath)"))
            .Select(x => $"{Path.GetFileName(x.f)}:{x.i + 1}")
            .ToList();
        Assert.Empty(offenders);
    }

    private static string RepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "server", "src"))) dir = dir.Parent;
        return dir?.FullName ?? throw new InvalidOperationException("repository root not found");
    }
}
