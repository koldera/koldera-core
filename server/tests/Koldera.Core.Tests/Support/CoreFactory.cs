using System.Collections.Concurrent;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace Koldera.Core.Tests.Support;

public sealed class CoreFactory : WebApplicationFactory<Program>
{
    private static readonly string[] Keys =
    [
        "TOKEN", "AUTH", "DATA_DIR", "PUBLIC_URL", "RETENTION_HOURS", "CONCURRENCY", "QUEUE_CAPACITY", "FEATURES",
        "CORS_ORIGINS", "SIGNING_KEY", "WEB_PROXY", "WEB_ALLOW_HTTP", "PDF_MAX_BYTES", "PDF_MAX_PAGES",
        "HTML_MAX_BYTES", "JOB_TIMEOUT_SECONDS", "WEB_MAX_RESPONSE_BYTES", "WEB_MAX_REQUESTS",
    ];

    private readonly Dictionary<string, string?> _env;
    private readonly string? _tempDir;

    public ConcurrentQueue<string> Logs { get; } = new();

    private CoreFactory(Dictionary<string, string?> env, string? tempDir)
    {
        _env = env;
        _tempDir = tempDir;
    }

    public static CoreFactory Create(Dictionary<string, string?> env)
    {
        // Empty values override any real KOLDERA_* variable of the machine running the tests.
        var values = Keys.ToDictionary(k => "KOLDERA_" + k, _ => (string?)"");
        foreach (var (k, v) in env) values[k] = v;
        string? temp = null;
        if (!env.ContainsKey("KOLDERA_DATA_DIR"))
        {
            temp = Path.Combine(Path.GetTempPath(), "koldera-test-" + Guid.NewGuid().ToString("N"));
            values["KOLDERA_DATA_DIR"] = temp;
        }
        return new CoreFactory(values, temp);
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(_env));
        builder.ConfigureLogging(l => l.AddProvider(new CapturingProvider(Logs)));
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing && _tempDir is not null && Directory.Exists(_tempDir))
            try { Directory.Delete(_tempDir, true); } catch (IOException) { }
    }

    private sealed class CapturingProvider(ConcurrentQueue<string> sink) : ILoggerProvider
    {
        public ILogger CreateLogger(string categoryName) => new Capture(sink, categoryName);
        public void Dispose() { }

        private sealed class Capture(ConcurrentQueue<string> sink, string category) : ILogger
        {
            public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
            public bool IsEnabled(LogLevel logLevel) => true;
            public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) =>
                sink.Enqueue($"{category} {logLevel} {formatter(state, exception)}");
        }
    }
}
