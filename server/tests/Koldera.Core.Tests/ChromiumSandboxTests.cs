using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class ChromiumSandboxTests
{
    private static Dictionary<string, string?> Env(string? value) => new() { ["KOLDERA_CHROMIUM_SANDBOX"] = value };

    [Theory]
    [InlineData(null, false)]
    [InlineData("", false)]
    [InlineData("false", false)]
    [InlineData("FALSE", false)]
    [InlineData("true", true)]
    [InlineData(" True ", true)]
    public void The_sandbox_option_defaults_to_off(string? value, bool expected) =>
        Assert.Equal(expected, CoreOptions.FromEnvironment(Env(value)).ChromiumSandbox);

    [Theory]
    [InlineData("1")]
    [InlineData("yes")]
    public void Anything_but_true_or_false_is_refused(string value)
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(Env(value)));
        Assert.Contains("KOLDERA_CHROMIUM_SANDBOX", ex.Message);
    }

    [Theory]
    [InlineData("true", true)]
    [InlineData(null, false)]
    public async Task A_web_job_request_carries_the_option_only_when_set(string? value, bool present)
    {
        var web = StubResults.WebCompleted();
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: Env(value), web: web);
        await f.WaitForJob(await f.CreateWebJob("https://example.com/"));
        var policy = JsonDocument.Parse(Assert.Single(web.Calls).RequestJson).RootElement.GetProperty("policy");
        Assert.Equal(present, policy.TryGetProperty("chromiumSandbox", out var v) && v.GetBoolean());
        if (!present) Assert.False(policy.TryGetProperty("chromiumSandbox", out _));
    }

    [Theory]
    [InlineData("true", true)]
    [InlineData(null, false)]
    public async Task An_html_render_request_carries_the_option_only_when_set(string? value, bool present)
    {
        var render = StubResults.HtmlRenderCompleted();
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), env: Env(value), htmlRender: render, inspect: StubResults.InspectResult(Fixtures.Sha256(Fixtures.SmallPdf())));
        await f.WaitForJob(await f.CreateHtmlJob());
        var root = JsonDocument.Parse(Assert.Single(render.Calls).RequestJson).RootElement;
        Assert.Equal(present, root.TryGetProperty("policy", out var policy) && policy.GetProperty("chromiumSandbox").GetBoolean());
    }
}
