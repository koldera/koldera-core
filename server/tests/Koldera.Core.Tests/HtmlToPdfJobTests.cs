using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

public class HtmlToPdfJobTests
{
    private static string GeneratedSha => Fixtures.Sha256(Fixtures.SmallPdf());

    private static CoreFactory Factory(StubWorker? render = null, StubWorker? pdf = null, StubWorker? inspect = null, StubWorker? structure = null, Dictionary<string, string?>? env = null, StubWorker? semantic = null) =>
        CoreFactory.WithStubWorkers(pdf ?? StubResults.PdfAnalyzeCompleted(), structure, env,
            htmlRender: render ?? StubResults.HtmlRenderCompleted(), inspect: inspect ?? StubResults.InspectResult(GeneratedSha), semantic: semantic);

    private static string SourceSha => Fixtures.Sha256(Fixtures.SmallHtml());

    [Fact]
    public async Task A_completed_render_and_validation_gives_a_generated_pdf_and_findings_from_both_stages()
    {
        using var f = Factory();
        var id = await f.CreateHtmlJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Equal("html_to_pdf", job.GetProperty("kind").GetString());
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        Assert.Contains(artifacts.GetProperty("items").EnumerateArray(), a => a.GetProperty("kind").GetString() == "generated_pdf");
        // Renderer findings come first, then the PDF engine's.
        var findings = f.Store.GetJob(id)!.Findings;
        Assert.Equal("html", findings[0].Source);
        Assert.Equal("HTML_LANG_MISSING", findings[0].RuleId);
        Assert.Contains(findings, x => x.Source == "pdf" && x.RuleId == "7.1-3");
        Assert.Contains(findings, x => x.RuleId == "PDF_STRUCTURE_TAGS_PRESENT" && x.NeedsHumanReview);
        Assert.True(findings.FindIndex(x => x.Source == "pdf") > findings.FindLastIndex(x => x.Source == "html"));
    }

    [Fact]
    public async Task The_generated_artifact_downloads_with_the_declared_hash()
    {
        using var f = Factory();
        var id = await f.CreateHtmlJob();
        await f.WaitForJob(id);
        var item = (await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts")).GetProperty("items")[0];
        var bytes = await f.CreateClient().GetByteArrayAsync(item.GetProperty("downloadUrl").GetString());
        Assert.Equal(GeneratedSha, Fixtures.Sha256(bytes));
    }

    [Fact]
    public async Task An_unsupported_render_fails_with_the_renderers_code_and_never_reaches_the_validate_stage()
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        var inspect = StubResults.InspectResult(GeneratedSha);
        using var f = Factory(render: StubResults.HtmlRenderUnsupported("EXTERNAL_RESOURCE_BLOCKED"), pdf: pdf, inspect: inspect);
        var id = await f.CreateHtmlJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("EXTERNAL_RESOURCE_BLOCKED", job.GetProperty("error").GetProperty("code").GetString());
        Assert.Empty(pdf.Calls);
        Assert.Empty(inspect.Calls);
        Assert.Contains(f.Store.GetJob(id)!.Findings, x => x.Source == "html" && x.RuleId == "HTML_NOT_A_DOCUMENT" && x.Disposition == "refused");
    }

    [Fact]
    public async Task A_pdf_upload_is_rejected_with_422()
    {
        using var f = Factory();
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Empty(f.Store.AllJobs());
    }

    [Theory]
    [InlineData("analyze")]
    [InlineData("fix")]
    [InlineData("generate_safe_fix")]
    [InlineData("")]
    public async Task Only_the_two_generate_modes_are_accepted(string mode)
    {
        using var f = Factory();
        var upload = await f.CompletedHtmlUpload();
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode, profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Theory]
    [InlineData("generate_analyze", "analyze")]
    [InlineData("generate_fix", "fix")]
    public async Task The_mode_maps_to_the_pdf_engine_with_the_html_generated_origin(string mode, string engineMode)
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = Factory(pdf: pdf);
        await f.WaitForJob(await f.CreateHtmlJob(mode));
        var request = JsonDocument.Parse(pdf.Calls.Single().RequestJson).RootElement;
        Assert.Equal(engineMode, request.GetProperty("mode").GetString());
        Assert.Equal("html_generated", request.GetProperty("origin").GetString());
        Assert.Equal("page.html", request.GetProperty("sourceFileName").GetString());
    }

    [Fact]
    public async Task The_content_selector_reaches_the_renderer_trimmed()
    {
        var render = StubResults.HtmlRenderCompleted();
        using var f = Factory(render: render);
        await f.WaitForJob(await f.CreateHtmlJob(contentSelector: "  main article  "));
        var content = JsonDocument.Parse(render.Calls.Single().RequestJson).RootElement.GetProperty("content");
        Assert.Equal("main article", content.GetProperty("selector").GetString());
    }

    [Fact]
    public async Task Without_a_selector_or_caps_the_request_has_no_content_and_no_limits()
    {
        var render = StubResults.HtmlRenderCompleted();
        using var f = Factory(render: render);
        await f.WaitForJob(await f.CreateHtmlJob());
        var request = JsonDocument.Parse(render.Calls.Single().RequestJson).RootElement;
        Assert.False(request.TryGetProperty("content", out _));
        Assert.False(request.TryGetProperty("limits", out _));
        Assert.Equal("A4", request.GetProperty("page").GetProperty("format").GetString());
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("a\nb")]
    public async Task A_blank_or_unprintable_selector_is_rejected(string selector)
    {
        using var f = Factory();
        var upload = await f.CompletedHtmlUpload();
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1", contentSelector = selector });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task A_selector_over_200_characters_is_rejected()
    {
        using var f = Factory();
        var upload = await f.CompletedHtmlUpload();
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1", contentSelector = new string('a', 201) });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task A_configured_html_size_cap_is_the_only_limit_sent_and_the_timeout_is_passed_unchanged()
    {
        var render = StubResults.HtmlRenderCompleted();
        var inspect = StubResults.InspectResult(GeneratedSha);
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = Factory(render: render, inspect: inspect, pdf: pdf, env: new() { ["KOLDERA_HTML_MAX_BYTES"] = "100000", ["KOLDERA_JOB_TIMEOUT_SECONDS"] = "90" });
        await f.WaitForJob(await f.CreateHtmlJob());
        var limits = JsonDocument.Parse(render.Calls.Single().RequestJson).RootElement.GetProperty("limits");
        Assert.Equal(100000, limits.GetProperty("maxBytes").GetInt64());
        Assert.False(limits.TryGetProperty("timeoutSeconds", out _));
        Assert.Equal(TimeSpan.FromSeconds(90), render.Calls.Single().Timeout);
        Assert.Equal(TimeSpan.FromSeconds(90), inspect.Calls.Single().Timeout);
        Assert.Equal(TimeSpan.FromSeconds(90), pdf.Calls.Single().Timeout);
    }

    [Fact]
    public async Task Without_a_timeout_every_stage_runs_without_one()
    {
        var render = StubResults.HtmlRenderCompleted();
        var inspect = StubResults.InspectResult(GeneratedSha);
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = Factory(render: render, inspect: inspect, pdf: pdf);
        await f.WaitForJob(await f.CreateHtmlJob());
        Assert.Equal(Timeout.InfiniteTimeSpan, render.Calls.Single().Timeout);
        Assert.Equal(Timeout.InfiniteTimeSpan, inspect.Calls.Single().Timeout);
        Assert.Equal(Timeout.InfiniteTimeSpan, pdf.Calls.Single().Timeout);
    }

    [Fact]
    public async Task A_render_without_usable_tags_fails_but_keeps_the_generated_file_for_review()
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = Factory(pdf: pdf, inspect: StubResults.InspectResult(GeneratedSha, usableTags: false));
        var id = await f.CreateHtmlJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("TAGGED_OUTPUT_UNAVAILABLE", job.GetProperty("error").GetProperty("code").GetString());
        Assert.Empty(pdf.Calls);
        var stored = f.Store.GetJob(id)!;
        Assert.Single(stored.Artifacts, a => a.Kind == "generated_pdf");
        Assert.Contains(stored.Findings, x => x.RuleId == "TAGGED_OUTPUT_UNAVAILABLE" && x.Severity == "blocking");
    }

    [Fact]
    public async Task An_inspect_probe_that_reports_another_hash_fails_the_job()
    {
        using var f = Factory(inspect: StubResults.InspectResult(new string('a', 64)));
        var job = await f.WaitForJob(await f.CreateHtmlJob());
        Assert.Equal("WORKER_CONTRACT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_renderer_artifact_whose_bytes_differ_from_its_declared_hash_fails_the_job()
    {
        // The declaration is for the small PDF, the file on disk is another one.
        var declared = StubResults.HtmlRenderCompleted();
        var tampered = new StubWorker(request => declared.ResultFor(request), new Dictionary<string, byte[]> { ["generated.pdf"] = Fixtures.OtherPdf() });
        using var f = Factory(render: tampered);
        var job = await f.WaitForJob(await f.CreateHtmlJob());
        Assert.Equal("WORKER_CONTRACT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_timed_out_renderer_fails_the_job_as_a_timeout()
    {
        using var f = Factory(render: new StubWorker(_ => "", timedOut: true));
        var job = await f.WaitForJob(await f.CreateHtmlJob());
        Assert.Equal("WORKER_TIMEOUT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task The_structure_probe_snapshots_the_generated_pdf()
    {
        using var f = Factory(structure: StubResults.StructureInspected(GeneratedSha, pages: 4));
        var id = await f.CreateHtmlJob();
        await f.WaitForJob(id);
        var stored = f.Store.GetJob(id)!;
        Assert.Equal(GeneratedSha, stored.Snapshots.Single().ArtifactSha256);
        Assert.Contains(stored.Findings, x => x.RuleId == "ORDER_NOTE");
    }

    [Fact]
    public async Task The_html_feature_can_be_switched_off()
    {
        using var f = Factory(env: new() { ["KOLDERA_FEATURES"] = "pdf" });
        var upload = await f.CompletedHtmlUpload();
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
    }

    [Fact]
    public async Task A_source_that_is_not_markup_is_unsupported()
    {
        using var f = Factory();
        var upload = await f.CompletedHtmlUpload("just plain text"u8.ToArray());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task The_semantic_collector_adds_a_dom_snapshot_bound_to_the_source_and_its_findings()
    {
        using var f = Factory(semantic: StubResults.SemanticCollected(SourceSha), structure: StubResults.StructureInspected(GeneratedSha));
        var id = await f.CreateHtmlJob();
        await f.WaitForJob(id);
        var stored = f.Store.GetJob(id)!;
        var dom = Assert.Single(stored.Snapshots, x => x.Origin == SnapshotOrigin.HtmlDom);
        Assert.Equal(SourceSha, dom.ArtifactSha256);
        Assert.Contains("n1", dom.NodesJson);
        // The PDF structure snapshot stays alongside it.
        Assert.Single(stored.Snapshots, x => x.Origin == SnapshotOrigin.PdfStructure && x.ArtifactSha256 == GeneratedSha);
        var finding = Assert.Single(stored.Findings, x => x.RuleId == "DOM_ORDER_NOTE");
        Assert.Equal("html", finding.Source);
        Assert.Equal("suspected_semantic_mismatch", finding.Disposition);
        Assert.True(finding.NeedsHumanReview);
        // Renderer diagnostics, then the semantic findings, then the PDF engine's.
        Assert.True(stored.Findings.FindIndex(x => x.RuleId == "HTML_LANG_MISSING") < stored.Findings.FindIndex(x => x.RuleId == "DOM_ORDER_NOTE"));
        Assert.True(stored.Findings.FindIndex(x => x.RuleId == "DOM_ORDER_NOTE") < stored.Findings.FindIndex(x => x.Source == "pdf"));
    }

    [Fact]
    public async Task The_semantic_collector_gets_the_source_and_the_unchanged_timeout()
    {
        var semantic = StubResults.SemanticCollected(SourceSha);
        using var f = Factory(semantic: semantic, env: new() { ["KOLDERA_JOB_TIMEOUT_SECONDS"] = "90" });
        await f.WaitForJob(await f.CreateHtmlJob());
        var call = semantic.Calls.Single();
        Assert.Equal(TimeSpan.FromSeconds(90), call.Timeout);
        Assert.Equal("/work/in/source.html", JsonDocument.Parse(call.RequestJson).RootElement.GetProperty("inputPath").GetString());
    }

    [Fact]
    public async Task A_semantic_result_for_another_source_fails_the_job()
    {
        using var f = Factory(semantic: StubResults.SemanticCollected(new string('a', 64)));
        var job = await f.WaitForJob(await f.CreateHtmlJob());
        Assert.Equal("WORKER_CONTRACT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_semantic_collector_that_cannot_collect_leaves_the_job_without_a_dom_snapshot()
    {
        using var f = Factory(semantic: StubResults.SemanticCollected(SourceSha, status: "unsupported"));
        var id = await f.CreateHtmlJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.DoesNotContain(f.Store.GetJob(id)!.Snapshots, x => x.Origin == SnapshotOrigin.HtmlDom);
    }

    [Fact]
    public async Task A_timed_out_semantic_collector_fails_the_job_as_a_timeout()
    {
        using var f = Factory(semantic: new StubWorker(_ => "", timedOut: true));
        var job = await f.WaitForJob(await f.CreateHtmlJob());
        Assert.Equal("WORKER_TIMEOUT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task Pdf_bytes_uploaded_as_html_are_unsupported()
    {
        using var f = Factory();
        var upload = await f.CompletedHtmlUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task A_source_that_is_not_utf8_is_unsupported()
    {
        using var f = Factory();
        var bytes = new byte[] { (byte)'<', (byte)'p', (byte)'>', 0xFF, 0xFE, 0xFD }.Concat("0123456789 and more text".Select(c => (byte)c)).ToArray();
        var upload = await f.CompletedHtmlUpload(bytes);
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task An_upload_that_was_never_sent_answers_409()
    {
        using var f = Factory();
        var ticket = await f.HtmlUploadTicket();
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = ticket, mode = "generate_analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("UPLOAD_INCOMPLETE", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_generated_pdf_over_the_configured_page_limit_fails_with_limit_exceeded_and_keeps_the_file()
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        // The inspect stub reports 2 pages.
        using var f = Factory(pdf: pdf, env: new() { ["KOLDERA_PDF_MAX_PAGES"] = "1" });
        var id = await f.CreateHtmlJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("LIMIT_EXCEEDED", job.GetProperty("error").GetProperty("code").GetString());
        Assert.Empty(pdf.Calls);
        Assert.Single(f.Store.GetJob(id)!.Artifacts, a => a.Kind == "generated_pdf");
    }
}
