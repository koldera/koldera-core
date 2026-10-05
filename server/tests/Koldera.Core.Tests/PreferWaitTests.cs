using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class PreferWaitTests
{
    [Theory]
    [InlineData("wait=30", 30)]
    [InlineData("respond-async, wait=5", 5)]
    [InlineData("wait=1", 1)]
    [InlineData("wait=900", 900)]
    [InlineData("WAIT=7", 7)]
    [InlineData(" wait = 12 ", 12)]
    [InlineData("wait=\"20\"", 20)]
    [InlineData("return=minimal; x=y, wait=3;foo=bar", 3)]
    [InlineData("wait=0", null)]
    [InlineData("wait=901", null)]
    [InlineData("wait=-1", null)]
    [InlineData("wait=+5", null)]
    [InlineData("wait=1.5", null)]
    [InlineData("wait=abc", null)]
    [InlineData("wait=", null)]
    [InlineData("wait", null)]
    [InlineData("wait=99999999999999999999", null)]
    [InlineData("respond-async", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void Parses_the_wait_preference(string? header, int? seconds) =>
        Assert.Equal(seconds is null ? (TimeSpan?)null : TimeSpan.FromSeconds(seconds.Value), PreferWait.Parse(header));

    [Fact]
    public void The_first_wait_preference_decides()
    {
        Assert.Null(PreferWait.Parse("wait=0, wait=5"));
        Assert.Equal(TimeSpan.FromSeconds(5), PreferWait.Parse("wait=5, wait=0"));
    }

    private static HttpRequestMessage Post(string path, object body, string? prefer, string? key = null)
    {
        var req = new HttpRequestMessage(HttpMethod.Post, path) { Content = JsonContent.Create(body) };
        if (prefer is not null) req.Headers.TryAddWithoutValidation("Prefer", prefer);
        if (key is not null) req.Headers.Add("Idempotency-Key", key);
        return req;
    }

    private static async Task<string> State(HttpResponseMessage r) => (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("state").GetString()!;

    [Fact]
    public async Task Prefer_wait_returns_the_finished_job_with_200_and_says_so()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" }, "wait=30"));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("completed", await State(r));
        Assert.Equal("wait=30", r.Headers.GetValues("Preference-Applied").Single());
        Assert.NotNull(r.Headers.Location);
    }

    [Fact]
    public async Task Without_the_preference_the_answer_is_202_and_carries_no_preference_applied()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" }, null));
        Assert.Equal(HttpStatusCode.Accepted, r.StatusCode);
        Assert.False(r.Headers.Contains("Preference-Applied"));
    }

    [Theory]
    [InlineData("wait=0")]
    [InlineData("wait=901")]
    [InlineData("wait=soon")]
    [InlineData("respond-async")]
    public async Task An_invalid_preference_is_ignored(string prefer)
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var r = await f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" }, prefer));
        Assert.Equal(HttpStatusCode.Accepted, r.StatusCode);
        Assert.False(r.Headers.Contains("Preference-Applied"));
    }

    [Fact]
    public async Task A_wait_that_expires_returns_202_with_the_job_still_unfinished()
    {
        var gate = new TaskCompletionSource();
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(gate.Task));
        try
        {
            var upload = await f.CompletedUpload(Fixtures.SmallPdf());
            var sending = f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" }, "wait=1"));
            // The server waits on its own (fake) clock: move it on until the wait has expired.
            for (var i = 0; i < 200 && !sending.IsCompleted; i++)
            {
                f.Clock.Advance(TimeSpan.FromSeconds(1));
                await Task.Delay(25);
            }
            Assert.True(sending.IsCompleted, "the wait did not end");
            var r = await sending;
            Assert.Equal(HttpStatusCode.Accepted, r.StatusCode);
            Assert.NotEqual("completed", await State(r));
            Assert.Equal("wait=1", r.Headers.GetValues("Preference-Applied").Single());
        }
        finally { gate.TrySetResult(); }
    }

    [Fact]
    public async Task Aborting_the_request_ends_the_wait_and_the_job_still_finishes()
    {
        var gate = new TaskCompletionSource();
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(gate.Task));
        try
        {
            var upload = await f.CompletedUpload(Fixtures.SmallPdf());
            using var abort = new CancellationTokenSource();
            var sending = f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" }, "wait=600"), abort.Token);
            for (var i = 0; i < 200 && !f.Store.AllJobs().Any(); i++) await Task.Delay(25);
            var id = Assert.Single(f.Store.AllJobs()).Id;
            await abort.CancelAsync();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => sending);
            gate.SetResult();
            Assert.Equal("completed", (await f.WaitForJob(id)).GetProperty("state").GetString());
            Assert.Single(f.Store.AllJobs());
        }
        finally { gate.TrySetResult(); }
    }

    [Fact]
    public async Task An_idempotent_replay_returns_what_was_originally_returned()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var body = new { uploadId = upload, mode = "analyze", profile = "ua1" };
        var first = await f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", body, "wait=30", "wk1"));
        var firstText = await first.Content.ReadAsStringAsync();
        // A retry without the preference, and one with a different one, both see the stored 200.
        foreach (var prefer in new string?[] { null, "wait=5" })
        {
            var again = await f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", body, prefer, "wk1"));
            Assert.Equal(HttpStatusCode.OK, again.StatusCode);
            Assert.Equal("true", again.Headers.GetValues("Idempotent-Replayed").Single());
            Assert.Equal("wait=30", again.Headers.GetValues("Preference-Applied").Single());
            Assert.Equal(first.Headers.Location, again.Headers.Location);
            Assert.Equal(firstText, await again.Content.ReadAsStringAsync());
        }
        Assert.Single(f.Store.AllJobs());
    }

    [Fact]
    public async Task A_replay_of_a_202_stays_202_even_when_the_retry_asks_to_wait()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var upload = await f.CompletedUpload(Fixtures.SmallPdf());
        var body = new { uploadId = upload, mode = "analyze", profile = "ua1" };
        var first = await f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", body, null, "wk2"));
        Assert.Equal(HttpStatusCode.Accepted, first.StatusCode);
        var again = await f.AuthorizedClient().SendAsync(Post("/v1/pdf/jobs", body, "wait=30", "wk2"));
        Assert.Equal(HttpStatusCode.Accepted, again.StatusCode);
        Assert.False(again.Headers.Contains("Preference-Applied"));
    }

    [Fact]
    public async Task The_html_to_pdf_route_honours_the_preference()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), htmlRender: StubResults.HtmlRenderCompleted(), inspect: StubResults.InspectResult(Fixtures.Sha256(Fixtures.SmallPdf())));
        var upload = await f.CompletedHtmlUpload();
        var r = await f.AuthorizedClient().SendAsync(Post("/v1/html-to-pdf/jobs", new { uploadId = upload, mode = "generate_analyze", profile = "ua1" }, "wait=30"));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("completed", await State(r));
        Assert.Equal("wait=30", r.Headers.GetValues("Preference-Applied").Single());
    }

    [Fact]
    public async Task The_web_route_honours_the_preference()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), web: StubResults.WebCompleted());
        var r = await f.AuthorizedClient().SendAsync(Post("/v1/web/jobs", new { url = "https://example.com/" }, "wait=30"));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("completed", await State(r));
    }

    [Fact]
    public async Task The_documents_route_honours_the_preference()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeClean(), documentRender: StubResults.DocumentRenderCompleted());
        var req = new HttpRequestMessage(HttpMethod.Post, "/v1/documents/jobs")
        {
            Content = new StringContent(File.ReadAllText("Fixtures/documents/invoice.json"), Encoding.UTF8, "application/json"),
        };
        req.Headers.Add("Prefer", "wait=30");
        var r = await f.AuthorizedClient().SendAsync(req);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("completed", await State(r));
        Assert.Equal("wait=30", r.Headers.GetValues("Preference-Applied").Single());
    }

    [Fact]
    public async Task The_cors_exposure_lists_preference_applied_on_a_waited_answer()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: new() { ["KOLDERA_CORS_ORIGINS"] = "https://app.example" });
        var session = await f.MintSession("https://app.example");
        var bytes = Fixtures.SmallPdf();
        var client = f.SessionClient(session, "https://app.example");
        var declared = await client.PostAsJsonAsync("/v1/uploads", new { fileName = "r.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" });
        declared.EnsureSuccessStatusCode();
        var ticket = await declared.Content.ReadFromJsonAsync<JsonElement>();
        (await f.CreateClient().PutAsync(ticket.GetProperty("uploadUrl").GetString()!, new ByteArrayContent(bytes))).EnsureSuccessStatusCode();
        var r = await client.SendAsync(Post("/v1/pdf/jobs", new { uploadId = ticket.GetProperty("uploadId").GetGuid(), mode = "analyze", profile = "ua1" }, "wait=30"));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Contains("Preference-Applied", r.Headers.GetValues("Access-Control-Expose-Headers").Single());
    }
}
