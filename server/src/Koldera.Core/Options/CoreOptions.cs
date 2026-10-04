using System.Globalization;

namespace Koldera.Core;

public sealed record Limits(
    long? PdfMaxBytes,
    int? PdfMaxPages,
    long? HtmlMaxBytes,
    int? JobTimeoutSeconds,
    long? WebMaxResponseBytes,
    int? WebMaxRequests);

public sealed record CoreOptions(
    string? Token,
    string Auth,
    string DataDir,
    string? PublicUrl,
    int RetentionHours,
    int Concurrency,
    int QueueCapacity,
    IReadOnlySet<string> Features,
    IReadOnlyList<string> CorsOrigins,
    string? SigningKey,
    string? WebProxy,
    bool WebAllowHttp,
    Limits Limits)
{
    public static readonly IReadOnlyList<string> KnownFeatures = ["pdf", "html", "web", "document"];

    public static CoreOptions FromEnvironment(IDictionary<string, string?> env) =>
        From(name => env.TryGetValue(name, out var v) ? v : null);

    public static CoreOptions From(Func<string, string?> get)
    {
        string? Text(string key) => string.IsNullOrWhiteSpace(get("KOLDERA_" + key)) ? null : get("KOLDERA_" + key)!.Trim();

        long? Number(string key)
        {
            var raw = Text(key);
            if (raw is null) return null;
            if (!long.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out var n) || n <= 0)
                throw new InvalidOperationException($"KOLDERA_{key}: expected a positive integer");
            return n;
        }

        int Whole(string key, int fallback) => checked((int)(Number(key) ?? fallback));

        int? OptionalWhole(string key) => Number(key) is { } n ? checked((int)n) : null;

        var features = (Text("FEATURES") ?? string.Join(',', KnownFeatures))
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(f => f.ToLowerInvariant())
            .ToList();
        foreach (var f in features)
            if (!KnownFeatures.Contains(f))
                throw new InvalidOperationException($"KOLDERA_FEATURES: unknown feature {f}");

        var allowHttp = Text("WEB_ALLOW_HTTP");
        if (allowHttp is not null && !bool.TryParse(allowHttp, out _))
            throw new InvalidOperationException("KOLDERA_WEB_ALLOW_HTTP: expected true or false");

        return new CoreOptions(
            Token: Text("TOKEN"),
            Auth: (Text("AUTH") ?? "token").ToLowerInvariant(),
            DataDir: Text("DATA_DIR") ?? "/data",
            PublicUrl: Text("PUBLIC_URL"),
            RetentionHours: Whole("RETENTION_HOURS", 24),
            Concurrency: Whole("CONCURRENCY", Math.Max(1, Environment.ProcessorCount / 2)),
            QueueCapacity: Whole("QUEUE_CAPACITY", 100),
            Features: new HashSet<string>(features),
            CorsOrigins: (Text("CORS_ORIGINS") ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
            SigningKey: Text("SIGNING_KEY"),
            WebProxy: Text("WEB_PROXY"),
            WebAllowHttp: allowHttp is not null && bool.Parse(allowHttp),
            Limits: new Limits(
                Number("PDF_MAX_BYTES"),
                OptionalWhole("PDF_MAX_PAGES"),
                Number("HTML_MAX_BYTES"),
                OptionalWhole("JOB_TIMEOUT_SECONDS"),
                Number("WEB_MAX_RESPONSE_BYTES"),
                OptionalWhole("WEB_MAX_REQUESTS")));
    }

    public void EnsureDataDirWritable()
    {
        try
        {
            Directory.CreateDirectory(DataDir);
            var probe = Path.Combine(DataDir, ".probe-" + Guid.NewGuid().ToString("N"));
            File.WriteAllText(probe, "");
            File.Delete(probe);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException)
        {
            throw new InvalidOperationException($"KOLDERA_DATA_DIR {DataDir} is not writable: {e.Message}");
        }
    }
}
