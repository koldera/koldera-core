using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class WebJobTests
{
    private static CoreFactory Factory(StubWorker? web = null, Dictionary<string, string?>? env = null, string? features = null) =>
        CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: env, web: web ?? StubResults.WebCompleted(), features: features);

    [Theory]
    [InlineData("http://127.0.0.1/")]
    [InlineData("http://10.0.0.5/")]
    [InlineData("http://[::1]/")]
    [InlineData("http://localhost/")]
    [InlineData("file:///etc/passwd")]
    [InlineData("https://user:pw@example.com/")]
    // Beyond the brief: IPv6 and IPv4-mapped literals, reserved names, ports, plain http (off by default).
    [InlineData("https://[::ffff:127.0.0.1]/")]
    [InlineData("https://[::ffff:10.1.2.3]/")]
    [InlineData("https://[fe80::1]/")]
    [InlineData("https://[fd00::1]/")]
    [InlineData("https://169.254.169.254/latest/meta-data")]
    [InlineData("https://printer.local/")]
    [InlineData("https://db.internal/")]
    [InlineData("https://app.localhost/")]
    [InlineData("https://intranet/")]
    [InlineData("https://example.com:8443/")]
    [InlineData("http://example.com/")]
    [InlineData("")]
    public async Task Private_or_malformed_targets_are_rejected_at_acceptance(string url)
    {
        var web = StubResults.WebCompleted();
        using var f = Factory(web);
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/web/jobs", new { url });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Empty(web.Calls);
    }

    [Fact]
    public async Task A_missing_url_is_a_validation_error()
    {
        using var f = Factory();
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/web/jobs", new { });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
    }

    [Fact]
    public async Task Web_feature_off_returns_404()
    {
        using var f = Factory(features: "pdf,html");
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/web/jobs", new { url = "https://example.com/" });
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
    }

    [Fact]
    public async Task The_worker_policy_carries_the_allow_http_switch_and_proxy()
    {
        var web = StubResults.WebCompleted();
        using var f = Factory(web, new() { ["KOLDERA_WEB_ALLOW_HTTP"] = "true", ["KOLDERA_WEB_PROXY"] = "http://proxy.invalid:3128" });
        await f.WaitForJob(await f.CreateWebJob("http://example.com/"));
        var policy = JsonDocument.Parse(web.Calls.Single().RequestJson).RootElement.GetProperty("policy");
        Assert.True(policy.GetProperty("allowHttp").GetBoolean());
        Assert.Equal("http://proxy.invalid:3128", policy.GetProperty("proxyServer").GetString());
    }

    [Fact]
    public async Task By_default_the_worker_policy_forbids_http_names_no_proxy_and_sets_no_limits()
    {
        var web = StubResults.WebCompleted();
        using var f = Factory(web);
        await f.WaitForJob(await f.CreateWebJob("https://example.com/"));
        var request = JsonDocument.Parse(web.Calls.Single().RequestJson).RootElement;
        Assert.False(request.GetProperty("policy").GetProperty("allowHttp").GetBoolean());
        Assert.Equal(JsonValueKind.Null, request.GetProperty("policy").GetProperty("proxyServer").ValueKind);
        Assert.False(request.TryGetProperty("limits", out _));
    }

    [Fact]
    public async Task Configured_limits_reach_the_scanner_and_the_timeout_is_passed_unchanged()
    {
        var web = StubResults.WebCompleted();
        using var f = Factory(web, new() { ["KOLDERA_WEB_MAX_RESPONSE_BYTES"] = "5000000", ["KOLDERA_WEB_MAX_REQUESTS"] = "40", ["KOLDERA_JOB_TIMEOUT_SECONDS"] = "90" });
        await f.WaitForJob(await f.CreateWebJob("https://example.com/"));
        var call = web.Calls.Single();
        var limits = JsonDocument.Parse(call.RequestJson).RootElement.GetProperty("limits");
        Assert.Equal(5000000, limits.GetProperty("maxResponseBytes").GetInt64());
        Assert.Equal(40, limits.GetProperty("maxRequests").GetInt32());
        Assert.Equal(90, limits.GetProperty("timeoutSeconds").GetInt32());
        Assert.Equal(TimeSpan.FromSeconds(90), call.Timeout);
    }

    [Fact]
    public async Task The_query_and_fragment_are_stripped_before_storing_and_never_echoed_or_logged()
    {
        var web = StubResults.WebCompleted();
        using var f = Factory(web);
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/web/jobs", new { url = "https://example.com/a/b?token=SECRET#frag" });
        Assert.Equal(HttpStatusCode.Accepted, r.StatusCode);
        var body = await r.Content.ReadAsStringAsync();
        Assert.DoesNotContain("SECRET", body);
        var accepted = JsonDocument.Parse(body).RootElement;
        Assert.Equal("https://example.com/a/b", accepted.GetProperty("targetUrl").GetString());
        var id = accepted.GetProperty("id").GetGuid();
        await f.WaitForJob(id);
        Assert.Equal("https://example.com/a/b", f.Store.GetJob(id)!.TargetUrl);
        Assert.Equal("https://example.com/a/b", JsonDocument.Parse(web.Calls.Single().RequestJson).RootElement.GetProperty("url").GetString());
        Assert.DoesNotContain(f.Logs, l => l.Contains("SECRET") || l.Contains("example.com/a/b"));
    }

    [Fact]
    public async Task Completed_scan_adds_the_manual_review_finding()
    {
        using var f = Factory();
        var id = await f.CreateWebJob("https://example.com/");
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Equal("wcag22aa_automated", job.GetProperty("validation").GetProperty("profile").GetString());
        Assert.Equal("failed", job.GetProperty("validation").GetProperty("status").GetString());
        Assert.Equal("image-alt", job.GetProperty("validation").GetProperty("failedRules")[0].GetProperty("ruleId").GetString());
        var items = (await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/findings")).GetProperty("items");
        Assert.Contains(items.EnumerateArray(), i => i.GetProperty("ruleId").GetString() == "MANUAL_REVIEW_REQUIRED");
        Assert.Contains(items.EnumerateArray(), i => i.GetProperty("ruleId").GetString() == "image-alt");
        var artifacts = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        Assert.Equal("web_report", artifacts.GetProperty("items")[0].GetProperty("kind").GetString());
    }

    [Fact]
    public async Task A_scan_without_violations_passes_the_machine_checks_but_still_needs_manual_review()
    {
        using var f = Factory(StubResults.WebCompleted(violations: 0));
        var job = await f.WaitForJob(await f.CreateWebJob("https://example.com/"));
        Assert.Equal("passed", job.GetProperty("validation").GetProperty("status").GetString());
        Assert.True(job.GetProperty("manualReviewRequired").GetBoolean());
    }
}
