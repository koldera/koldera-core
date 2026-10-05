using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

// A text/plain form POST is a "simple" cross-site request that skips the CORS preflight, so every JSON route must refuse it.
public class ContentTypeTests
{
    public static IEnumerable<object[]> Routes()
    {
        foreach (var auth in new[] { false, true })
            foreach (var path in new[]
            {
                "/v1/uploads", "/v1/pdf/jobs", "/v1/html-to-pdf/jobs", "/v1/web/jobs", "/v1/documents/jobs",
                "/v1/jobs/00000000-0000-0000-0000-000000000001/reading-order/remediations", "/v1/client-sessions",
            })
                yield return [auth, path];
    }

    private static CoreFactory Server(bool authNone) => authNone
        ? CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none" })
        : CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted());

    private static HttpRequestMessage Post(string path, string contentType)
    {
        var content = new StringContent("{\"allowedOrigin\":\"https://app.example\"}", Encoding.UTF8);
        content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        return new HttpRequestMessage(HttpMethod.Post, path) { Content = content };
    }

    [Theory]
    [MemberData(nameof(Routes))]
    public async Task A_text_plain_body_is_refused_on_every_post_route(bool authNone, string path)
    {
        using var f = Server(authNone);
        var c = authNone ? f.CreateClient() : f.AuthorizedClient();
        var r = await c.SendAsync(Post(path, "text/plain"));
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
        Assert.Equal("application/problem+json", r.Content.Headers.ContentType?.MediaType);
        Assert.Equal("UNSUPPORTED_MEDIA_TYPE", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Theory]
    [MemberData(nameof(Routes))]
    public async Task Json_with_a_charset_parameter_is_not_refused_on_the_media_type(bool authNone, string path)
    {
        using var f = Server(authNone);
        var c = authNone ? f.CreateClient() : f.AuthorizedClient();
        var r = await c.SendAsync(Post(path, "application/json; charset=utf-8"));
        Assert.NotEqual(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
    }

    [Fact]
    public async Task A_missing_content_type_is_refused()
    {
        using var f = Server(false);
        var r = await f.AuthorizedClient().PostAsync("/v1/client-sessions", new ByteArrayContent("{}"u8.ToArray()));
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
    }

    [Fact]
    public async Task Json_with_a_charset_mints_a_session()
    {
        using var f = Server(true);
        var r = await f.CreateClient().SendAsync(Post("/v1/client-sessions", "application/json; charset=utf-8"));
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
    }

    [Fact]
    public async Task The_signed_upload_put_still_takes_raw_bytes()
    {
        using var f = Server(false);
        var upload = await f.CompletedUpload(Fixtures.SmallPdf()); // PUT of application/octet-stream style bytes
        Assert.NotEqual(Guid.Empty, upload);
    }

    [Theory]
    [MemberData(nameof(Routes))]
    public async Task An_empty_json_body_reaches_the_route_instead_of_being_a_415(bool authNone, string path)
    {
        using var f = Server(authNone);
        var c = authNone ? f.CreateClient() : f.AuthorizedClient();
        var content = new ByteArrayContent([]);
        content.Headers.ContentType = MediaTypeHeaderValue.Parse("application/json");
        var r = await c.PostAsync(path, content);
        // The route's own validation answers (400 for a missing required body, 422 for a field rule), never the media-type refusal.
        Assert.NotEqual(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
        Assert.InRange((int)r.StatusCode, 400, 499);
    }

    [Theory]
    [InlineData("application/jsonx")]
    [InlineData("application/json-patch+json")]
    [InlineData("xapplication/json")]
    public async Task A_media_type_that_only_starts_like_json_is_a_415(string contentType)
    {
        using var f = Server(false);
        var r = await f.AuthorizedClient().SendAsync(Post("/v1/client-sessions", contentType));
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, r.StatusCode);
    }
}
