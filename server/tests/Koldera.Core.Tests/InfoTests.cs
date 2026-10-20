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
    public async Task Info_reports_the_version_set_in_Directory_Build_props_and_the_engine_jars_own_version()
    {
        var jar = Path.Combine(Path.GetTempPath(), "koldera-engine-" + Guid.NewGuid().ToString("N") + ".jar");
        using (var zip = System.IO.Compression.ZipFile.Open(jar, System.IO.Compression.ZipArchiveMode.Create))
        using (var w = new StreamWriter(zip.CreateEntry("engine-version.properties").Open()))
            w.Write("engine.version=0.2.3\npdfbox.version=3.0.8\n");
        try
        {
            using var factory = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t1" });
            using var f = factory.WithWebHostBuilder(b => b.UseSetting("Workers:PdfCommand:0", "java").UseSetting("Workers:PdfCommand:1", "-jar")
                .UseSetting("Workers:PdfCommand:2", jar).UseSetting("Workers:PdfCommand:3", "process"));
            var c = f.CreateClient();
            c.DefaultRequestHeaders.Authorization = new("Bearer", "t1");
            var json = await c.GetFromJsonAsync<JsonElement>("/v1/info");
            Assert.Equal(DirectoryBuildPropsVersion(), json.GetProperty("version").GetString());
            Assert.NotEqual("1.0.0", json.GetProperty("version").GetString());
            Assert.Equal("koldera-pdf-engine 0.2.3; pdfbox 3.0.8", json.GetProperty("engineVersion").GetString());
        }
        finally { File.Delete(jar); }
    }

    [Fact]
    public void A_missing_or_unreadable_engine_jar_gives_no_engine_version()
    {
        Assert.Null(InfoEndpoints.EngineVersion(Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N") + ".jar")));
        var notAZip = Path.GetTempFileName();
        try
        {
            File.WriteAllText(notAZip, "not a jar");
            Assert.Null(InfoEndpoints.EngineVersion(notAZip));
        }
        finally { File.Delete(notAZip); }
    }

    private static string DirectoryBuildPropsVersion()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var props = Path.Combine(dir.FullName, "Directory.Build.props");
            if (File.Exists(props) && System.Xml.Linq.XDocument.Load(props).Descendants("Version").FirstOrDefault() is { } v) return v.Value;
        }
        throw new FileNotFoundException("Directory.Build.props with a <Version> not found");
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
