using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class CorsTests
{
    private const string App = "https://app.example";

    private static HttpRequestMessage Preflight(string path, string origin, string method = "POST", string headers = "authorization,content-type,idempotency-key")
    {
        var r = new HttpRequestMessage(HttpMethod.Options, path);
        r.Headers.Add("Origin", origin);
        r.Headers.Add("Access-Control-Request-Method", method);
        r.Headers.Add("Access-Control-Request-Headers", headers);
        return r;
    }

    private static string? Header(HttpResponseMessage r, string name) => r.Headers.TryGetValues(name, out var v) ? string.Join(",", v) : null;

    [Fact]
    public async Task Preflight_for_a_configured_origin_needs_no_token()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: new() { ["KOLDERA_CORS_ORIGINS"] = App });
        var r = await f.CreateClient().SendAsync(Preflight("/v1/pdf/jobs", App));
        Assert.True(r.IsSuccessStatusCode);
        Assert.Equal(App, Header(r, "Access-Control-Allow-Origin"));
        Assert.Equal("GET, POST, PUT", Header(r, "Access-Control-Allow-Methods"));
        var allowed = Header(r, "Access-Control-Allow-Headers")!;
        foreach (var h in new[] { "authorization", "content-type", "idempotency-key", "prefer" }) Assert.Contains(h, allowed);
    }

    [Fact]
    public async Task Preflight_works_for_a_session_origin_that_is_not_configured()
    {
        // A preflight carries no token, so it is answered for any origin; the actual request is what is enforced.
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var r = await f.CreateClient().SendAsync(Preflight("/v1/uploads", App));
        Assert.True(r.IsSuccessStatusCode);
        Assert.Equal(App, Header(r, "Access-Control-Allow-Origin"));
    }

    [Fact]
    public async Task Actual_response_for_a_session_origin_carries_allow_origin()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var session = await f.MintSession(App);
        var r = await f.SessionClient(session, App).GetAsync("/v1/info");
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(App, Header(r, "Access-Control-Allow-Origin"));
        Assert.Contains("Origin", Header(r, "Vary")!);
    }

    [Fact]
    public async Task Actual_response_for_an_unlisted_origin_without_a_session_has_no_allow_origin()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var c = f.AuthorizedClient();
        c.DefaultRequestHeaders.Add("Origin", "https://evil.example");
        var r = await c.GetAsync("/v1/info");
        Assert.Null(Header(r, "Access-Control-Allow-Origin"));
    }

    [Fact]
    public async Task Actual_response_for_a_configured_origin_carries_allow_origin_and_exposes_headers()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: new() { ["KOLDERA_CORS_ORIGINS"] = App });
        var c = f.AuthorizedClient();
        c.DefaultRequestHeaders.Add("Origin", App);
        var r = await c.GetAsync("/v1/info");
        Assert.Equal(App, Header(r, "Access-Control-Allow-Origin"));
        var exposed = Header(r, "Access-Control-Expose-Headers")!;
        foreach (var h in new[] { "Retry-After", "Preference-Applied", "Location" }) Assert.Contains(h, exposed);
    }

    [Fact]
    public async Task Signed_upload_and_artifact_urls_answer_cross_origin_without_credentials()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());
        var bytes = Fixtures.SmallPdf();
        var d = await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "r.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" });
        var url = (await d.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("uploadUrl").GetString()!;
        var path = new Uri(url).PathAndQuery;

        var pre = await f.CreateClient().SendAsync(Preflight(path, "https://anywhere.example", "PUT", "content-type"));
        Assert.True(pre.IsSuccessStatusCode);
        Assert.Equal("*", Header(pre, "Access-Control-Allow-Origin"));
        Assert.Null(Header(pre, "Access-Control-Allow-Credentials"));

        var put = new HttpRequestMessage(HttpMethod.Put, path) { Content = new ByteArrayContent(bytes) };
        put.Headers.Add("Origin", "https://anywhere.example");
        var r = await f.CreateClient().SendAsync(put);
        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        Assert.Equal("*", Header(r, "Access-Control-Allow-Origin"));

        // Artifact download: even the signature failure must be readable by the page that asked.
        var get = new HttpRequestMessage(HttpMethod.Get, $"/v1/artifacts/{Guid.NewGuid()}/content?exp=1&sig=x");
        get.Headers.Add("Origin", "https://anywhere.example");
        var g = await f.CreateClient().SendAsync(get);
        Assert.Equal("*", Header(g, "Access-Control-Allow-Origin"));
    }
}
