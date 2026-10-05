using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;
using Microsoft.Extensions.DependencyInjection;

namespace Koldera.Core.Tests;

public class PdfJobTests
{
    [Fact]
    public async Task Analyze_job_runs_and_exposes_findings()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var id = await f.CreatePdfJob(mode: "analyze");
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        var findings = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/findings");
        Assert.Equal(1, findings.GetProperty("items").GetArrayLength());
    }

    [Fact]
    public async Task Fix_job_artifact_downloads_through_its_signed_url_with_matching_hash()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfFixApplied());
        var id = await f.CreatePdfJob(mode: "fix");
        await f.WaitForJob(id);
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        var a = artifacts.GetProperty("items")[0];
        var response = await f.CreateClient().GetAsync(a.GetProperty("downloadUrl").GetString());
        var bytes = await response.Content.ReadAsByteArrayAsync();
        Assert.Equal(a.GetProperty("sha256").GetString(), Fixtures.Sha256(bytes));
        Assert.Equal("application/pdf", response.Content.Headers.ContentType!.MediaType);
        Assert.Equal("attachment; filename=\"remediated.pdf\"", response.Content.Headers.ContentDisposition!.ToString());
        Assert.DoesNotContain("report", response.Content.Headers.ContentDisposition.ToString());
    }

    [Fact]
    public async Task Actions_are_listed_in_the_workers_order()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfFixApplied());
        var id = await f.CreatePdfJob(mode: "fix");
        await f.WaitForJob(id);
        var actions = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/actions");
        Assert.Equal("PDF_TITLE_V1", actions.GetProperty("items")[0].GetProperty("ruleId").GetString());
    }

    [Theory]
    [InlineData("safe_fix")]
    [InlineData("structure_fix")]
    [InlineData("generate_fix")]
    public async Task Modes_outside_the_core_set_are_rejected(string mode)
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode, profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task Another_profile_is_rejected()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua2" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("tab\there")]
    public async Task A_blank_or_unprintable_title_is_rejected(string title)
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode = "fix", profile = "ua1", explicitTitle = title });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("VALIDATION_ERROR", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_title_over_500_characters_is_rejected()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode = "fix", profile = "ua1", explicitTitle = new string('a', 501) });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task A_malformed_language_tag_is_rejected()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode = "fix", profile = "ua1", explicitLanguage = "not a tag" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task The_trimmed_title_and_the_upload_file_name_reach_the_engine()
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = CoreFactory.WithStubWorkers(pdf);
        var id = await f.CreatePdfJob(mode: "fix", explicitTitle: "  Annual report  ", explicitLanguage: "de");
        await f.WaitForJob(id);
        var request = JsonDocument.Parse(pdf.Calls.Single().RequestJson).RootElement;
        Assert.Equal("Annual report", request.GetProperty("explicitTitle").GetString());
        Assert.Equal("de", request.GetProperty("explicitLanguage").GetString());
        Assert.Equal("report.pdf", request.GetProperty("sourceFileName").GetString());
        Assert.Equal("upload", request.GetProperty("origin").GetString());
    }

    [Fact]
    public async Task Without_configured_caps_the_request_has_no_limits_and_the_run_has_no_timeout()
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = CoreFactory.WithStubWorkers(pdf);
        await f.WaitForJob(await f.CreatePdfJob());
        var call = pdf.Calls.Single();
        Assert.False(JsonDocument.Parse(call.RequestJson).RootElement.TryGetProperty("limits", out _));
        Assert.Equal(Timeout.InfiniteTimeSpan, call.Timeout);
    }

    [Fact]
    public async Task Configured_caps_are_the_only_limits_sent_and_the_timeout_is_passed_unchanged()
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = CoreFactory.WithStubWorkers(pdf, env: new() { ["KOLDERA_PDF_MAX_PAGES"] = "40", ["KOLDERA_JOB_TIMEOUT_SECONDS"] = "90" });
        await f.WaitForJob(await f.CreatePdfJob());
        var call = pdf.Calls.Single();
        var limits = JsonDocument.Parse(call.RequestJson).RootElement.GetProperty("limits");
        Assert.Equal(40, limits.GetProperty("maxPages").GetInt32());
        Assert.Equal(90, limits.GetProperty("timeoutSeconds").GetInt32());
        Assert.False(limits.TryGetProperty("maxBytes", out _));
        Assert.Equal(TimeSpan.FromSeconds(90), call.Timeout);
    }

    [Fact]
    public async Task Job_on_an_incomplete_upload_is_rejected()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var ticket = await f.UploadTicket(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = ticket, mode = "analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
    }

    [Fact]
    public async Task An_unknown_upload_is_rejected()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = Guid.NewGuid(), mode = "analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task A_file_without_a_pdf_header_is_unsupported()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload("plain text, no header"u8.ToArray());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" });
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task Response_shape_matches_the_sdk_job_type()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var id = await f.CreatePdfJob(mode: "analyze");
        var job = await f.WaitForJob(id);
        string[] expected = ["id", "kind", "mode", "state", "createdAt", "startedAt", "finishedAt", "validation", "baselineValidation", "finalValidation", "remediationStatus", "manualReviewRequired", "engineVersion", "error", "targetUrl", "contentSelector"];
        Assert.Equal(expected.Order(), job.EnumerateObject().Select(p => p.Name).Order());
    }

    [Fact]
    public async Task An_unsupported_result_fails_the_job_with_the_workers_error()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfUnsupported());
        var job = await f.WaitForJob(await f.CreatePdfJob());
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("UNSUPPORTED_ENCRYPTED", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_timed_out_worker_fails_the_job_as_a_timeout()
    {
        using var f = CoreFactory.WithStubWorkers(new StubWorker(_ => "", timedOut: true));
        var job = await f.WaitForJob(await f.CreatePdfJob());
        Assert.Equal("WORKER_TIMEOUT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task The_structure_probe_adds_a_snapshot_page_count_and_findings_for_the_original()
    {
        var probe = StubResults.StructureInspected(Fixtures.Sha256(Fixtures.SmallPdf()), pages: 3);
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), probe);
        var id = await f.CreatePdfJob();
        await f.WaitForJob(id);
        var stored = f.Store.GetJob(id)!;
        Assert.Equal(3, stored.Pages);
        Assert.Single(stored.Snapshots);
        Assert.Contains(stored.Findings, x => x.RuleId == "ORDER_NOTE" && x.NeedsHumanReview);
    }

    [Fact]
    public async Task For_a_fix_the_probe_describes_the_remediated_copy()
    {
        var remediated = Fixtures.OtherPdf();
        var probe = StubResults.StructureInspected(Fixtures.Sha256(remediated));
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfFixApplied(remediated), probe);
        var id = await f.CreatePdfJob(mode: "fix");
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Equal(Fixtures.Sha256(remediated), f.Store.GetJob(id)!.Snapshots.Single().ArtifactSha256);
    }

    [Fact]
    public async Task A_probe_that_reports_another_hash_fails_the_job()
    {
        var probe = StubResults.StructureInspected(new string('a', 64));
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), probe);
        var job = await f.WaitForJob(await f.CreatePdfJob());
        Assert.Equal("WORKER_CONTRACT", job.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task Another_owners_job_is_not_found_on_every_read()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var id = Guid.NewGuid();
        await f.Store.SaveJobAsync(new CoreJob { Id = id, Owner = "session:other", State = CoreJobState.Completed, CreatedAt = f.Clock.GetUtcNow() });
        var c = f.AuthorizedClient();
        foreach (var path in new[] { "", "/findings", "/actions", "/artifacts" })
            Assert.Equal(HttpStatusCode.NotFound, (await c.GetAsync($"/v1/jobs/{id}{path}")).StatusCode);
    }

    [Fact]
    public async Task Job_reads_require_authorization_but_unknown_jobs_are_not_found()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.CreateClient().GetAsync($"/v1/jobs/{Guid.NewGuid()}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await f.AuthorizedClient().GetAsync($"/v1/jobs/{Guid.NewGuid()}")).StatusCode);
    }

    [Fact]
    public async Task A_tampered_or_expired_download_signature_is_forbidden()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfFixApplied());
        var id = await f.CreatePdfJob(mode: "fix");
        await f.WaitForJob(id);
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        var url = artifacts.GetProperty("items")[0].GetProperty("downloadUrl").GetString()!;
        Assert.Equal(HttpStatusCode.Forbidden, (await f.CreateClient().GetAsync(url[..^2] + "AA")).StatusCode);
        f.Clock.Advance(TimeSpan.FromMinutes(11));
        Assert.Equal(HttpStatusCode.Forbidden, (await f.CreateClient().GetAsync(url)).StatusCode);
    }

    [Fact]
    public async Task The_artifact_expiry_is_the_retention_expiry_not_the_link_expiry()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfFixApplied(), env: new() { ["KOLDERA_RETENTION_HOURS"] = "5" });
        var id = await f.CreatePdfJob(mode: "fix");
        await f.WaitForJob(id);
        var items = (await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts")).GetProperty("items");
        var expires = items[0].GetProperty("expiresAt").GetDateTimeOffset();
        Assert.Equal(f.Clock.GetUtcNow().AddHours(5), expires);
    }

    [Fact]
    public async Task Artifacts_stay_downloadable_after_a_restart_of_the_index()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfFixApplied());
        var id = await f.CreatePdfJob(mode: "fix");
        await f.WaitForJob(id);
        var artifactId = f.Store.GetJob(id)!.Artifacts.Single().Id;
        var rebuilt = new ArtifactIndex(f.Store);
        Assert.True(rebuilt.TryFind(artifactId, out var jobId));
        Assert.Equal(id, jobId);
    }

    private sealed class BlockingWorker : IWorkerProcess
    {
        public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource Release { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public async Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
        {
            Started.TrySetResult();
            await Release.Task.WaitAsync(ct);
            return new WorkerRun(0, true, "");
        }
    }

    [Fact]
    public async Task A_full_queue_answers_503_with_retry_after_and_leaves_no_job_record()
    {
        var blocking = new BlockingWorker();
        using var f = CoreFactory.WithStubWorkers(blocking, env: new() { ["KOLDERA_CONCURRENCY"] = "1", ["KOLDERA_QUEUE_CAPACITY"] = "1" });
        try
        {
            var first = await f.CreatePdfJob();
            await blocking.Started.Task.WaitAsync(TimeSpan.FromSeconds(30));
            await f.CreatePdfJob(); // fills the single queue slot
            var upload = await f.CompletedUpload(Fixtures.SmallPdf());
            var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" });
            Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
            Assert.Equal("30", r.Headers.RetryAfter!.Delta!.Value.TotalSeconds.ToString());
            Assert.Equal("QUEUE_FULL", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
            Assert.Equal(2, f.Store.AllJobs().Count());
            Assert.NotNull(f.Store.GetJob(first));
        }
        finally
        {
            blocking.Release.TrySetResult();
        }
    }
}
