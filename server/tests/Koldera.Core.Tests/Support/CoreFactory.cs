using System.Collections.Concurrent;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Workers;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Time.Testing;

namespace Koldera.Core.Tests.Support;

public sealed class CoreFactory : WebApplicationFactory<Program>
{
    private static readonly string[] Keys =
    [
        "TOKEN", "AUTH", "DATA_DIR", "PUBLIC_URL", "RETENTION_HOURS", "CONCURRENCY", "QUEUE_CAPACITY", "FEATURES",
        "CORS_ORIGINS", "SIGNING_KEY", "WEB_PROXY", "WEB_ALLOW_HTTP", "PDF_MAX_BYTES", "PDF_MAX_PAGES",
        "TYPST_CMD", "HTML_MAX_BYTES", "JOB_TIMEOUT_SECONDS", "WEB_MAX_RESPONSE_BYTES", "WEB_MAX_REQUESTS", "CHROMIUM_SANDBOX",
    ];

    private readonly Dictionary<string, string?> _env;
    private readonly string? _tempDir;
    private Action<IServiceCollection>? _services;

    public ConcurrentQueue<string> Logs { get; } = new();

    /// <summary>The clock the server runs on; tests may advance it.</summary>
    public FakeTimeProvider Clock { get; } = new(DateTimeOffset.UtcNow);

    public DataStore Store => Services.GetRequiredService<DataStore>();

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

    /// <summary>A server (token "t") whose PDF engine processes are the given stubs; the structure probe is off unless one is given.</summary>
    public static CoreFactory WithStubWorkers(IWorkerProcess pdf, IWorkerProcess? structureProbe = null, Dictionary<string, string?>? env = null,
        IWorkerProcess? htmlRender = null, IWorkerProcess? inspect = null, IWorkerProcess? semantic = null, Action<IServiceCollection>? configure = null, IWorkerProcess? web = null, string? features = null, IWorkerProcess? pdfReorder = null, IWorkerProcess? htmlReorder = null, IWorkerProcess? documentRender = null)
    {
        var values = new Dictionary<string, string?> { ["KOLDERA_TOKEN"] = "t" };
        foreach (var (k, v) in env ?? []) values[k] = v;
        if (features is not null) values["KOLDERA_FEATURES"] = features;
        var factory = Create(values);
        factory._services = s =>
        {
            s.RemoveAll<PdfWorkers>();
            s.AddSingleton(new PdfWorkers(pdf, structureProbe));
            // Without HTML stubs a render would start a real process; fail loudly if a test forgot them.
            s.RemoveAll<HtmlWorkers>();
            s.AddSingleton(new HtmlWorkers(htmlRender ?? new StubWorker(_ => throw new InvalidOperationException("no html render stub")),
                inspect ?? new StubWorker(_ => throw new InvalidOperationException("no inspect stub")), semantic));
            // Likewise for the reorder verbs: a remediation without its stub must not spawn a process.
            s.RemoveAll<OrderWorkers>();
            s.AddSingleton(new OrderWorkers(pdfReorder ?? new StubWorker(_ => throw new InvalidOperationException("no pdf reorder stub")),
                htmlReorder ?? new StubWorker(_ => throw new InvalidOperationException("no html reorder stub"))));
            // Without a web stub a scan would start a real process; fail loudly if a test forgot it.
            s.RemoveAll<DocumentWorkers>();
            s.AddSingleton(new DocumentWorkers(documentRender ?? new StubWorker(_ => throw new InvalidOperationException("no document render stub"))));
            s.RemoveAll<WebWorkers>();
            s.AddSingleton(new WebWorkers(web ?? new StubWorker(_ => throw new InvalidOperationException("no web stub"))));
            configure?.Invoke(s);
        };
        return factory;
    }

    /// <summary>Uploads <paramref name="bytes"/> as a PDF and returns the completed upload's id.</summary>
    public async Task<Guid> CompletedUpload(byte[] bytes) => await CompletedUpload(bytes, "report.pdf", "application/pdf");

    /// <summary>Uploads <paramref name="bytes"/> as an HTML document and returns the completed upload's id.</summary>
    public async Task<Guid> CompletedHtmlUpload(byte[]? bytes = null) => await CompletedUpload(bytes ?? Fixtures.SmallHtml(), "page.html", "text/html");

    public async Task<Guid> CompletedUpload(byte[] bytes, string fileName, string contentType)
    {
        var (id, url) = await Declare(bytes, fileName, contentType);
        var put = await CreateClient().PutAsync(url, new ByteArrayContent(bytes));
        put.EnsureSuccessStatusCode();
        return id;
    }

    /// <summary>Declares an HTML upload without sending its content.</summary>
    public async Task<Guid> HtmlUploadTicket() => (await Declare(Fixtures.SmallHtml(), "page.html", "text/html")).Id;

    /// <summary>Declares an upload without sending its content.</summary>
    public async Task<Guid> UploadTicket(byte[] bytes) => (await Declare(bytes)).Id;

    private async Task<(Guid Id, string Url)> Declare(byte[] bytes, string fileName = "report.pdf", string contentType = "application/pdf")
    {
        var r = await AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName, sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType });
        r.EnsureSuccessStatusCode();
        var ticket = await r.Content.ReadFromJsonAsync<JsonElement>();
        return (ticket.GetProperty("uploadId").GetGuid(), ticket.GetProperty("uploadUrl").GetString()!);
    }

    public async Task<Guid> CreatePdfJob(string mode = "analyze", string? explicitTitle = null, string? explicitLanguage = null)
    {
        var upload = await CompletedUpload(Fixtures.SmallPdf());
        var r = await AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode, profile = "ua1", explicitTitle, explicitLanguage });
        Assert.Equal(System.Net.HttpStatusCode.Accepted, r.StatusCode);
        return (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    public async Task<Guid> CreateHtmlJob(string mode = "generate_analyze", string? contentSelector = null, byte[]? html = null)
    {
        var upload = await CompletedHtmlUpload(html);
        var r = await AuthorizedClient().PostAsJsonAsync("/v1/html-to-pdf/jobs", new { uploadId = upload, mode, profile = "ua1", contentSelector });
        Assert.Equal(System.Net.HttpStatusCode.Accepted, r.StatusCode);
        return (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    public async Task<Guid> CreateWebJob(string url)
    {
        var r = await AuthorizedClient().PostAsJsonAsync("/v1/web/jobs", new { url });
        Assert.Equal(System.Net.HttpStatusCode.Accepted, r.StatusCode);
        return (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    /// <summary>Mints a client session through the real endpoint (as the server token) and returns the session token.</summary>
    public async Task<string> MintSession(string origin, int? maxJobs = null, string[]? scopes = null, int? ttlSeconds = null, int? maxUploads = null)
    {
        var r = await AuthorizedClient().PostAsJsonAsync("/v1/client-sessions", new { allowedOrigin = origin, maxJobs, scopes, ttlSeconds, maxUploads });
        Assert.Equal(System.Net.HttpStatusCode.Created, r.StatusCode);
        return (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("sessionToken").GetString()!;
    }

    /// <summary>A client carrying a session token and the origin a browser would send.</summary>
    public HttpClient SessionClient(string session, string origin)
    {
        var c = CreateClient();
        c.DefaultRequestHeaders.Authorization = new("Bearer", session);
        c.DefaultRequestHeaders.Add("Origin", origin);
        return c;
    }

    /// <summary>Uploads a small PDF and posts an analyze job, both as the session; returns the raw job response.</summary>
    public async Task<HttpResponseMessage> TryCreatePdfJobAs(string session, string origin)
    {
        var c = SessionClient(session, origin);
        var bytes = Fixtures.SmallPdf();
        var d = await c.PostAsJsonAsync("/v1/uploads", new { fileName = "r.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" });
        d.EnsureSuccessStatusCode();
        var ticket = await d.Content.ReadFromJsonAsync<JsonElement>();
        (await CreateClient().PutAsync(ticket.GetProperty("uploadUrl").GetString()!, new ByteArrayContent(bytes))).EnsureSuccessStatusCode();
        return await c.PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = ticket.GetProperty("uploadId").GetGuid(), mode = "analyze", profile = "ua1" });
    }

    public async Task<Guid> CreatePdfJobAs(string session, string origin)
    {
        var r = await TryCreatePdfJobAs(session, origin);
        Assert.Equal(System.Net.HttpStatusCode.Accepted, r.StatusCode);
        return (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    /// <summary>POST with an Idempotency-Key (as the server token unless a client is given); returns the raw response.</summary>
    public async Task<HttpResponseMessage> RawPostWithKey(string path, object body, string key, HttpClient? client = null)
    {
        var c = client ?? AuthorizedClient();
        var req = new HttpRequestMessage(HttpMethod.Post, path) { Content = JsonContent.Create(body) };
        req.Headers.Add("Idempotency-Key", key);
        return await c.SendAsync(req);
    }

    public async Task<JsonElement> PostWithKey(string path, object body, string key)
    {
        var r = await RawPostWithKey(path, body, key);
        Assert.True(r.IsSuccessStatusCode, $"POST {path} answered {(int)r.StatusCode}");
        return await r.Content.ReadFromJsonAsync<JsonElement>();
    }

    /// <summary>Waits for the job to finish (real time limit of 30 seconds) and returns its job resource.</summary>
    public async Task<JsonElement> WaitForJob(Guid id)
    {
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        var finished = false;
        try
        {
            finished = await Services.GetRequiredService<JobRunner>().WaitForCompletionAsync(id, TimeSpan.FromSeconds(30), limit.Token);
        }
        catch (OperationCanceledException)
        {
            // reported below with the job's state instead of a bare cancellation
        }
        var current = Store.GetJob(id);
        Assert.True(finished, $"the job did not finish within 30 s; current state: {current?.State ?? "missing"}, error: {current?.ErrorCode ?? "none"}");
        return await AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}");
    }

    /// <summary>Client carrying the first configured KOLDERA_TOKEN as bearer (no header when none is set).</summary>
    public HttpClient AuthorizedClient()
    {
        var client = CreateClient();
        var first = (_env.GetValueOrDefault("KOLDERA_TOKEN") ?? "")
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .FirstOrDefault();
        if (first is not null) client.DefaultRequestHeaders.Authorization = new("Bearer", first);
        return client;
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(_env));
        builder.ConfigureServices(s =>
        {
            s.AddSingleton<TimeProvider>(Clock);
            _services?.Invoke(s);
        });
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
