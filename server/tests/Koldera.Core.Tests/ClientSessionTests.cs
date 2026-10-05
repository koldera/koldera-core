using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class ClientSessionTests
{
    private const string App = "https://app.example";

    [Fact]
    public async Task Session_token_reads_only_its_own_jobs()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var serverJob = await f.CreatePdfJob("analyze");
        var session = await f.MintSession(App);
        var c = f.SessionClient(session, App);
        Assert.Equal(HttpStatusCode.NotFound, (await c.GetAsync($"/v1/jobs/{serverJob}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await c.GetAsync($"/v1/jobs/{serverJob}/artifacts")).StatusCode);

        var own = await f.CreatePdfJobAs(session, App);
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync($"/v1/jobs/{own}")).StatusCode);
        // Another session of the same origin is a different owner.
        var other = f.SessionClient(await f.MintSession(App), App);
        Assert.Equal(HttpStatusCode.NotFound, (await other.GetAsync($"/v1/jobs/{own}")).StatusCode);
    }

    [Fact]
    public async Task Session_token_from_another_origin_is_refused()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App);
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.SessionClient(session, "https://evil.example").GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Session_token_without_an_origin_header_is_refused()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App);
        var c = f.CreateClient();
        c.DefaultRequestHeaders.Authorization = new("Bearer", session);
        Assert.Equal(HttpStatusCode.Unauthorized, (await c.GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Session_job_budget_is_enforced()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App, maxJobs: 1);
        await f.CreatePdfJobAs(session, App);
        var r = await f.TryCreatePdfJobAs(session, App);
        Assert.Equal(HttpStatusCode.TooManyRequests, r.StatusCode);
        Assert.Equal("SESSION_JOB_LIMIT", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Session_cannot_mint_sessions()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App);
        var r = await f.SessionClient(session, App).PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = App });
        Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
    }

    [Fact]
    public async Task Session_may_read_info()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App, scopes: ["jobs:create"]);
        Assert.Equal(HttpStatusCode.OK, (await f.SessionClient(session, App).GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Request_outside_the_session_scopes_is_forbidden()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var readOnly = await f.MintSession(App, scopes: ["jobs:read"]);
        var r = await f.SessionClient(readOnly, App).PostAsJsonAsync("/v1/uploads", new { fileName = "a.pdf", sizeBytes = 1, sha256 = new string('a', 64), contentType = "application/pdf" });
        Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
        Assert.Equal("INSUFFICIENT_SCOPE", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());

        var createOnly = await f.MintSession(App, scopes: ["jobs:create"]);
        var g = await f.SessionClient(createOnly, App).GetAsync($"/v1/jobs/{Guid.NewGuid()}");
        Assert.Equal(HttpStatusCode.Forbidden, g.StatusCode);
    }

    [Fact]
    public async Task Mint_response_has_the_sdk_fields_and_the_requested_expiry()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = App + "/", ttlSeconds = 120 });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        var body = await r.Content.ReadFromJsonAsync<JsonElement>();
        var token = body.GetProperty("sessionToken").GetString()!;
        Assert.StartsWith("v1.", token);
        Assert.Equal(3, token.Split('.').Length);
        // Core has no publishable keys; the field stays for SDK compatibility.
        Assert.Equal(JsonValueKind.Null, body.GetProperty("publishableKey").ValueKind);
        Assert.Equal(f.Clock.GetUtcNow().AddSeconds(120), body.GetProperty("expiresAt").GetDateTimeOffset(), TimeSpan.FromSeconds(1));
    }

    [Theory]
    [InlineData("not-an-origin")]
    [InlineData("https://app.example/path")]
    [InlineData("ftp://app.example")]
    [InlineData("")]
    public async Task Mint_rejects_a_bad_origin(string origin)
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = origin });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(3601)]
    public async Task Mint_rejects_a_ttl_outside_one_second_to_sixty_minutes(int ttl)
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = App, ttlSeconds = ttl });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task Mint_rejects_unknown_scopes()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = App, scopes = new[] { "admin" } });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task Mint_needs_a_token()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var r = await f.CreateClient().PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = App });
        Assert.Equal(HttpStatusCode.Unauthorized, r.StatusCode);
    }

    [Fact]
    public async Task Default_ttl_is_fifteen_minutes_and_the_token_expires()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App);
        var c = f.SessionClient(session, App);
        f.Clock.Advance(TimeSpan.FromMinutes(14));
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync("/v1/info")).StatusCode);
        f.Clock.Advance(TimeSpan.FromMinutes(2));
        Assert.Equal(HttpStatusCode.Unauthorized, (await c.GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Tampered_payload_or_signature_is_refused()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App);
        var parts = session.Split('.');
        // The original signature over a payload that claims another origin.
        var forged = Convert.ToBase64String(Encoding.UTF8.GetBytes("{\"sid\":\"x\",\"origin\":\"https://evil.example\",\"scopes\":[\"jobs:create\",\"jobs:read\"],\"maxJobs\":99,\"exp\":9999999999}"))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.SessionClient($"v1.{forged}.{parts[2]}", "https://evil.example").GetAsync("/v1/info")).StatusCode);
        var flipped = parts[2][..^2] + (parts[2][^2] == 'A' ? 'B' : 'A') + parts[2][^1];
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.SessionClient($"v1.{parts[1]}.{flipped}", App).GetAsync("/v1/info")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.SessionClient($"v1.{parts[1]}", App).GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Wrong_version_prefix_is_refused()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var parts = (await f.MintSession(App)).Split('.');
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.SessionClient($"v2.{parts[1]}.{parts[2]}", App).GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task A_session_from_another_signing_key_is_refused()
    {
        static string Key(byte b) => Convert.ToBase64String(Enumerable.Repeat(b, 32).ToArray());
        using var a = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: new() { ["KOLDERA_SIGNING_KEY"] = Key(1) });
        using var b = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: new() { ["KOLDERA_SIGNING_KEY"] = Key(2) });
        var session = await a.MintSession(App);
        Assert.Equal(HttpStatusCode.Unauthorized, (await b.SessionClient(session, App).GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Auth_none_still_mints_sessions()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none" });
        var r = await f.CreateClient().PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = App });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
    }
}
