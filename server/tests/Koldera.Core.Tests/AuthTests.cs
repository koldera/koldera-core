using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class AuthTests
{
    [Theory]
    [InlineData(null, HttpStatusCode.Unauthorized)]
    [InlineData("wrong", HttpStatusCode.Unauthorized)]
    [InlineData("old", HttpStatusCode.OK)]
    [InlineData("new", HttpStatusCode.OK)]
    public async Task Any_configured_token_is_accepted(string? token, HttpStatusCode expected)
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "new, old" });
        var c = f.CreateClient();
        if (token is not null) c.DefaultRequestHeaders.Authorization = new("Bearer", token);
        Assert.Equal(expected, (await c.GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Auth_none_opens_the_api()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none" });
        Assert.Equal(HttpStatusCode.OK, (await f.CreateClient().GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public void Missing_token_generates_one_and_logs_it_once()
    {
        var log = new ListLogger();
        var tokens = TokenAuth.ResolveTokens(CoreOptions.FromEnvironment(new Dictionary<string, string?>()), log);
        Assert.Single(tokens);
        Assert.True(tokens[0].Length >= 32);
        Assert.Single(log.Lines, l => l.Contains(tokens[0]));
        var json = JsonDocument.Parse(log.Lines.Single()).RootElement;
        Assert.Equal("token_generated", json.GetProperty("event").GetString());
        Assert.Equal(tokens[0], json.GetProperty("token").GetString());
    }

    [Fact]
    public async Task Generated_token_appears_in_exactly_one_startup_log_line_and_works()
    {
        using var f = CoreFactory.Create(new());
        var c = f.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized, (await c.GetAsync("/v1/info")).StatusCode);
        var line = Assert.Single(f.Logs, l => l.Contains("token_generated"));
        var token = JsonDocument.Parse(line[line.IndexOf('{')..]).RootElement.GetProperty("token").GetString()!;
        c.DefaultRequestHeaders.Authorization = new("Bearer", token);
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync("/v1/info")).StatusCode);
        Assert.Single(f.Logs, l => l.Contains(token));
    }

    [Fact]
    public async Task Unauthorized_is_problem_json_with_challenge()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var r = await f.CreateClient().GetAsync("/v1/info");
        Assert.Equal("application/problem+json", r.Content.Headers.ContentType?.MediaType);
        Assert.Equal("UNAUTHORIZED", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
        Assert.Contains("Bearer", r.Headers.WwwAuthenticate.ToString());
    }

    [Fact]
    public async Task Healthz_stays_public()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        Assert.Equal(HttpStatusCode.OK, (await f.CreateClient().GetAsync("/healthz")).StatusCode);
    }

    [Fact]
    public async Task Token_comparison_is_exact_and_authorized_client_helper_works()
    {
        Assert.Equal("server", new ServerTokens(["a"]).Resolve("a")?.Kind);
        Assert.Null(new ServerTokens(["abc"]).Resolve("ab"));
        Assert.Null(new ServerTokens(["ab"]).Resolve("abc"));
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        Assert.Equal(HttpStatusCode.OK, (await f.AuthorizedClient().GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task The_scheme_name_is_case_insensitive()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var c = f.CreateClient();
        c.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", "bearer t");
        Assert.Equal(HttpStatusCode.OK, (await c.GetAsync("/v1/info")).StatusCode);
    }

    [Theory]
    [InlineData("Bearer ")]
    [InlineData("Bearer")]
    public async Task A_bearer_header_without_a_token_is_401(string header)
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var c = f.CreateClient();
        c.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", header);
        Assert.Equal(HttpStatusCode.Unauthorized, (await c.GetAsync("/v1/info")).StatusCode);
    }

    [Fact]
    public async Task Two_authorization_headers_are_401_even_when_both_are_valid()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var request = new HttpRequestMessage(HttpMethod.Get, "/v1/info");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer t");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer t");
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.CreateClient().SendAsync(request)).StatusCode);
    }
}
