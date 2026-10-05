using System.Text.Json;
using Koldera.Core.Cli;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>The command-line tool against the real tools; gated like <see cref="DocumentEngineIntegrationTests"/>.</summary>
public sealed class CliEngineIntegrationTests
{
    private static string? Env(string name) => Environment.GetEnvironmentVariable(name);

    /// <summary>The real tools, located by the same variables the other integration tests use and the container-style names the CLI reads.</summary>
    private static (IWorkerFactory Workers, Func<string, string?> Env) Real()
    {
        string? Lookup(string name) => name switch
        {
            "KOLDERA_ENGINE_JAR" => Env("KOLDERA_TEST_ENGINE_JAR"),
            "KOLDERA_RENDERER_CLI" => Env("KOLDERA_TEST_RENDERER_CLI"),
            "KOLDERA_TYPST_CMD" => Env("TYPST_CMD"),
            _ => Env(name),
        };
        var workers = new WorkerOptions { ContractsDir = SchemaResources.ContractsDirectory }.ApplyDefaults(Lookup);
        return (new ProcessWorkerFactory(workers), Lookup);
    }

    private static async Task<(int Code, string Stdout, string Stderr)> Run(params string[] args)
    {
        var (workers, env) = Real();
        var stdout = new StringWriter();
        var stderr = new StringWriter();
        var code = await CliApp.RunAsync(args, stdout, stderr, workers, env);
        return (code, stdout.ToString(), stderr.ToString());
    }

    /// <summary>The engine's own synthetic sample (the same file EngineIntegrationTests uses).</summary>
    private static string SamplePath()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "engine", "src", "test", "resources", "generated", "generated-untagged-text.pdf");
            if (File.Exists(candidate)) return candidate;
        }
        throw new FileNotFoundException("synthetic engine sample not found");
    }

    [DocumentToolsFact]
    public async Task The_cli_analyzes_a_synthetic_pdf_with_the_real_engine()
    {
        var (code, stdout, stderr) = await Run("pdf", SamplePath(), "--json");
        Assert.True(code is 0 or 2, $"exit {code}: {stderr} {stdout}");
        var job = JsonDocument.Parse(stdout).RootElement.GetProperty("job");
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.False(string.IsNullOrEmpty(job.GetProperty("engineVersion").GetString()));
    }

    [DocumentToolsFact]
    public async Task The_cli_renders_the_invoice_document_to_a_pdf_that_passes_the_machine_check()
    {
        var outDir = Directory.CreateTempSubdirectory("koldera-cli-real-out-").FullName;
        var (code, stdout, stderr) = await Run("document", Path.GetFullPath("Fixtures/documents/invoice.json"), "--out", outDir);
        Assert.True(code == 0, $"exit {code}: {stderr}\n{stdout}");
        Assert.Contains("PDF/UA-1 machine check: passed", stdout);
        var pdf = Assert.Single(Directory.GetFiles(outDir, "invoice.generated_pdf.pdf"));
        Assert.StartsWith("%PDF-", System.Text.Encoding.ASCII.GetString(await File.ReadAllBytesAsync(pdf), 0, 5));
    }
}
