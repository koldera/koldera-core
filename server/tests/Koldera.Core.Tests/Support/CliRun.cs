using Koldera.Core.Cli;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests.Support;

/// <summary>Workers for CLI tests; one that a test did not supply fails loudly instead of starting a process.</summary>
public sealed class StubWorkerFactory : IWorkerFactory
{
    private static IWorkerProcess Missing(string name) => new StubWorker(_ => throw new InvalidOperationException("no " + name + " stub"));

    public IWorkerProcess? PdfProcess { get; init; }
    public IWorkerProcess? HtmlRender { get; init; }
    public IWorkerProcess? HtmlInspect { get; init; }
    public IWorkerProcess? WebScan { get; init; }
    public IWorkerProcess? DocumentRender { get; init; }

    public PdfWorkers Pdf() => new(PdfProcess ?? Missing("pdf"), null);
    public HtmlWorkers Html() => new(HtmlRender ?? Missing("html render"), HtmlInspect ?? Missing("inspect"));
    public WebWorkers Web() => new(WebScan ?? Missing("web"));
    public DocumentWorkers Document() => new(DocumentRender ?? Missing("document render"));
}

/// <summary>Wraps a stub and records what the worker would have found in its scratch directory.</summary>
public sealed class RecordingWorker(IWorkerProcess inner) : IWorkerProcess
{
    public string? StoredDocument { get; private set; }
    public List<string> ImageNames { get; } = [];

    public Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
    {
        StoredDocument = File.ReadAllText(Path.Combine(scratchRoot, "in", "document.json"));
        ImageNames.AddRange(Directory.GetFiles(Path.Combine(scratchRoot, "in", "images")).Select(p => Path.GetFileName(p)));
        return inner.RunAsync(scratchRoot, requestPath, resultPath, timeout, ct);
    }
}

public static class CliRun
{
    /// <summary>Runs the CLI with a PDF stub; see <see cref="Run(StubWorkerFactory, string[])"/>.</summary>
    public static Task<(int Code, string Stdout, string Stderr)> Run(IWorkerProcess pdf, params string[] args) =>
        Run(new StubWorkerFactory { PdfProcess = pdf }, args);

    /// <summary>Runs the CLI against its own empty environment and temp root; fails when the run leaves anything in that root.</summary>
    public static async Task<(int Code, string Stdout, string Stderr)> Run(StubWorkerFactory workers, params string[] args)
    {
        var tempRoot = Directory.CreateTempSubdirectory("koldera-cli-test-").FullName;
        try
        {
            var stdout = new StringWriter();
            var stderr = new StringWriter();
            var code = await CliApp.RunAsync(args, stdout, stderr, workers, _ => null, tempRoot);
            Assert.Empty(Directory.GetFileSystemEntries(tempRoot)); // the temporary data directory is always removed
            return (code, stdout.ToString(), stderr.ToString());
        }
        finally
        {
            Directory.Delete(tempRoot, true);
        }
    }
}
