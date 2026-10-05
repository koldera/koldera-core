using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class IdempotencyTests
{
    private const string App = "https://app.example";

    private static object UploadBody(byte[] bytes) =>
        new { fileName = "r.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" };

    [Fact]
    public async Task Same_idempotency_key_returns_the_same_job()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var body = new { uploadId = upload, mode = "analyze", profile = "ua1" };
        var a = await f.PostWithKey("/v1/pdf/jobs", body, "k1");
        var b = await f.PostWithKey("/v1/pdf/jobs", body, "k1");
        Assert.Equal(a.GetProperty("id").GetString(), b.GetProperty("id").GetString());
        Assert.Single(f.Store.AllJobs());
    }

    [Fact]
    public async Task A_replay_keeps_status_and_location()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var body = new { uploadId = upload, mode = "analyze", profile = "ua1" };
        var a = await f.RawPostWithKey("/v1/pdf/jobs", body, "k1b");
        var b = await f.RawPostWithKey("/v1/pdf/jobs", body, "k1b");
        Assert.Equal(HttpStatusCode.Accepted, b.StatusCode);
        Assert.Equal(a.Headers.Location, b.Headers.Location);
        Assert.Equal("true", b.Headers.GetValues("Idempotent-Replayed").Single());
    }

    [Fact]
    public async Task Same_idempotency_key_with_a_different_body_conflicts()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        await f.PostWithKey("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" }, "k2");
        var r = await f.RawPostWithKey("/v1/pdf/jobs", new { uploadId = upload, mode = "fix", profile = "ua1" }, "k2");
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("IDEMPOTENCY_KEY_REUSED", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Upload_declarations_are_idempotent()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var body = UploadBody(Fixtures.SmallPdf());
        var a = await f.PostWithKey("/v1/uploads", body, "u1");
        var b = await f.PostWithKey("/v1/uploads", body, "u1");
        Assert.Equal(a.GetProperty("uploadId").GetString(), b.GetProperty("uploadId").GetString());
    }

    [Fact]
    public async Task Session_mints_are_idempotent()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var body = new { allowedOrigin = App };
        var a = await f.PostWithKey("/v1/client-sessions", body, "m1");
        var b = await f.PostWithKey("/v1/client-sessions", body, "m1");
        Assert.Equal(a.GetProperty("sessionToken").GetString(), b.GetProperty("sessionToken").GetString());
    }

    [Fact]
    public async Task The_same_key_on_another_path_conflicts()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        await f.PostWithKey("/v1/uploads", UploadBody(Fixtures.SmallPdf()), "x");
        var r = await f.RawPostWithKey("/v1/client-sessions", new { allowedOrigin = App }, "x");
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
    }

    [Fact]
    public async Task Keys_are_scoped_to_the_principal()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var s1 = await f.MintSession(App);
        var s2 = await f.MintSession(App);
        var body = UploadBody(Fixtures.SmallPdf());
        var a = await (await f.RawPostWithKey("/v1/uploads", body, "same", f.SessionClient(s1, App))).Content.ReadFromJsonAsync<JsonElement>();
        var b = await (await f.RawPostWithKey("/v1/uploads", body, "same", f.SessionClient(s2, App))).Content.ReadFromJsonAsync<JsonElement>();
        Assert.NotEqual(a.GetProperty("uploadId").GetString(), b.GetProperty("uploadId").GetString());
    }

    [Fact]
    public async Task A_replayed_job_does_not_use_up_a_session_budget()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App, maxJobs: 1);
        var c = f.SessionClient(session, App);
        var bytes = Fixtures.SmallPdf();
        var d = await (await c.PostAsJsonAsync("/v1/uploads", UploadBody(bytes))).Content.ReadFromJsonAsync<JsonElement>();
        (await f.CreateClient().PutAsync(d.GetProperty("uploadUrl").GetString()!, new ByteArrayContent(bytes))).EnsureSuccessStatusCode();
        var body = new { uploadId = d.GetProperty("uploadId").GetGuid(), mode = "analyze", profile = "ua1" };
        Assert.Equal(HttpStatusCode.Accepted, (await f.RawPostWithKey("/v1/pdf/jobs", body, "j", c)).StatusCode);
        Assert.Equal(HttpStatusCode.Accepted, (await f.RawPostWithKey("/v1/pdf/jobs", body, "j", c)).StatusCode);
    }

    [Fact]
    public async Task A_key_is_forgotten_after_24_hours()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var body = UploadBody(Fixtures.SmallPdf());
        var a = await f.PostWithKey("/v1/uploads", body, "ttl");
        f.Clock.Advance(TimeSpan.FromHours(24) + TimeSpan.FromSeconds(1));
        var b = await f.PostWithKey("/v1/uploads", body, "ttl");
        Assert.NotEqual(a.GetProperty("uploadId").GetString(), b.GetProperty("uploadId").GetString());
    }

    [Fact]
    public async Task Failures_are_not_replayed_so_a_retry_can_succeed()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var bytes = Fixtures.SmallPdf();
        var ticket = await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", UploadBody(bytes));
        var t = await ticket.Content.ReadFromJsonAsync<JsonElement>();
        var body = new { uploadId = t.GetProperty("uploadId").GetGuid(), mode = "analyze", profile = "ua1" };
        Assert.Equal(HttpStatusCode.Conflict, (await f.RawPostWithKey("/v1/pdf/jobs", body, "retry")).StatusCode); // upload incomplete
        (await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString()!, new ByteArrayContent(bytes))).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Accepted, (await f.RawPostWithKey("/v1/pdf/jobs", body, "retry")).StatusCode);
    }

    [Fact]
    public async Task A_request_without_a_key_is_never_deduplicated()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var body = new { uploadId = upload, mode = "analyze", profile = "ua1" };
        var c = f.AuthorizedClient();
        var a = await (await c.PostAsJsonAsync("/v1/pdf/jobs", body)).Content.ReadFromJsonAsync<JsonElement>();
        var b = await (await c.PostAsJsonAsync("/v1/pdf/jobs", body)).Content.ReadFromJsonAsync<JsonElement>();
        Assert.NotEqual(a.GetProperty("id").GetString(), b.GetProperty("id").GetString());
    }

    [Fact]
    public async Task Parallel_requests_with_one_key_create_one_job()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var body = new { uploadId = upload, mode = "analyze", profile = "ua1" };
        var ids = await Task.WhenAll(Enumerable.Range(0, 12).Select(_ => Task.Run(async () =>
        {
            var r = await f.RawPostWithKey("/v1/pdf/jobs", body, "burst");
            Assert.Equal(HttpStatusCode.Accepted, r.StatusCode);
            return (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString();
        })));
        Assert.Single(ids.Distinct());
        Assert.Single(f.Store.AllJobs());
    }
}
