using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

public class DocumentJobTests
{
    private static string Golden(string name = "invoice") => File.ReadAllText($"Fixtures/documents/{name}.json");

    private static byte[] Png() => File.ReadAllBytes("Fixtures/documents/logo.png");

    private static CoreFactory Factory(IWorkerProcess? render = null, IWorkerProcess? pdf = null, string? features = null, Dictionary<string, string?>? env = null) =>
        CoreFactory.WithStubWorkers(pdf ?? StubResults.PdfDocumentWithdrawn(), env: env, features: features,
            documentRender: render ?? StubResults.DocumentRenderCompleted());

    private static Task<HttpResponseMessage> Post(CoreFactory f, string json) =>
        f.AuthorizedClient().PostAsync("/v1/documents/jobs", new StringContent(json, Encoding.UTF8, "application/json"));

    private static string ImageDoc(string imageBlock) =>
        $$"""{"title":"T","language":"en","body":[{{imageBlock}}]}""";

    private static async Task<Guid> JobId(HttpResponseMessage accepted)
    {
        Assert.Equal(HttpStatusCode.Accepted, accepted.StatusCode);
        return (await accepted.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    private static async Task<string> FirstErrorPath(HttpResponseMessage r) =>
        (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("errors")[0].GetProperty("path").GetString()!;

    [Fact]
    public async Task A_valid_golden_document_completes_with_a_generated_pdf()
    {
        using var f = Factory();
        var id = await JobId(await Post(f, Golden()));
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Equal("document_to_pdf", job.GetProperty("kind").GetString());
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        Assert.Contains(artifacts.GetProperty("items").EnumerateArray(), a => a.GetProperty("kind").GetString() == "generated_pdf");
    }

    [Fact]
    public async Task The_renderer_gets_the_canonical_request_and_the_configured_typst_command()
    {
        var render = StubResults.DocumentRenderCompleted();
        using var f = Factory(render, env: new() { ["KOLDERA_TYPST_CMD"] = "/usr/local/bin/typst" });
        await f.WaitForJob(await JobId(await Post(f, Golden())));
        var request = JsonDocument.Parse(Assert.Single(render.Calls).RequestJson).RootElement;
        Assert.Equal("/work/in/document.json", request.GetProperty("documentPath").GetString());
        Assert.Equal("/work/in/images", request.GetProperty("imagesDirectory").GetString());
        Assert.Equal("/work/out", request.GetProperty("outputDirectory").GetString());
        Assert.Equal("/usr/local/bin/typst", request.GetProperty("typstCommand").GetString());
    }

    [Fact]
    public async Task The_typst_command_defaults_to_the_image_location()
    {
        var render = StubResults.DocumentRenderCompleted();
        using var f = Factory(render);
        await f.WaitForJob(await JobId(await Post(f, Golden())));
        Assert.Equal("/opt/typst/typst", JsonDocument.Parse(render.Calls[0].RequestJson).RootElement.GetProperty("typstCommand").GetString());
    }

    [Fact]
    public async Task An_invalid_document_is_422_with_the_validators_path_and_code()
    {
        using var f = Factory();
        var r = await Post(f, ImageDoc("""{"type":"image","uploadId":"00000000-0000-4000-8000-000000000001"}"""));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        var body = await r.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("VALIDATION_ERROR", body.GetProperty("code").GetString());
        Assert.Equal(422, body.GetProperty("status").GetInt32());
        Assert.Equal("/body/0", body.GetProperty("errors")[0].GetProperty("path").GetString());
        Assert.Equal("IMAGE_ALT_MISSING", body.GetProperty("errors")[0].GetProperty("code").GetString());
        Assert.False(string.IsNullOrEmpty(body.GetProperty("errors")[0].GetProperty("message").GetString()));
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task A_file_image_source_is_not_accepted_over_http()
    {
        using var f = Factory();
        var r = await Post(f, Golden("report")); // uses "file" images, which are CLI-only
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        var body = await r.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("VALIDATION_ERROR", body.GetProperty("code").GetString());
        Assert.Equal("/header/0", body.GetProperty("errors")[0].GetProperty("path").GetString());
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task An_unknown_image_upload_is_422_at_the_block()
    {
        using var f = Factory();
        var r = await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{Guid.NewGuid()}}","alt":"x"}"""));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("/body/0", await FirstErrorPath(r));
    }

    [Fact]
    public async Task An_incomplete_image_upload_is_422()
    {
        using var f = Factory();
        var bytes = Png();
        var declare = await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "a.png", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "image/png" });
        Assert.Equal(HttpStatusCode.Created, declare.StatusCode);
        var id = (await declare.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("uploadId").GetGuid();
        var r = await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{id}}","alt":"x"}"""));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("/body/0", await FirstErrorPath(r));
    }

    [Fact]
    public async Task A_pdf_upload_is_not_an_image()
    {
        using var f = Factory();
        var id = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{id}}","alt":"x"}"""));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("/body/0", await FirstErrorPath(r));
    }

    [Fact]
    public async Task Another_callers_image_upload_is_422()
    {
        using var f = Factory();
        var id = await f.CompletedUpload(Png(), "a.png", "image/png");
        f.Store.CreateUpload(f.Store.GetUpload(id)! with { Owner = "someone-else" });
        var r = await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{id}}","alt":"x"}"""));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task An_image_whose_bytes_are_not_the_declared_type_is_415()
    {
        using var f = Factory();
        var id = await f.CompletedUpload(Encoding.ASCII.GetBytes("not a png at all"), "a.png", "image/png");
        var r = await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{id}}","alt":"x"}"""));
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task An_svg_whose_root_is_not_svg_is_415()
    {
        using var f = Factory();
        var id = await f.CompletedUpload(Encoding.UTF8.GetBytes("<html><body/></html>"), "a.svg", "image/svg+xml");
        var r = await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{id}}","alt":"x"}"""));
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
    }

    /// <summary>Checks, while the renderer "runs", that the document and the image upload sit where the renderer looks for them.</summary>
    private sealed class PlacementCheckingRender(Func<Guid> uploadId, byte[] expected, StubWorker inner) : IWorkerProcess
    {
        public bool Placed { get; private set; }

        public Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
        {
            Placed = File.Exists(Path.Combine(scratchRoot, "in", "document.json"))
                && File.ReadAllBytes(Path.Combine(scratchRoot, "in", "images", uploadId().ToString())).SequenceEqual(expected);
            return inner.RunAsync(scratchRoot, requestPath, resultPath, timeout, ct);
        }
    }

    [Theory]
    [InlineData("image/png", "a.png")]
    [InlineData("image/jpeg", "a.jpg")]
    [InlineData("image/svg+xml", "a.svg")]
    public async Task A_referenced_image_upload_is_placed_in_the_images_directory(string contentType, string fileName)
    {
        var bytes = contentType switch
        {
            "image/png" => Png(),
            "image/jpeg" => [0xFF, 0xD8, 0xFF, 0xE0, 0, 16, (byte)'J', (byte)'F', (byte)'I', (byte)'F', 0],
            _ => Encoding.UTF8.GetBytes("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"),
        };
        Guid id = default; // known only after the upload, which needs the factory the render stub is given to
        var render = new PlacementCheckingRender(() => id, bytes, StubResults.DocumentRenderCompleted());
        using var f = Factory(render);
        id = await f.CompletedUpload(bytes, fileName, contentType);
        var jobId = await JobId(await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{id}}","alt":"x"}""")));
        Assert.Equal("completed", (await f.WaitForJob(jobId)).GetProperty("state").GetString());
        Assert.True(render.Placed);
    }

    [Fact]
    public async Task The_engine_withdraws_the_renderers_identifier_and_the_job_completes_as_declaration_withheld()
    {
        var pdf = StubResults.PdfDocumentWithdrawn();
        using var f = Factory(pdf: pdf);
        var id = await JobId(await Post(f, Golden()));
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Equal("declaration_withheld", job.GetProperty("validation").GetProperty("status").GetString());
        Assert.Equal("ISO 14289-1:2014 5-1", Assert.Single(job.GetProperty("validation").GetProperty("failedRules").EnumerateArray()).GetProperty("ruleId").GetString());
        var request = JsonDocument.Parse(Assert.Single(pdf.Calls).RequestJson).RootElement;
        Assert.Equal("fix", request.GetProperty("mode").GetString());
        Assert.Equal("document_generated", request.GetProperty("origin").GetString());
        Assert.False(request.TryGetProperty("declarePdfUa", out _), "the server never requests the declaration");

        // The withdrawn copy is the one generated_pdf; the rendered file, which still declares PDF/UA, is not kept.
        var items = (await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts")).GetProperty("items").EnumerateArray().ToList();
        var generated = Assert.Single(items, a => a.GetProperty("kind").GetString() == "generated_pdf");
        Assert.Equal(Fixtures.Sha256(Fixtures.OtherPdf()), generated.GetProperty("sha256").GetString());
        Assert.DoesNotContain(items, a => a.GetProperty("kind").GetString() == "remediated");
        Assert.False(File.Exists(Path.Combine(f.Store.JobDir(id), "out", "document.pdf")));
        var actions = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/actions");
        Assert.Contains(actions.GetProperty("items").EnumerateArray(), a => a.GetProperty("ruleId").GetString() == "PDF_UA_IDENTIFIER_WITHDRAWN_V1");
    }

    [Fact]
    public async Task A_withheld_verdict_listing_another_rule_is_still_a_failed_rule()
    {
        using var f = Factory(pdf: StubResults.PdfDocumentWithdrawn(r => r["finalValidation"] = StubResults.ValidationOf("declaration_withheld", ("7.1-3", 1), ("ISO 14289-1:2014 5-1", 1))));
        var job = await f.WaitForJob(await JobId(await Post(f, Golden())));
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("DOCUMENT_VALIDATION_FAILED", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_rolled_back_withdrawal_fails_the_job_and_keeps_no_declared_pdf()
    {
        var pdf = StubResults.PdfCustom(r =>
        {
            r["status"] = "failed";
            r["remediationStatus"] = "rolled_back";
            r["error"] = new JsonObject { ["code"] = "INTEGRITY_REGRESSION", ["message"] = "generated gate: newly failing rule 7.1-3 (1)" };
        });
        using var f = Factory(pdf: pdf);
        var id = await JobId(await Post(f, Golden()));
        Assert.Equal("failed", (await f.WaitForJob(id)).GetProperty("state").GetString());
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        Assert.DoesNotContain(artifacts.GetProperty("items").EnumerateArray(), a => a.GetProperty("kind").GetString() == "generated_pdf");
    }

    [Fact]
    public async Task A_rendered_pdf_without_an_identifier_is_kept_as_it_is()
    {
        var pdf = StubResults.PdfCustom(r =>
        {
            r["remediationStatus"] = "no_applicable_fix";
            r["baseline"] = StubResults.ValidationOf("passed");
        });
        using var f = Factory(pdf: pdf);
        var id = await JobId(await Post(f, Golden()));
        Assert.Equal("completed", (await f.WaitForJob(id)).GetProperty("state").GetString());
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        var generated = Assert.Single(artifacts.GetProperty("items").EnumerateArray(), a => a.GetProperty("kind").GetString() == "generated_pdf");
        Assert.Equal(Fixtures.Sha256(Fixtures.SmallPdf()), generated.GetProperty("sha256").GetString());
    }

    [Fact]
    public async Task A_failed_veraPDF_rule_fails_the_job_but_keeps_the_pdf_and_the_findings()
    {
        using var f = Factory(pdf: StubResults.PdfDocumentWithdrawn(r => r["finalValidation"] = StubResults.ValidationOf("failed", ("7.1-3", 2), ("ISO 14289-1:2014 5-1", 1))));
        var id = await JobId(await Post(f, Golden()));
        var job = await f.WaitForJob(id);
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("DOCUMENT_VALIDATION_FAILED", job.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains("7.1-3", job.GetProperty("error").GetProperty("message").GetString());
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        Assert.Contains(artifacts.GetProperty("items").EnumerateArray(), a => a.GetProperty("kind").GetString() == "generated_pdf");
        Assert.Contains(f.Store.GetJob(id)!.Findings, x => x.RuleId == "7.1-3");
        Assert.Equal(HttpStatusCode.OK, (await f.AuthorizedClient().GetAsync($"/v1/jobs/{id}/findings")).StatusCode);
    }

    [Theory]
    [InlineData("not_run")]
    [InlineData("error")]
    public async Task A_veraPDF_run_without_a_verdict_does_not_complete_the_job(string status)
    {
        var pdf = StubResults.PdfDocumentWithdrawn(r => r["finalValidation"] = new JsonObject { ["profile"] = "ua1", ["status"] = status, ["failedRules"] = new JsonArray() });
        using var f = Factory(pdf: pdf);
        var id = await JobId(await Post(f, Golden()));
        var job = await f.WaitForJob(id);
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("DOCUMENT_VALIDATION_UNAVAILABLE", job.GetProperty("error").GetProperty("code").GetString());
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        Assert.Contains(artifacts.GetProperty("items").EnumerateArray(), a => a.GetProperty("kind").GetString() == "generated_pdf");
    }

    [Fact]
    public async Task A_missing_baseline_verdict_does_not_complete_the_job()
    {
        var pdf = StubResults.PdfCustom(r => r["baseline"] = null);
        using var f = Factory(pdf: pdf);
        var job = await f.WaitForJob(await JobId(await Post(f, Golden())));
        // Either the contract check or the verdict check refuses it; it is never completed.
        Assert.Equal("failed", job.GetProperty("state").GetString());
    }

    [Theory]
    [InlineData("\uFEFF<svg xmlns=\"http://www.w3.org/2000/svg\"/>")] // BOM
    [InlineData("<?xml version=\"1.0\" encoding=\"UTF-8\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>")]
    [InlineData("<!-- made by a tool --><svg xmlns=\"http://www.w3.org/2000/svg\"/>")]
    [InlineData("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\"><svg xmlns=\"http://www.w3.org/2000/svg\"/>")]
    [InlineData("<!DOCTYPE svg [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]><svg xmlns=\"http://www.w3.org/2000/svg\">&b;</svg>")]
    [InlineData("<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\"/>")]
    public async Task Real_world_svg_prologs_are_accepted_without_expanding_or_fetching(string svg)
    {
        var path = Path.GetTempFileName();
        try
        {
            await File.WriteAllBytesAsync(path, new UTF8Encoding(false).GetBytes(svg));
            Assert.Null(await ImageSniffer.MismatchAsync(path, "image/svg+xml", CancellationToken.None));
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public async Task A_bom_prefixed_svg_is_accepted_over_http()
    {
        using var f = Factory();
        var bytes = new UTF8Encoding(true).GetPreamble().Concat(Encoding.UTF8.GetBytes("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")).ToArray();
        var id = await f.CompletedUpload(bytes, "a.svg", "image/svg+xml");
        var r = await Post(f, ImageDoc($$"""{"type":"image","uploadId":"{{id}}","alt":"x"}"""));
        Assert.Equal(HttpStatusCode.Accepted, r.StatusCode);
    }

    [Fact]
    public async Task With_the_feature_off_a_malformed_body_is_still_404()
    {
        using var f = Factory(features: "pdf");
        Assert.Equal(HttpStatusCode.NotFound, (await Post(f, "{not json")).StatusCode);
    }

    [Fact]
    public async Task A_malformed_body_is_400_not_a_server_error()
    {
        using var f = Factory();
        Assert.Equal(HttpStatusCode.BadRequest, (await Post(f, "{not json")).StatusCode);
    }

    private sealed class BlockingRender : IWorkerProcess
    {
        public TaskCompletionSource Started { get; } = new();
        public TaskCompletionSource Release { get; } = new();

        public async Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
        {
            Started.TrySetResult();
            await Release.Task.WaitAsync(ct);
            return new WorkerRun(0, true, "");
        }
    }

    [Fact]
    public async Task A_full_queue_is_503_and_leaves_no_job_directory_or_document()
    {
        var blocking = new BlockingRender();
        using var f = Factory(blocking, env: new() { ["KOLDERA_CONCURRENCY"] = "1", ["KOLDERA_QUEUE_CAPACITY"] = "1" });
        try
        {
            await JobId(await Post(f, Golden()));
            await blocking.Started.Task.WaitAsync(TimeSpan.FromSeconds(30));
            await JobId(await Post(f, Golden())); // fills the single slot
            var before = f.Store.AllJobs().Select(j => j.Id).ToHashSet();
            var jobsDir = Path.GetDirectoryName(f.Store.JobDir(before.First()))!;
            var dirsBefore = Directory.GetDirectories(jobsDir).Length;
            var refused = await Post(f, Golden());
            Assert.Equal(HttpStatusCode.ServiceUnavailable, refused.StatusCode);
            Assert.Equal(dirsBefore, Directory.GetDirectories(jobsDir).Length);
            Assert.Equal(before, f.Store.AllJobs().Select(j => j.Id).ToHashSet());
        }
        finally
        {
            blocking.Release.TrySetResult();
        }
    }

    [Fact]
    public async Task A_renderer_refusal_fails_the_job_with_its_code_and_typsts_message()
    {
        var pdf = StubResults.PdfAnalyzeClean();
        using var f = Factory(StubResults.DocumentRenderFailed("DOCUMENT_REFUSED", "image has no description"), pdf);
        var job = await f.WaitForJob(await JobId(await Post(f, Golden())));
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("DOCUMENT_REFUSED", job.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal("image has no description", job.GetProperty("error").GetProperty("message").GetString());
        Assert.Empty(pdf.Calls);
    }

    [Fact]
    public async Task A_renderer_that_publishes_an_artifact_with_a_failure_is_a_contract_violation()
    {
        var bytes = Fixtures.SmallPdf();
        var artifact = new JsonObject { ["kind"] = "generated_pdf", ["path"] = "/work/out/document.pdf", ["sha256"] = Fixtures.Sha256(bytes), ["bytes"] = bytes.Length };
        var render = new StubWorker(req => StubResults.DocumentRenderResult(req, "failed", new JsonArray(artifact),
            new JsonObject { ["code"] = "WORKER_ERROR", ["message"] = "x" }), new Dictionary<string, byte[]> { ["document.pdf"] = bytes });
        using var f = Factory(render);
        var job = await f.WaitForJob(await JobId(await Post(f, Golden())));
        Assert.Equal("WORKER_CONTRACT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task Without_the_document_feature_the_route_is_404()
    {
        using var f = Factory(features: "pdf");
        var r = await Post(f, Golden());
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task Uploads_accept_the_three_image_types_and_still_refuse_others()
    {
        using var f = Factory();
        foreach (var type in new[] { "image/png", "image/jpeg", "image/svg+xml", "application/pdf", "text/html" })
        {
            var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "x", sizeBytes = 10, sha256 = new string('a', 64), contentType = type });
            Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        }
        var bad = await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "x", sizeBytes = 10, sha256 = new string('a', 64), contentType = "image/gif" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, bad.StatusCode);
    }
}
