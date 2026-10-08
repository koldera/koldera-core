using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class InfoTests
{
    [Fact]
    public async Task Healthz_is_public_and_ok()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t1" });
        var r = await f.CreateClient().GetAsync("/healthz");
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("{\"status\":\"ok\"}", await r.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Info_lists_enabled_features()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t1", ["KOLDERA_FEATURES"] = "pdf,html" });
        var c = f.CreateClient();
        c.DefaultRequestHeaders.Authorization = new("Bearer", "t1");
        var json = await c.GetFromJsonAsync<JsonElement>("/v1/info");
        Assert.Equal(["html", "pdf"], json.GetProperty("features").EnumerateArray().Select(e => e.GetString()).Order());
    }

    [Fact]
    public void Unwritable_data_dir_fails_start_with_a_clear_message()
    {
        var blocker = Path.GetTempFileName();
        try
        {
            var ex = Assert.ThrowsAny<Exception>(() =>
            {
                using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t1", ["KOLDERA_DATA_DIR"] = blocker });
                f.CreateClient();
            });
            Assert.Contains("KOLDERA_DATA_DIR", ex.ToString());
        }
        finally { File.Delete(blocker); }
    }

    [Fact]
    public void Unknown_feature_is_rejected()
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_FEATURES"] = "pdf,x" }));
        Assert.Equal("KOLDERA_FEATURES: unknown feature x", ex.Message);
    }

    [Fact]
    public void Caps_have_their_defaults_and_are_parsed_when_set()
    {
        var d = CoreOptions.FromEnvironment(new Dictionary<string, string?>());
        Assert.Null(d.Limits.PdfMaxBytes);
        Assert.Equal(Limits.DefaultJobTimeoutSeconds, d.Limits.JobTimeoutSeconds);
        Assert.Equal(24, d.RetentionHours);
        Assert.Equal(100, d.QueueCapacity);
        Assert.Equal("/data", d.DataDir);
        Assert.False(d.WebAllowHttp);
        Assert.Equal(4, d.Features.Count);
        var s = CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_PDF_MAX_BYTES"] = "10", ["KOLDERA_WEB_ALLOW_HTTP"] = "true" });
        Assert.Equal(10, s.Limits.PdfMaxBytes);
        Assert.True(s.WebAllowHttp);
    }
}
