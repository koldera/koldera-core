using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.DependencyInjection;

namespace Koldera.Core.Tests;

/// <summary>Defaults that hold without any configuration: document body cap, image upload cap, job deadline, test-only switches.</summary>
public class ProductionLimitsTests
{
    private static readonly string Document = File.ReadAllText("Fixtures/documents/invoice.json");

    private static CoreFactory DocumentFactory(Dictionary<string, string?>? env = null) =>
        CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeClean(), env: env, documentRender: StubResults.DocumentRenderCompleted());

    private static async Task AssertTooLarge(HttpResponseMessage r)
    {
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("FILE_TOO_LARGE", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    /// <summary>A body without a Content-Length (chunked), so only counting the bytes can refuse it.</summary>
    private sealed class UnsizedContent(string text) : HttpContent
    {
        public UnsizedContent Typed() { Headers.ContentType = new("application/json"); return this; }
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) => stream.WriteAsync(Encoding.UTF8.GetBytes(text)).AsTask();
        protected override bool TryComputeLength(out long length) { length = 0; return false; }
    }

    [Fact]
    public void Unset_limits_get_safe_defaults()
    {
        var l = CoreOptions.FromEnvironment(new Dictionary<string, string?>()).Limits;
        Assert.Equal(10L * 1024 * 1024, l.DocumentMaxBytes);
        Assert.Equal(20L * 1024 * 1024, l.ImageMaxBytes);
        Assert.Equal(900, l.JobTimeoutSeconds);
    }

    [Fact]
    public void Limits_can_be_overridden()
    {
        var l = CoreOptions.FromEnvironment(new Dictionary<string, string?>
        {
            ["KOLDERA_DOCUMENT_MAX_BYTES"] = "1000", ["KOLDERA_IMAGE_MAX_BYTES"] = "2000", ["KOLDERA_JOB_TIMEOUT_SECONDS"] = "60",
        }).Limits;
        Assert.Equal(1000, l.DocumentMaxBytes);
        Assert.Equal(2000, l.ImageMaxBytes);
        Assert.Equal(60, l.JobTimeoutSeconds);
    }

    [Theory]
    [InlineData("KOLDERA_DOCUMENT_MAX_BYTES")]
    [InlineData("KOLDERA_IMAGE_MAX_BYTES")]
    public void Invalid_new_limits_name_their_variable(string key)
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(new Dictionary<string, string?> { [key] = "0" }));
        Assert.Equal($"{key}: expected a positive integer", ex.Message);
    }

    [Fact]
    public async Task A_document_body_over_the_cap_is_refused_and_one_within_it_is_accepted()
    {
        var size = Encoding.UTF8.GetByteCount(Document);
        using var f = DocumentFactory(new() { ["KOLDERA_DOCUMENT_MAX_BYTES"] = (size - 1).ToString() });
        await AssertTooLarge(await f.AuthorizedClient().PostAsync("/v1/documents/jobs", new StringContent(Document, Encoding.UTF8, "application/json")));
        Assert.Empty(f.Store.AllJobs());

        using var g = DocumentFactory(new() { ["KOLDERA_DOCUMENT_MAX_BYTES"] = size.ToString() });
        var ok = await g.AuthorizedClient().PostAsync("/v1/documents/jobs", new StringContent(Document, Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.Accepted, ok.StatusCode);
    }

    [Fact]
    public async Task A_chunked_document_body_over_the_cap_is_refused()
    {
        using var f = DocumentFactory(new() { ["KOLDERA_DOCUMENT_MAX_BYTES"] = "100" });
        await AssertTooLarge(await f.AuthorizedClient().PostAsync("/v1/documents/jobs", new UnsizedContent(Document).Typed()));
        Assert.Empty(f.Store.AllJobs());
    }

    [Fact]
    public async Task The_idempotency_layer_does_not_buffer_a_document_over_the_cap()
    {
        using var f = DocumentFactory(new() { ["KOLDERA_DOCUMENT_MAX_BYTES"] = "100" });
        foreach (var content in new HttpContent[] { new StringContent(Document, Encoding.UTF8, "application/json"), new UnsizedContent(Document).Typed() })
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/documents/jobs") { Content = content };
            request.Headers.Add("Idempotency-Key", "doc-" + Guid.NewGuid());
            await AssertTooLarge(await f.AuthorizedClient().SendAsync(request));
        }
        Assert.Empty(f.Store.AllJobs());
    }

    [Theory]
    [InlineData("image/png")]
    [InlineData("image/jpeg")]
    [InlineData("image/svg+xml")]
    public async Task Image_uploads_are_capped_by_default_and_by_configuration(string contentType)
    {
        object Declare(long size) => new { fileName = "a.img", sizeBytes = size, sha256 = new string('a', 64), contentType };

        using var open = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        await AssertTooLarge(await open.AuthorizedClient().PostAsJsonAsync("/v1/uploads", Declare(20L * 1024 * 1024 + 1)));
        var withinDefault = await open.AuthorizedClient().PostAsJsonAsync("/v1/uploads", Declare(1024));
        Assert.Equal(20L * 1024 * 1024, (await withinDefault.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("maxBytes").GetInt64());

        using var capped = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t", ["KOLDERA_IMAGE_MAX_BYTES"] = "100" });
        await AssertTooLarge(await capped.AuthorizedClient().PostAsJsonAsync("/v1/uploads", Declare(101)));
        var ok = await capped.AuthorizedClient().PostAsJsonAsync("/v1/uploads", Declare(100));
        Assert.Equal(HttpStatusCode.Created, ok.StatusCode);
        Assert.Equal(100, (await ok.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("maxBytes").GetInt64());
    }

    [Theory]
    [InlineData("Production")]
    [InlineData("Staging")]
    public void The_scanner_loopback_switch_is_refused_outside_development(string environment)
    {
        var ex = Assert.Throws<InvalidOperationException>(() => new WorkerOptions { AllowLoopbackForTests = true }.EnsureTestSwitchesAllowed(environment));
        Assert.Contains("Workers:AllowLoopbackForTests", ex.Message);
        Assert.Contains(environment, ex.Message);
    }

    [Fact]
    public void The_scanner_loopback_switch_is_accepted_in_development_and_absent_switches_anywhere()
    {
        new WorkerOptions { AllowLoopbackForTests = true }.EnsureTestSwitchesAllowed("Development");
        new WorkerOptions().EnsureTestSwitchesAllowed("Production");
    }

    [Fact]
    public void A_production_server_with_the_loopback_switch_does_not_start()
    {
        // Host settings reach the configuration before Program reads the Workers section (like Workers__ variables do).
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        using var production = f.WithWebHostBuilder(b => b.UseEnvironment("Production").UseSetting("Workers:AllowLoopbackForTests", "true"));
        var ex = Assert.ThrowsAny<Exception>(() => production.CreateClient());
        Assert.Contains("Workers:AllowLoopbackForTests", ex.ToString());

        using var development = f.WithWebHostBuilder(b => b.UseEnvironment("Development").UseSetting("Workers:AllowLoopbackForTests", "true"));
        Assert.True(development.Services.GetRequiredService<WorkerOptions>().AllowLoopbackForTests);
    }
}
