using System.Diagnostics;
using System.Text.Json;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>Uses node as the child process: it is present wherever the repository's other tests run.</summary>
public sealed class SubprocessWorkerTests : IDisposable
{
    private readonly string _scratch = Path.Combine(Path.GetTempPath(), "koldera-sub-" + Guid.NewGuid().ToString("N"));

    public SubprocessWorkerTests() => Directory.CreateDirectory(_scratch);

    public void Dispose() => Directory.Delete(_scratch, true);

    private static string[] Node(string script) => ["node", "-e", script, "--"];

    private const string DumpEnvironment =
        "const a=process.argv; require('fs').writeFileSync(a[a.indexOf('--result')+1], JSON.stringify({env:process.env, args:a.slice(1)}));";

    [Fact]
    public async Task The_child_sees_only_os_plumbing_and_the_explicit_variables()
    {
        Environment.SetEnvironmentVariable("KOLDERA_TOKEN", "server-secret");
        Environment.SetEnvironmentVariable("AWS_SECRET_ACCESS_KEY", "aws-canary");
        Environment.SetEnvironmentVariable("DOTNET_ENVIRONMENT", "dotnet-canary");
        try
        {
            var env = new Dictionary<string, string> { ["VERAPDF_CMD"] = "verapdf" };
            var worker = new SubprocessWorker(Node(DumpEnvironment), env);
            var result = Path.Combine(_scratch, "result.json");

            var run = await worker.RunAsync(_scratch, Path.Combine(_scratch, "request.json"), result, TimeSpan.FromSeconds(30), CancellationToken.None);

            Assert.Equal(0, run.ExitCode);
            using var doc = JsonDocument.Parse(await File.ReadAllTextAsync(result));
            var names = doc.RootElement.GetProperty("env").EnumerateObject().Select(p => p.Name).ToList();
            Assert.DoesNotContain(names, n => n.StartsWith("KOLDERA_", StringComparison.OrdinalIgnoreCase));
            Assert.Equal("verapdf", doc.RootElement.GetProperty("env").GetProperty("VERAPDF_CMD").GetString());
            var raw = doc.RootElement.GetRawText();
            Assert.DoesNotContain("server-secret", raw);
            Assert.DoesNotContain("aws-canary", raw);
            Assert.DoesNotContain("dotnet-canary", raw);
            // Nothing beyond the pass-through allow-list and the explicitly passed variables reaches the child.
            var allowed = SubprocessWorker.PassThroughNames.Concat(env.Keys).ToHashSet(StringComparer.OrdinalIgnoreCase);
            Assert.All(names, n => Assert.Contains(n, allowed));
        }
        finally
        {
            Environment.SetEnvironmentVariable("KOLDERA_TOKEN", null);
            Environment.SetEnvironmentVariable("AWS_SECRET_ACCESS_KEY", null);
            Environment.SetEnvironmentVariable("DOTNET_ENVIRONMENT", null);
        }
    }

    [Fact]
    public async Task The_request_result_style_appends_the_envelope_arguments_and_the_probe_style_the_input_arguments()
    {
        var req = Path.Combine(_scratch, "request.json");
        var res = Path.Combine(_scratch, "result.json");

        await SubprocessWorker.ForRequestResult(Node(DumpEnvironment)).RunAsync(_scratch, req, res, TimeSpan.FromSeconds(30), CancellationToken.None);
        var envelope = JsonDocument.Parse(await File.ReadAllTextAsync(res)).RootElement.GetProperty("args").EnumerateArray().Select(a => a.GetString()).ToList();
        Assert.Equal(["--request", req, "--result", res, "--work-root", _scratch], envelope.Skip(envelope.IndexOf("--request")).ToList());

        File.Delete(res);
        await SubprocessWorker.ForProbe(Node(DumpEnvironment)).RunAsync(_scratch, req, res, TimeSpan.FromSeconds(30), CancellationToken.None);
        var probe = JsonDocument.Parse(await File.ReadAllTextAsync(res)).RootElement.GetProperty("args").EnumerateArray().Select(a => a.GetString()).ToList();
        Assert.Equal(["--input", req, "--result", res], probe.Skip(probe.IndexOf("--input")).ToList());
    }

    [Fact]
    public async Task A_timeout_kills_the_child_and_its_descendants()
    {
        var marker = Path.Combine(_scratch, "grandchild-ran.txt").Replace("\\", "/");
        var script =
            "const {spawn}=require('child_process');" +
            $"spawn(process.execPath,['-e',\"setTimeout(()=>require('fs').writeFileSync('{marker}','x'),2500)\"],{{stdio:'ignore'}});" +
            "setTimeout(()=>{},60000);";
        var worker = SubprocessWorker.ForRequestResult(Node(script));

        var watch = Stopwatch.StartNew();
        var run = await worker.RunAsync(_scratch, "r.json", "o.json", TimeSpan.FromSeconds(1), CancellationToken.None);
        watch.Stop();

        Assert.True(run.TimedOut);
        Assert.Equal(-1, run.ExitCode);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(30));
        await Task.Delay(TimeSpan.FromSeconds(3.5));
        Assert.False(File.Exists(marker));
    }

    [Fact]
    public async Task Cancellation_kills_the_child_and_surfaces_as_cancellation()
    {
        var worker = SubprocessWorker.ForRequestResult(Node("setTimeout(()=>{},60000)"));
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(1));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            worker.RunAsync(_scratch, "r.json", "o.json", Timeout.InfiniteTimeSpan, cts.Token));
    }

    [Fact]
    public async Task An_infinite_timeout_lets_the_child_finish_on_its_own()
    {
        var worker = SubprocessWorker.ForRequestResult(Node("setTimeout(()=>process.exit(3),1200)"));

        var run = await worker.RunAsync(_scratch, "r.json", "o.json", Timeout.InfiniteTimeSpan, CancellationToken.None);

        Assert.False(run.TimedOut);
        Assert.Equal(3, run.ExitCode);
    }

    [Fact]
    public async Task The_stderr_tail_is_bounded()
    {
        var worker = SubprocessWorker.ForRequestResult(Node("process.stderr.write('e'.repeat(10000))"));

        var run = await worker.RunAsync(_scratch, "r.json", "o.json", TimeSpan.FromSeconds(30), CancellationToken.None);

        Assert.Equal(4096, run.StderrTail.Length);
    }

    [Fact]
    public async Task Large_stdout_and_stderr_are_drained_and_only_the_stderr_tail_is_kept()
    {
        // 48 MiB on each stream: the drain must keep reading (or the child blocks) but hold only the last 4 KiB.
        var script = "const c='x'.repeat(1<<20);for(let i=0;i<48;i++){process.stdout.write(c);process.stderr.write(c)}process.stderr.write('END')";
        var run = await SubprocessWorker.ForRequestResult(Node(script)).RunAsync(_scratch, "r.json", "o.json", TimeSpan.FromSeconds(120), CancellationToken.None);

        Assert.Equal(0, run.ExitCode);
        Assert.Equal(4096, run.StderrTail.Length);
        Assert.EndsWith("xxxEND", run.StderrTail);
    }

    [Fact]
    public async Task The_tail_keeper_returns_the_last_characters_across_buffer_boundaries()
    {
        var text = string.Concat(Enumerable.Range(0, 5000).Select(i => (char)('a' + i % 26)));
        using var reader = new StringReader(text);

        Assert.Equal(text[^4096..], await SubprocessWorker.DrainTailAsync(reader, 4096));
        using var again = new StringReader(text);
        Assert.Equal("", await SubprocessWorker.DrainTailAsync(again, 0));
    }

    [Fact]
    public async Task An_unconfigured_command_is_a_configuration_error()
    {
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            SubprocessWorker.ForRequestResult([]).RunAsync(_scratch, "r", "o", TimeSpan.FromSeconds(1), CancellationToken.None));
    }

    [Fact]
    public async Task Node_verbs_get_the_contracts_directory_only_when_one_is_given()
    {
        async Task<JsonElement> EnvOf(SubprocessWorker worker)
        {
            var res = Path.Combine(_scratch, Guid.NewGuid().ToString("N") + ".json");
            await worker.RunAsync(_scratch, "r.json", res, TimeSpan.FromSeconds(30), CancellationToken.None);
            return JsonDocument.Parse(await File.ReadAllTextAsync(res)).RootElement.GetProperty("env").Clone();
        }

        var with = await EnvOf(SubprocessWorker.ForNodeVerb(Node(DumpEnvironment), "/contracts"));
        Assert.Equal("/contracts", with.GetProperty("KOLDERA_CONTRACTS_DIR").GetString());
        var without = await EnvOf(SubprocessWorker.ForNodeVerb(Node(DumpEnvironment), null));
        Assert.False(without.TryGetProperty("KOLDERA_CONTRACTS_DIR", out _));
        var web = await EnvOf(SubprocessWorker.ForWeb(Node(DumpEnvironment), null, allowLoopbackForTests: true));
        Assert.Equal("1", web.GetProperty("KOLDERA_SCANNER_ALLOW_LOOPBACK_FOR_TESTS").GetString());
    }
}
