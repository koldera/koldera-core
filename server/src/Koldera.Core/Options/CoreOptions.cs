using System.Globalization;
using System.Security;

namespace Koldera.Core;

public sealed record Limits(
    long? PdfMaxBytes,
    int? PdfMaxPages,
    long? HtmlMaxBytes,
    int? JobTimeoutSeconds,
    long? WebMaxResponseBytes,
    int? WebMaxRequests);

/// <param name="PublicUrl">
/// KOLDERA_PUBLIC_URL: the externally visible base URL used in signed upload/download URLs. Behind a TLS-terminating proxy
/// it must be set, otherwise the URLs are built from the request scheme the server sees (http).
/// </param>
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
    Limits Limits,
    string TypstCommand = CoreOptions.DefaultTypstCommand)
{
    public const string DefaultTypstCommand = "/opt/typst/typst";

    public static readonly IReadOnlyList<string> KnownFeatures = ["pdf", "html", "web", "document"];

    public static CoreOptions FromEnvironment(IDictionary<string, string?> env) =>
        From(name => env.TryGetValue(name, out var v) ? v : null);

    public static CoreOptions FromProcessEnvironment() =>
        From(Environment.GetEnvironmentVariable);

    public static CoreOptions From(Func<string, string?> get)
    {
        string? Text(string key) => string.IsNullOrWhiteSpace(get("KOLDERA_" + key)) ? null : get("KOLDERA_" + key)!.Trim();

        InvalidOperationException BadNumber(string key) => new($"KOLDERA_{key}: expected a positive integer");

        long? Long(string key)
        {
            var raw = Text(key);
            if (raw is null) return null;
            if (!long.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out var n) || n <= 0) throw BadNumber(key);
            return n;
        }

        int? OptionalInt(string key)
        {
            var raw = Text(key);
            if (raw is null) return null;
            if (!int.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out var n) || n <= 0) throw BadNumber(key);
            return n;
        }

        int Int(string key, int fallback) => OptionalInt(key) ?? fallback;

        var features = (Text("FEATURES") ?? string.Join(',', KnownFeatures))
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(f => f.ToLowerInvariant())
            .ToList();
        if (features.Count == 0) throw new InvalidOperationException("KOLDERA_FEATURES: at least one feature is required");
        foreach (var f in features)
            if (!KnownFeatures.Contains(f))
                throw new InvalidOperationException($"KOLDERA_FEATURES: unknown feature {f}");

        var allowHttp = Text("WEB_ALLOW_HTTP");
        if (allowHttp is not null && !bool.TryParse(allowHttp, out _))
            throw new InvalidOperationException("KOLDERA_WEB_ALLOW_HTTP: expected true or false");

        var auth = Text("AUTH")?.ToLowerInvariant();
        if (auth is not (null or "none"))
            throw new InvalidOperationException("KOLDERA_AUTH: only \"none\" is accepted (leave it unset for token authentication)");

        var token = Text("TOKEN");
        if (token is not null && token.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Length == 0)
            throw new InvalidOperationException("KOLDERA_TOKEN: no token left after splitting on commas (unset it to generate one)");
        var signingKey = Text("SIGNING_KEY");
        if (signingKey is not null) SigningKeys.Decode(signingKey);

        return new CoreOptions(
            Token: token,
            Auth: auth ?? "token",
            DataDir: Text("DATA_DIR") ?? "/data",
            PublicUrl: Text("PUBLIC_URL"),
            RetentionHours: Int("RETENTION_HOURS", 24),
            Concurrency: Int("CONCURRENCY", Math.Max(1, Environment.ProcessorCount / 2)),
            QueueCapacity: Int("QUEUE_CAPACITY", 100),
            Features: new HashSet<string>(features),
            CorsOrigins: (Text("CORS_ORIGINS") ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
            SigningKey: signingKey,
            WebProxy: Text("WEB_PROXY"),
            WebAllowHttp: allowHttp is not null && bool.Parse(allowHttp),
            Limits: new Limits(
                Long("PDF_MAX_BYTES"),
                OptionalInt("PDF_MAX_PAGES"),
                Long("HTML_MAX_BYTES"),
                OptionalInt("JOB_TIMEOUT_SECONDS"),
                Long("WEB_MAX_RESPONSE_BYTES"),
                OptionalInt("WEB_MAX_REQUESTS")),
            TypstCommand: Text("TYPST_CMD") ?? DefaultTypstCommand);
    }

    // Secrets never appear in the printed form (logs, exception messages, test output).
    public override string ToString() =>
        $"CoreOptions {{ Token = {(Token is null ? "null" : "***")}, Auth = {Auth}, DataDir = {DataDir}, PublicUrl = {PublicUrl}, " +
        $"RetentionHours = {RetentionHours}, Concurrency = {Concurrency}, QueueCapacity = {QueueCapacity}, " +
        $"Features = [{string.Join(", ", Features.Order())}], CorsOrigins = [{string.Join(", ", CorsOrigins)}], " +
        $"SigningKey = {(SigningKey is null ? "null" : "***")}, WebProxy = {(WebProxy is null ? "null" : "***")}, " +
        $"WebAllowHttp = {WebAllowHttp}, Limits = {Limits} }}";

    public void EnsureDataDirWritable()
    {
        try
        {
            Directory.CreateDirectory(DataDir);
            var probe = Path.Combine(DataDir, ".probe-" + Guid.NewGuid().ToString("N"));
            File.WriteAllText(probe, "");
            File.Delete(probe);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException or ArgumentException or SecurityException)
        {
            throw new InvalidOperationException($"KOLDERA_DATA_DIR {DataDir} is not writable: {e.Message}");
        }
    }
}
