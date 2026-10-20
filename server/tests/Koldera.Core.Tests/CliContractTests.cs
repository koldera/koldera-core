using System.Text.Json.Nodes;
using Koldera.Core.Cli;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

/// <summary>Exit-code contract, dispatch, option parsing and output failures of the command-line tool.</summary>
public class CliContractTests
{
    private static string WriteTemp(byte[] bytes, string name)
    {
        var dir = Directory.CreateTempSubdirectory("koldera-cli-in-").FullName;
        var path = Path.Combine(dir, name);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    private static string NewDir() => Directory.CreateTempSubdirectory("koldera-cli-out-").FullName;

    // ---- exit codes: 0 only when the machine verdict is "passed" ----

    [Theory]
    [InlineData("not_run")]
    [InlineData("error")]
    public async Task A_completed_pdf_job_without_a_verdict_exits_3(string status)
    {
        var pdf = StubResults.PdfCustom(r => r["baseline"] = new JsonObject { ["profile"] = "ua1", ["status"] = status, ["failedRules"] = new JsonArray() });
        var (code, stdout, _) = await CliRun.Run(pdf, "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"));
        Assert.Equal(3, code);
        Assert.Contains("machine check: " + status, stdout);
    }

    [Fact]
    public async Task A_fix_whose_only_failure_is_the_withheld_declaration_exits_0_and_says_it_is_not_a_pass()
    {
        var pdf = StubResults.PdfCustom(r => r["finalValidation"] = StubResults.ValidationOf("declaration_withheld", ("ISO 14289-1:2014 5-1", 1)));
        var (code, stdout, _) = await CliRun.Run(pdf, "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"));
        Assert.Equal(0, code);
        Assert.Contains("PDF/UA-1 machine check: declaration_withheld (1 rule failed: ISO 14289-1:2014 5-1 x1)", stdout);
        Assert.Contains("The PDF/UA identifier (pdfuaid) was not written: declaring PDF/UA requires a human review. This is not a pass.", stdout);
    }

    [Fact]
    public async Task A_completed_pdf_job_with_a_missing_verdict_exits_3()
    {
        var pdf = StubResults.PdfCustom(r => r["baseline"] = null);
        var (code, _, _) = await CliRun.Run(pdf, "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"));
        Assert.Equal(3, code);
    }

    [Fact]
    public async Task Scan_reports_a_machine_verdict_and_maps_it_to_0_or_2()
    {
        var (clean, cleanOut, _) = await CliRun.Run(new StubWorkerFactory { WebScan = StubResults.WebCompleted(0) }, "scan", "https://example.com/", "--out", NewDir());
        var (bad, badOut, _) = await CliRun.Run(new StubWorkerFactory { WebScan = StubResults.WebCompleted(2) }, "scan", "https://example.com/", "--out", NewDir());
        Assert.Equal(0, clean);
        Assert.Contains("WCAG 2.2 AA automated machine check: passed", cleanOut);
        Assert.Equal(2, bad);
        Assert.Contains("WCAG 2.2 AA automated machine check: failed", badOut);
    }

    // ---- dispatch ----

    [Theory]
    [InlineData]
    [InlineData("serve")]
    [InlineData("--urls", "http://+:8080")]
    [InlineData("--urls=http://+:8080")]
    [InlineData("--environment", "Production")]
    [InlineData("--contentRoot", "/x")]
    [InlineData("--applicationName", "x")]
    [InlineData("--port", "80")]
    [InlineData("Logging:LogLevel:Default=Debug")]
    [InlineData("Key=value")]
    [InlineData("/Key", "value")]
    public void These_invocations_start_the_server(params string[] args) => Assert.True(CliApp.StartsServer(args));

    [Theory]
    [InlineData("--fix", "a.pdf")]
    [InlineData("--json", "pdf", "x")]
    [InlineData("--hepl")]
    [InlineData("pdf", "a.pdf")]
    [InlineData("frobnicate")]
    [InlineData("--help")]
    [InlineData("--version")]
    public void These_invocations_do_not_start_the_server(params string[] args) => Assert.False(CliApp.StartsServer(args));

    [Theory]
    [InlineData("--fix", "a.pdf")]
    [InlineData("--json", "pdf", "x")]
    [InlineData("--hepl")]
    public async Task A_misplaced_or_misspelled_option_first_is_a_usage_error(params string[] args)
    {
        var (code, _, stderr) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), args);
        Assert.Equal(64, code);
        Assert.Contains("usage", stderr, StringComparison.OrdinalIgnoreCase);
    }

    // ---- option parsing ----

    [Theory]
    [InlineData("--out", "")]
    [InlineData("--out=")]
    [InlineData("--out", "   ")]
    public async Task An_empty_out_is_a_usage_error(params string[] extra)
    {
        var (code, _, stderr) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), ["pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"), .. extra]);
        Assert.Equal(64, code);
        Assert.Contains("--out", stderr);
    }

    [Fact]
    public async Task Double_dash_ends_the_options_so_a_file_may_start_with_a_dash()
    {
        var (code, stdout, stderr) = await CliRun.Run(StubResults.PdfAnalyzeClean(), "pdf", "--json", "--", WriteTemp(Fixtures.SmallPdf(), "-x.pdf"));
        Assert.True(code == 0, stderr);
        Assert.Contains("\"state\":\"completed\"", stdout);
    }

    [Fact]
    public async Task A_help_token_that_is_a_flag_value_is_not_help()
    {
        var (code, stdout, _) = await CliRun.Run(StubResults.PdfAnalyzeClean(), "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"), "--title", "--help");
        Assert.Equal(0, code); // the title is literally "--help"; the job ran
        Assert.Contains("machine check", stdout);
    }

    [Fact]
    public async Task Help_after_double_dash_is_a_file_name_not_help()
    {
        var (code, _, stderr) = await CliRun.Run(StubResults.PdfAnalyzeClean(), "pdf", "--", "--help");
        Assert.Equal(64, code);
        Assert.Contains("file not found: --help", stderr);
    }

    // ---- output failures ----

    [Fact]
    public async Task An_output_path_that_cannot_be_created_exits_3_with_a_short_message()
    {
        var blocker = WriteTemp("x"u8.ToArray(), "file.txt"); // a file where the directory should be
        var (code, _, stderr) = await CliRun.Run(StubResults.PdfFixApplied(), "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"), "--fix", "--out", Path.Combine(blocker, "sub"));
        Assert.Equal(3, code);
        Assert.StartsWith("koldera:", stderr);
        Assert.DoesNotContain("   at ", stderr);
    }

    [Fact]
    public async Task A_missing_source_artifact_fails_without_looping_or_leaving_files()
    {
        var store = new DataStore(Directory.CreateTempSubdirectory("koldera-cli-art-").FullName);
        var job = new CoreJob { Id = Guid.NewGuid(), Artifacts = [new CoreArtifact(Guid.NewGuid(), "remediated", "gone.pdf", "00", 1, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow)] };
        var outDir = NewDir();
        await File.WriteAllTextAsync(Path.Combine(outDir, "a.remediated.pdf"), "mine"); // would send the old code round the suffix loop
        await Assert.ThrowsAnyAsync<IOException>(() => CliApp.WriteArtifactsAsync(job, store, outDir, "a", CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(10)));
        Assert.Equal(["a.remediated.pdf"], Directory.GetFiles(outDir).Select(p => Path.GetFileName(p)));
    }
}
