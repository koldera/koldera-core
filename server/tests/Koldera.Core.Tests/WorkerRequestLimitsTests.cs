using System.Text.Json;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>A missing limit means unlimited, so requests omit unset limits and still satisfy the schemas.</summary>
public sealed class WorkerRequestLimitsTests
{
    private static readonly Guid Id = Guid.Parse("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a");

    private static JsonElement Parse(string json) => JsonDocument.Parse(json).RootElement;

    [Fact]
    public void A_pdf_request_without_limits_validates_and_has_no_limits_property()
    {
        var json = WorkerContracts.SerializeRequest(new WorkerRequest("1.0", Id, WorkerRequest.CanonicalInputPath, WorkerRequest.CanonicalOutputDirectory, "fix", "ua1", null, null, null, null));

        Assert.False(Parse(json).TryGetProperty("limits", out _));
    }

    [Fact]
    public void A_pdf_request_with_only_some_limits_writes_just_those()
    {
        var json = WorkerContracts.SerializeRequest(new WorkerRequest("1.0", Id, WorkerRequest.CanonicalInputPath, WorkerRequest.CanonicalOutputDirectory, "analyze", "ua1", null, null, null, null, new WorkerLimits(MaxBytes: 1000, MaxPages: null, TimeoutSeconds: null)));

        var limits = Parse(json).GetProperty("limits");
        Assert.Equal(1000, limits.GetProperty("maxBytes").GetInt64());
        Assert.False(limits.TryGetProperty("maxPages", out _));
        Assert.False(limits.TryGetProperty("timeoutSeconds", out _));
    }

    [Fact]
    public void Html_and_web_requests_without_limits_validate()
    {
        var html = HtmlWorkerContracts.SerializeRequest(new HtmlRendererRequest("1.0", Id, HtmlRendererRequest.CanonicalInputPath, HtmlRendererRequest.CanonicalOutputDirectory, null, new HtmlRendererPage("A4", true)));
        Assert.False(Parse(html).TryGetProperty("limits", out _));

        var web = WebWorkerContracts.SerializeRequest(new WebWorkerRequest("1.0", Id, "https://example.test/", WebWorkerRequest.CanonicalOutputDirectory, null, new WebWorkerPolicy(false, null)));
        Assert.False(Parse(web).TryGetProperty("limits", out _));
    }

    [Fact]
    public void Order_requests_without_limits_validate()
    {
        var sha = new string('a', 64);
        var pdf = OrderWorkerContracts.SerializePdfReorderRequest(new PdfReorderRequest("1.0", Id, PdfReorderRequest.CanonicalInputPath, PdfReorderRequest.CanonicalOutputPath, sha, [0], [1, 0], null));
        Assert.False(Parse(pdf).TryGetProperty("limits", out _));

        var semantic = OrderWorkerContracts.SerializeSemanticRequest(new SemanticRequest("1.0", Id, SemanticRequest.CanonicalInputPath, null));
        Assert.False(Parse(semantic).TryGetProperty("limits", out _));

        var html = OrderWorkerContracts.SerializeHtmlReorderRequest(new HtmlReorderRequest("1.0", Id, HtmlRendererRequest.CanonicalInputPath, HtmlRendererRequest.CanonicalOutputDirectory, sha, "/html/body", [1, 0], null, new HtmlRendererPage("A4", true)));
        Assert.False(Parse(html).TryGetProperty("limits", out _));
    }
}
