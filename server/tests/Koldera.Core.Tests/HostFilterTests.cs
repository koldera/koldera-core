using System.Net;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

/// <summary>
/// With KOLDERA_AUTH=none anyone who reaches the port is the owner, so a web page that rebinds its own host name to
/// 127.0.0.1 could drive the instance from a visitor's browser. Only the allowed Host names are answered then.
/// </summary>
public class HostFilterTests
{
    private static async Task<HttpStatusCode> GetWithHost(CoreFactory f, string host, string path = "/v1/info")
    {
        var request = new HttpRequestMessage(HttpMethod.Get, path);
        request.Headers.Host = host;
        return (await f.CreateClient().SendAsync(request)).StatusCode;
    }

    [Theory]
    [InlineData("localhost")]
    [InlineData("localhost:8080")]
    [InlineData("127.0.0.1:9999")]
    [InlineData("[::1]:8080")]
    [InlineData("LOCALHOST:8080")]
    public async Task Auth_none_answers_loopback_host_names_on_any_port(string host)
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none" });
        Assert.Equal(HttpStatusCode.OK, await GetWithHost(f, host));
    }

    [Theory]
    [InlineData("attacker.example")]
    [InlineData("attacker.example:8080")]
    [InlineData("127.0.0.1.attacker.example")]
    [InlineData("10.0.0.5:8080")]
    public async Task Auth_none_refuses_other_host_names(string host)
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none" });
        var request = new HttpRequestMessage(HttpMethod.Get, "/v1/info");
        request.Headers.Host = host;
        var response = await f.CreateClient().SendAsync(request);

        Assert.Equal(HttpStatusCode.MisdirectedRequest, response.StatusCode);
        Assert.Contains("HOST_NOT_ALLOWED", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Auth_none_refuses_a_foreign_host_on_every_path_including_signed_links_and_health()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none" });
        Assert.Equal(HttpStatusCode.MisdirectedRequest, await GetWithHost(f, "attacker.example", "/healthz"));
        Assert.Equal(HttpStatusCode.MisdirectedRequest, await GetWithHost(f, "attacker.example", $"/v1/artifacts/{Guid.NewGuid()}/content?exp=1&sig=x"));
    }

    [Fact]
    public async Task Allowed_hosts_replace_the_default_list()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none", ["KOLDERA_ALLOWED_HOSTS"] = "koldera.internal, Front" });
        Assert.Equal(HttpStatusCode.OK, await GetWithHost(f, "koldera.internal:8080"));
        Assert.Equal(HttpStatusCode.OK, await GetWithHost(f, "front"));
        Assert.Equal(HttpStatusCode.MisdirectedRequest, await GetWithHost(f, "localhost"));
    }

    [Fact]
    public async Task The_public_url_host_is_allowed_as_well()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none", ["KOLDERA_PUBLIC_URL"] = "https://pdf.example.org" });
        Assert.Equal(HttpStatusCode.OK, await GetWithHost(f, "pdf.example.org"));
        Assert.Equal(HttpStatusCode.OK, await GetWithHost(f, "localhost"));
    }

    [Fact]
    public async Task A_star_switches_the_check_off()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_AUTH"] = "none", ["KOLDERA_ALLOWED_HOSTS"] = "*" });
        Assert.Equal(HttpStatusCode.OK, await GetWithHost(f, "anything.example"));
    }

    [Fact]
    public async Task Token_authentication_does_not_filter_hosts()
    {
        // The token is the protection there: a rebinding page cannot read or send it.
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "secret-token" });
        var request = new HttpRequestMessage(HttpMethod.Get, "/v1/info");
        request.Headers.Host = "core.example";
        request.Headers.Authorization = new("Bearer", "secret-token");
        Assert.Equal(HttpStatusCode.OK, (await f.CreateClient().SendAsync(request)).StatusCode);
    }

    [Fact]
    public void Allowed_hosts_are_parsed_and_printed()
    {
        var options = CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_ALLOWED_HOSTS"] = " a.example ,B.example:8080," });
        Assert.Equal(["a.example", "b.example"], options.AllowedHosts);
        Assert.Contains("AllowedHosts = [a.example, b.example]", options.ToString());
        Assert.Equal(["localhost", "127.0.0.1", "[::1]"], CoreOptions.FromEnvironment(new Dictionary<string, string?>()).AllowedHosts);
    }
}
