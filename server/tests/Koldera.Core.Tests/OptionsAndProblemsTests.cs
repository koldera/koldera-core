using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;

namespace Koldera.Core.Tests;

public class OptionsAndProblemsTests
{
    private static Dictionary<string, string?> Env(string k, string? v) => new() { [k] = v };

    [Theory]
    [InlineData("KOLDERA_RETENTION_HOURS", "abc")]
    [InlineData("KOLDERA_RETENTION_HOURS", "0")]
    [InlineData("KOLDERA_QUEUE_CAPACITY", "-1")]
    [InlineData("KOLDERA_CONCURRENCY", "99999999999")]
    [InlineData("KOLDERA_PDF_MAX_PAGES", "99999999999")]
    [InlineData("KOLDERA_PDF_MAX_BYTES", "x1")]
    public void Invalid_number_names_its_variable(string key, string value)
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(Env(key, value)));
        Assert.Equal($"{key}: expected a positive integer", ex.Message);
    }

    [Fact]
    public void Empty_or_whitespace_is_unset()
    {
        var o = CoreOptions.FromEnvironment(new Dictionary<string, string?>
        {
            ["KOLDERA_RETENTION_HOURS"] = "", ["KOLDERA_PDF_MAX_BYTES"] = "  ", ["KOLDERA_FEATURES"] = " ", ["KOLDERA_DATA_DIR"] = "",
        });
        Assert.Equal(24, o.RetentionHours);
        Assert.Null(o.Limits.PdfMaxBytes);
        Assert.Equal(4, o.Features.Count);
        Assert.Equal("/data", o.DataDir);
    }

    [Fact]
    public void Features_that_parse_to_nothing_are_rejected()
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(Env("KOLDERA_FEATURES", ",")));
        Assert.Contains("KOLDERA_FEATURES", ex.Message);
    }

    [Theory]
    [InlineData("", "token")]
    [InlineData("none", "none")]
    [InlineData("NONE", "none")]
    public void Auth_accepts_only_unset_or_none(string value, string expected) =>
        Assert.Equal(expected, CoreOptions.FromEnvironment(Env("KOLDERA_AUTH", value)).Auth);

    [Fact]
    public void Auth_rejects_other_values()
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(Env("KOLDERA_AUTH", "basic")));
        Assert.Contains("KOLDERA_AUTH", ex.Message);
    }

    [Fact]
    public void Bad_data_dir_path_names_the_variable()
    {
        var o = CoreOptions.FromEnvironment(Env("KOLDERA_DATA_DIR", "bad\0path"));
        var ex = Assert.Throws<InvalidOperationException>(o.EnsureDataDirWritable);
        Assert.Contains("KOLDERA_DATA_DIR", ex.Message);
    }

    [Fact]
    public void Process_environment_is_read_without_a_host()
    {
        Environment.SetEnvironmentVariable("KOLDERA_QUEUE_CAPACITY", "7");
        try { Assert.Equal(7, CoreOptions.FromProcessEnvironment().QueueCapacity); }
        finally { Environment.SetEnvironmentVariable("KOLDERA_QUEUE_CAPACITY", null); }
    }

    [Fact]
    public async Task Logs_do_not_contain_query_strings()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t1" });
        await f.CreateClient().GetAsync("/healthz?secret=abc123");
        Assert.DoesNotContain(f.Logs, l => l.Contains("abc123"));
    }

    [Fact]
    public async Task Factory_ignores_machine_environment()
    {
        Environment.SetEnvironmentVariable("KOLDERA_FEATURES", "pdf");
        try
        {
            using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t1" });
            var json = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>("/v1/info");
            Assert.Equal(4, json.GetProperty("features").GetArrayLength());
        }
        finally { Environment.SetEnvironmentVariable("KOLDERA_FEATURES", null); }
    }

    [Fact]
    public async Task Problem_has_standard_shape_and_omits_null_detail()
    {
        var ctx = new DefaultHttpContext { RequestServices = new ServiceCollection().AddLogging().BuildServiceProvider() };
        ctx.Response.Body = new MemoryStream();
        await Problems.Result(404, "NOT_FOUND", "Not found").ExecuteAsync(ctx);
        Assert.StartsWith("application/problem+json", ctx.Response.ContentType);
        Assert.Equal(404, ctx.Response.StatusCode);
        ctx.Response.Body.Position = 0;
        var json = await JsonDocument.ParseAsync(ctx.Response.Body);
        Assert.Equal("NOT_FOUND", json.RootElement.GetProperty("code").GetString());
        Assert.Equal("Not found", json.RootElement.GetProperty("title").GetString());
        Assert.Equal(404, json.RootElement.GetProperty("status").GetInt32());
        Assert.False(json.RootElement.TryGetProperty("detail", out _));
    }
}

public class OptionsSecretsTests
{
    [Fact]
    public void ToString_never_prints_the_token_or_the_signing_key()
    {
        var key = Convert.ToBase64String(Enumerable.Range(7, 32).Select(i => (byte)i).ToArray());
        var o = CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_TOKEN"] = "super-secret-token", ["KOLDERA_SIGNING_KEY"] = key });
        var text = o.ToString();
        Assert.DoesNotContain("super-secret-token", text);
        Assert.DoesNotContain(key, text);
        Assert.Contains("Token = ***", text);
    }

    [Theory]
    [InlineData(",")]
    [InlineData(" , ")]
    public void A_token_that_is_blank_after_splitting_is_a_startup_error(string value)
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_TOKEN"] = value }));
        Assert.Contains("KOLDERA_TOKEN", ex.Message);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void An_unset_or_empty_token_still_means_generate(string value)
    {
        Assert.Null(CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_TOKEN"] = value }).Token);
    }

    [Theory]
    [InlineData("not base64!!")]
    [InlineData("AAAA")]
    public void A_bad_or_short_signing_key_names_its_variable(string value)
    {
        var ex = Assert.Throws<InvalidOperationException>(() => CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_SIGNING_KEY"] = value }));
        Assert.Contains("KOLDERA_SIGNING_KEY", ex.Message);
    }

    [Fact]
    public void Generated_signing_key_is_persisted_and_reused()
    {
        var dir = Path.Combine(Path.GetTempPath(), "koldera-key-" + Guid.NewGuid().ToString("N"));
        try
        {
            var o = CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_DATA_DIR"] = dir });
            var first = SigningKeys.Resolve(o);
            Assert.Equal(32, first.Length);
            Assert.Equal(first, SigningKeys.Resolve(o));
            var path = Path.Combine(dir, ".signing-key");
            Assert.True(File.Exists(path));
            if (!OperatingSystem.IsWindows())
                Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(path));
        }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }

    [Fact]
    public void Configured_signing_key_is_used_as_is()
    {
        var raw = Enumerable.Range(0, 40).Select(i => (byte)i).ToArray();
        var o = CoreOptions.FromEnvironment(new Dictionary<string, string?> { ["KOLDERA_SIGNING_KEY"] = Convert.ToBase64String(raw) });
        Assert.Equal(raw, SigningKeys.Resolve(o));
    }
}
