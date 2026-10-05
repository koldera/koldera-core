using Koldera.Core.Workers;
using System.Text.Json;
using System.Text.Json.Nodes;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class CliTests
{
    private static string WriteTemp(byte[] bytes, string name)
    {
        var dir = Directory.CreateTempSubdirectory("koldera-cli-in-").FullName;
        var path = Path.Combine(dir, name);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    private static string NewDir() => Directory.CreateTempSubdirectory("koldera-cli-out-").FullName;

    [Fact]
    public async Task Pdf_analyze_with_no_failures_exits_0_and_prints_a_summary()
    {
        var (code, stdout, _) = await CliRun.Run(StubResults.PdfAnalyzeClean(), "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"));
        Assert.Equal(0, code);
        Assert.Contains("PDF/UA-1 machine check: passed", stdout);
    }

    [Fact]
    public async Task Failed_machine_rules_exit_2_and_are_counted_in_the_summary()
    {
        var (code, stdout, _) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"));
        Assert.Equal(2, code);
        Assert.Contains("PDF/UA-1 machine check: failed (1 rule failed", stdout);
    }

    [Fact]
    public async Task Fix_writes_the_copy_to_out_and_leaves_the_original_untouched()
    {
        var input = WriteTemp(Fixtures.SmallPdf(), "a.pdf");
        var before = await File.ReadAllBytesAsync(input);
        var outDir = NewDir();
        var (code, stdout, _) = await CliRun.Run(StubResults.PdfFixApplied(), "pdf", input, "--fix", "--out", outDir);
        Assert.Contains(code, new[] { 0, 2 });
        Assert.Equal(["a.remediated.pdf"], Directory.GetFiles(outDir).Select(p => Path.GetFileName(p)));
        Assert.Contains(Path.Combine(outDir, "a.remediated.pdf"), stdout);
        Assert.Equal(before, await File.ReadAllBytesAsync(input));
        Assert.Equal(Fixtures.OtherPdf(), await File.ReadAllBytesAsync(Path.Combine(outDir, "a.remediated.pdf")));
    }

    [Fact]
    public async Task An_existing_output_file_is_never_overwritten()
    {
        var input = WriteTemp(Fixtures.SmallPdf(), "a.pdf");
        var outDir = NewDir();
        await File.WriteAllTextAsync(Path.Combine(outDir, "a.remediated.pdf"), "mine");
        await CliRun.Run(StubResults.PdfFixApplied(), "pdf", input, "--fix", "--out", outDir);
        await CliRun.Run(StubResults.PdfFixApplied(), "pdf", input, "--fix", "--out", outDir);
        Assert.Equal("mine", await File.ReadAllTextAsync(Path.Combine(outDir, "a.remediated.pdf")));
        Assert.True(File.Exists(Path.Combine(outDir, "a.remediated-1.pdf")));
        Assert.True(File.Exists(Path.Combine(outDir, "a.remediated-2.pdf")));
    }

    [Fact]
    public async Task Fix_mode_is_sent_to_the_engine_with_language_and_title()
    {
        var pdf = StubResults.PdfFixApplied();
        await CliRun.Run(pdf, "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"), "--fix", "--language", "de", "--title", "  Mein Titel ", "--out", NewDir());
        var request = JsonDocument.Parse(pdf.Calls.Single().RequestJson).RootElement;
        Assert.Equal("fix", request.GetProperty("mode").GetString());
        Assert.Equal("de", request.GetProperty("explicitLanguage").GetString());
        Assert.Equal("Mein Titel", request.GetProperty("explicitTitle").GetString());
    }

    [Fact]
    public async Task Json_output_has_the_api_shapes_and_nothing_else_on_stdout()
    {
        var outDir = NewDir();
        var (_, stdout, _) = await CliRun.Run(StubResults.PdfFixApplied(), "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"), "--fix", "--json", "--out", outDir);
        var doc = JsonDocument.Parse(stdout).RootElement; // the whole stdout is one JSON document
        Assert.Equal("completed", doc.GetProperty("job").GetProperty("state").GetString());
        Assert.Equal(JsonValueKind.Array, doc.GetProperty("findings").ValueKind);
        Assert.Equal("PDF_TITLE_V1", doc.GetProperty("actions")[0].GetProperty("ruleId").GetString());
        var artifact = doc.GetProperty("artifacts")[0];
        Assert.Equal("remediated", artifact.GetProperty("kind").GetString());
        Assert.Equal(Path.Combine(outDir, "a.remediated.pdf"), artifact.GetProperty("path").GetString());
        Assert.False(artifact.TryGetProperty("downloadUrl", out _));
    }

    [Fact]
    public async Task Unsupported_input_exits_3()
    {
        var (code, stdout, _) = await CliRun.Run(StubResults.PdfUnsupported(), "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"));
        Assert.Equal(3, code);
        Assert.Contains("UNSUPPORTED_ENCRYPTED", stdout);
    }

    [Fact]
    public async Task A_worker_that_crashes_exits_3()
    {
        var crash = new StubWorker(_ => throw new InvalidOperationException("boom"));
        var (code, _, _) = await CliRun.Run(crash, "pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"));
        Assert.Equal(3, code);
    }

    [Theory]
    [InlineData("pdf")]
    [InlineData("pdf", "missing.pdf")]
    [InlineData("frobnicate")]
    [InlineData("html")]
    [InlineData("scan")]
    [InlineData("document")]
    public async Task Usage_errors_exit_64(params string[] args)
    {
        var (code, _, stderr) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), args);
        Assert.Equal(64, code);
        Assert.Contains("usage", stderr, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData("--language", "not a tag")]
    [InlineData("--title", "   ")]
    [InlineData("--bogus")]
    [InlineData("--out")] // value missing
    public async Task Bad_options_exit_64(params string[] extra)
    {
        var (code, _, stderr) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), ["pdf", WriteTemp(Fixtures.SmallPdf(), "a.pdf"), .. extra]);
        Assert.Equal(64, code);
        Assert.Contains("usage", stderr, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task A_file_that_is_not_a_pdf_exits_64()
    {
        var (code, _, stderr) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), "pdf", WriteTemp("hello"u8.ToArray(), "a.pdf"));
        Assert.Equal(64, code);
        Assert.Contains("%PDF-", stderr);
    }

    [Fact]
    public async Task Help_and_version_exit_0()
    {
        var (c1, out1, _) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), "--help");
        var (c2, out2, _) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), "pdf", "--help");
        var (c3, out3, _) = await CliRun.Run(StubResults.PdfAnalyzeCompleted(), "--version");
        Assert.Equal([0, 0, 0], [c1, c2, c3]);
        Assert.Contains("[serve]", out1);
        Assert.Contains("--fix", out2);
        Assert.Matches(@"^koldera-core \S+", out3);
    }

    private static StubWorkerFactory Html(StubWorker render, StubWorker pdf) => new()
    {
        PdfProcess = pdf,
        HtmlRender = render,
        HtmlInspect = StubResults.InspectResult(Fixtures.Sha256(Fixtures.SmallPdf())),
    };

    [Fact]
    public async Task Html_fix_is_the_default_and_analyze_only_switches_to_analyze()
    {
        var input = WriteTemp(Fixtures.SmallHtml(), "page.html");
        var outDir = NewDir();
        var render = StubResults.HtmlRenderCompleted();
        var fix = StubResults.PdfAnalyzeClean();
        var (code, _, stderr) = await CliRun.Run(Html(render, fix), "html", input, "--content-selector", "main", "--out", outDir);
        Assert.True(code == 0, stderr);
        // The renderer has no mode: the mode reaches the PDF engine that judges (and, for fix, repairs) the generated PDF.
        Assert.Equal("main", JsonDocument.Parse(render.Calls.Single().RequestJson).RootElement.GetProperty("content").GetProperty("selector").GetString());
        Assert.Equal("fix", JsonDocument.Parse(fix.Calls.Single().RequestJson).RootElement.GetProperty("mode").GetString());
        Assert.True(File.Exists(Path.Combine(outDir, "page.generated_pdf.pdf")));

        var analyze = StubResults.PdfAnalyzeClean();
        await CliRun.Run(Html(StubResults.HtmlRenderCompleted(), analyze), "html", input, "--analyze-only", "--out", outDir);
        Assert.Equal("analyze", JsonDocument.Parse(analyze.Calls.Single().RequestJson).RootElement.GetProperty("mode").GetString());
    }

    [Fact]
    public async Task Html_that_is_really_a_pdf_exits_64()
    {
        var (code, _, stderr) = await CliRun.Run(new StubWorkerFactory(), "html", WriteTemp(Fixtures.SmallPdf(), "page.html"));
        Assert.Equal(64, code);
        Assert.Contains("PDF", stderr);
    }

    [Fact]
    public async Task Scan_with_a_violation_exits_2_and_without_exits_0()
    {
        var outDir = NewDir();
        var (withViolation, stdout, errs) = await CliRun.Run(new StubWorkerFactory { WebScan = StubResults.WebCompleted(1) }, "scan", "https://example.com/page?token=secret", "--out", outDir);
        var (clean, _, _) = await CliRun.Run(new StubWorkerFactory { WebScan = StubResults.WebCompleted(0) }, "scan", "https://example.com/", "--out", outDir);
        Assert.True(withViolation == 2, errs + stdout);
        Assert.Equal(0, clean);
        Assert.DoesNotContain("secret", stdout); // a query string may carry a token and is never echoed
        Assert.NotEmpty(Directory.GetFiles(outDir, "example.com.*.json"));
    }

    [Fact]
    public async Task Scan_refuses_a_target_the_server_would_refuse()
    {
        var (code, _, stderr) = await CliRun.Run(new StubWorkerFactory(), "scan", "ftp://example.com/");
        Assert.Equal(64, code);
        Assert.Contains("usage", stderr, StringComparison.OrdinalIgnoreCase);
    }

    // ---- document ----

    private static string DocDir(string json, params (string Name, byte[] Bytes)[] files)
    {
        var dir = Directory.CreateTempSubdirectory("koldera-cli-doc-").FullName;
        File.WriteAllText(Path.Combine(dir, "doc.json"), json);
        foreach (var (name, bytes) in files)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.Combine(dir, name))!);
            File.WriteAllBytes(Path.Combine(dir, name), bytes);
        }
        return dir;
    }

    private static StubWorkerFactory Doc(IWorkerProcess? pdf = null, IWorkerProcess? render = null) =>
        new() { PdfProcess = pdf ?? StubResults.PdfAnalyzeClean(), DocumentRender = render ?? StubResults.DocumentRenderCompleted() };

    private static byte[] Png() => File.ReadAllBytes("Fixtures/documents/logo.png");

    private static string ImageDoc(string imageBlock) =>
        $$"""{"title":"T","language":"en","body":[{"type":"heading","level":1,"text":"T"},{{imageBlock}}]}""";

    [Fact]
    public async Task A_valid_document_renders_and_writes_the_pdf_to_out()
    {
        var dir = DocDir(File.ReadAllText("Fixtures/documents/invoice.json"));
        var outDir = NewDir();
        var (code, stdout, _) = await CliRun.Run(Doc(), "document", Path.Combine(dir, "doc.json"), "--out", outDir);
        Assert.Equal(0, code);
        Assert.True(File.Exists(Path.Combine(outDir, "doc.generated_pdf.pdf")));
        Assert.Contains("PDF/UA-1 machine check: passed", stdout);
    }

    [Fact]
    public async Task A_document_with_a_missing_alt_exits_64_with_the_validator_path_on_stderr()
    {
        var dir = DocDir(ImageDoc("""{"type":"image","file":"logo.png"}"""), ("logo.png", Png()));
        var render = StubResults.DocumentRenderCompleted();
        var (code, stdout, stderr) = await CliRun.Run(Doc(render: render), "document", Path.Combine(dir, "doc.json"));
        Assert.Equal(64, code);
        Assert.Contains("/body/1", stderr);
        Assert.Contains("IMAGE_ALT_MISSING", stderr);
        Assert.Equal("", stdout);
        Assert.Empty(render.Calls);
    }

    [Fact]
    public async Task An_image_file_is_resolved_relative_to_the_json_file_and_reaches_the_renderer_as_an_upload()
    {
        var dir = DocDir(ImageDoc("""{"type":"image","file":"img/logo.png","alt":"A logo."}"""), ("img/logo.png", Png()));
        var render = new RecordingWorker(StubResults.DocumentRenderCompleted());
        var (code, _, stderr) = await CliRun.Run(Doc(render: render), "document", Path.Combine(dir, "doc.json"), "--out", NewDir());
        Assert.True(code == 0, stderr);
        var image = Assert.Single(render.ImageNames);
        var stored = JsonNode.Parse(render.StoredDocument!)!["body"]![1]!.AsObject();
        Assert.Equal(image, stored["uploadId"]!.GetValue<string>()); // the renderer looks the file up by that name
        Assert.False(stored.ContainsKey("file"));
        Assert.Equal(Png(), await File.ReadAllBytesAsync(Path.Combine(dir, "img", "logo.png"))); // the input is not modified
    }

    [Theory]
    [InlineData("../outside.png")]
    [InlineData("img/../../outside.png")]
    public async Task An_image_path_that_escapes_the_json_directory_exits_64(string file)
    {
        var parent = Directory.CreateTempSubdirectory("koldera-cli-esc-").FullName;
        File.WriteAllBytes(Path.Combine(parent, "outside.png"), Png());
        var dir = Path.Combine(parent, "sub");
        Directory.CreateDirectory(Path.Combine(dir, "img"));
        File.WriteAllText(Path.Combine(dir, "doc.json"), ImageDoc($$"""{"type":"image","file":"{{file}}","alt":"x"}"""));
        var (code, _, stderr) = await CliRun.Run(Doc(), "document", Path.Combine(dir, "doc.json"));
        Assert.Equal(64, code);
        Assert.Contains("/body/1", stderr);
    }

    [Fact]
    public async Task An_absolute_image_path_exits_64()
    {
        var png = Path.GetFullPath("Fixtures/documents/logo.png").Replace("\\", "/");
        var dir = DocDir(ImageDoc($$"""{"type":"image","file":"{{png}}","alt":"x"}"""));
        var (code, _, stderr) = await CliRun.Run(Doc(), "document", Path.Combine(dir, "doc.json"));
        Assert.Equal(64, code);
        Assert.Contains("/body/1", stderr);
    }

    [Fact]
    public async Task An_upload_id_is_a_usage_error_naming_the_path()
    {
        var dir = DocDir(ImageDoc($$"""{"type":"image","uploadId":"{{Guid.NewGuid()}}","alt":"x"}"""));
        var (code, _, stderr) = await CliRun.Run(Doc(), "document", Path.Combine(dir, "doc.json"));
        Assert.Equal(64, code);
        Assert.Contains("/body/1", stderr);
        Assert.Contains("uploadId", stderr);
    }

    [Fact]
    public async Task An_image_whose_content_does_not_match_its_extension_exits_64()
    {
        var dir = DocDir(ImageDoc("""{"type":"image","file":"logo.png","alt":"x"}"""), ("logo.png", "not a png"u8.ToArray()));
        var (code, _, stderr) = await CliRun.Run(Doc(), "document", Path.Combine(dir, "doc.json"));
        Assert.Equal(64, code);
        Assert.Contains("/body/1", stderr);
    }

    [Fact]
    public async Task A_document_that_is_not_json_exits_64()
    {
        var dir = DocDir("{ nope");
        var (code, _, stderr) = await CliRun.Run(Doc(), "document", Path.Combine(dir, "doc.json"));
        Assert.Equal(64, code);
        Assert.Contains("JSON", stderr);
    }

    [Fact]
    public async Task A_generated_pdf_that_fails_veraPDF_exits_2_and_keeps_the_file()
    {
        var dir = DocDir(File.ReadAllText("Fixtures/documents/invoice.json"));
        var outDir = NewDir();
        var (code, stdout, _) = await CliRun.Run(Doc(pdf: StubResults.PdfAnalyzeCompleted()), "document", Path.Combine(dir, "doc.json"), "--out", outDir);
        Assert.Equal(2, code); // DOCUMENT_VALIDATION_FAILED counts as failed machine rules
        Assert.Contains("DOCUMENT_VALIDATION_FAILED", stdout);
        Assert.True(File.Exists(Path.Combine(outDir, "doc.generated_pdf.pdf")));
    }

    [Fact]
    public async Task No_veraPDF_verdict_exits_3()
    {
        var pdf = StubResults.PdfCustom(r => r["baseline"] = new JsonObject { ["profile"] = "ua1", ["status"] = "not_run", ["failedRules"] = new JsonArray() });
        var dir = DocDir(File.ReadAllText("Fixtures/documents/invoice.json"));
        var (code, stdout, _) = await CliRun.Run(Doc(pdf: pdf), "document", Path.Combine(dir, "doc.json"), "--out", NewDir());
        Assert.Equal(3, code);
        Assert.Contains("DOCUMENT_VALIDATION_UNAVAILABLE", stdout);
    }
}
