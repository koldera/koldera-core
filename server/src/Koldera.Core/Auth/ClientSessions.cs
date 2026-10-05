using System.Collections.Concurrent;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Koldera.Core;

public static class SessionScopes
{
    public const string Create = "jobs:create";
    public const string Read = "jobs:read";
    public static readonly string[] All = [Create, Read];
}

/// <summary>
/// Short-lived browser tokens: <c>v1.&lt;base64url(json)&gt;.&lt;base64url(hmac)&gt;</c> with payload
/// <c>{sid, origin, scopes, maxJobs, exp}</c>. Stateless: the signature is the whole credential, so nothing is stored
/// and a restart does not invalidate live tokens. The MAC uses the same key as <see cref="UrlSigner"/> under its own
/// context string, so a signed URL can never be replayed as a session token or the reverse.
/// </summary>
public sealed class ClientSessionTokens(byte[] key, TimeProvider clock) : ITokenResolver
{
    public const string Version = "v1";
    public static readonly TimeSpan DefaultTtl = TimeSpan.FromMinutes(15);
    public static readonly TimeSpan MaxTtl = TimeSpan.FromMinutes(60);
    private const int MaxTokenLength = 4096;
    private const string MacContext = "session";

    public (string Token, string SessionId, DateTimeOffset ExpiresAt) Issue(string origin, IReadOnlyList<string> scopes, int maxJobs, TimeSpan ttl)
    {
        var sid = Guid.NewGuid().ToString("N");
        var expires = clock.GetUtcNow().Add(ttl);
        var payload = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
        {
            ["sid"] = sid,
            ["origin"] = origin,
            ["scopes"] = scopes,
            ["maxJobs"] = maxJobs,
            ["exp"] = expires.ToUnixTimeSeconds(),
        });
        var body = Version + "." + Base64Url(payload);
        return (body + "." + Base64Url(Mac(body)), sid, expires);
    }

    /// <summary>The principal for a valid, unexpired token; null for anything else (wrong version, shape, MAC or time).</summary>
    public CorePrincipal? Resolve(string token)
    {
        if (token.Length > MaxTokenLength || !token.StartsWith(Version + ".", StringComparison.Ordinal)) return null;
        var parts = token.Split('.');
        if (parts.Length != 3) return null;
        byte[] given, payload;
        try
        {
            given = FromBase64Url(parts[2]);
            payload = FromBase64Url(parts[1]);
        }
        catch (FormatException) { return null; }
        // The MAC is checked before the payload is parsed at all.
        if (!CryptographicOperations.FixedTimeEquals(given, Mac(parts[0] + "." + parts[1]))) return null;

        try
        {
            using var doc = JsonDocument.Parse(payload);
            var root = doc.RootElement;
            var sid = root.GetProperty("sid").GetString();
            var origin = root.GetProperty("origin").GetString();
            var maxJobs = root.GetProperty("maxJobs").GetInt32();
            var exp = root.GetProperty("exp").GetInt64();
            var scopes = root.GetProperty("scopes").EnumerateArray().Select(s => s.GetString()).ToList();
            if (string.IsNullOrEmpty(sid) || string.IsNullOrEmpty(origin) || scopes.Any(s => s is null)) return null;
            if (clock.GetUtcNow().ToUnixTimeSeconds() >= exp) return null;
            return new CorePrincipal("session", sid, origin, maxJobs, scopes!, DateTimeOffset.FromUnixTimeSeconds(exp));
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException or KeyNotFoundException or FormatException)
        {
            return null;
        }
    }

    /// <summary>Canonical lower-case scheme://host[:port] for an http(s) origin without path, query or credentials; otherwise null.</summary>
    public static string? NormalizeOrigin(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri)) return null;
        if (uri.Scheme is not ("http" or "https") || uri.UserInfo.Length > 0 || uri.Query.Length > 0 || uri.Fragment.Length > 0) return null;
        if (uri.AbsolutePath != "/") return null;
        return uri.GetLeftPart(UriPartial.Authority).ToLowerInvariant();
    }

    private byte[] Mac(string signed) => HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(MacContext + "\n" + signed));

    internal static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromBase64Url(string s)
    {
        var t = s.Replace('-', '+').Replace('_', '/');
        t = t.PadRight(t.Length + (4 - t.Length % 4) % 4, '=');
        return Convert.FromBase64String(t);
    }
}

/// <summary>
/// How many jobs each session has started. In memory: a restart resets the counts, so a session's budget is only
/// guaranteed within one process lifetime (its token expires within the hour anyway).
/// </summary>
public sealed class SessionBudgets(TimeProvider clock)
{
    private readonly ConcurrentDictionary<string, (int Count, DateTimeOffset Expires)> _used = new();
    private DateTimeOffset _lastSweep = DateTimeOffset.MinValue;

    public bool TryReserve(CorePrincipal session)
    {
        Sweep();
        var sid = session.SessionId!;
        var limit = session.MaxJobs ?? int.MaxValue;
        var expires = session.ExpiresAt ?? clock.GetUtcNow().AddHours(1);
        var granted = false;
        _used.AddOrUpdate(sid,
            _ => { granted = limit >= 1; return (granted ? 1 : 0, expires); },
            (_, cur) =>
            {
                granted = cur.Count < limit;
                return granted ? (cur.Count + 1, cur.Expires) : cur;
            });
        return granted;
    }

    /// <summary>Gives a reservation back when the job was not accepted after all.</summary>
    public void Release(CorePrincipal session) =>
        _used.AddOrUpdate(session.SessionId!, _ => (0, clock.GetUtcNow()), (_, cur) => (Math.Max(0, cur.Count - 1), cur.Expires));

    // Entries of expired sessions can never be asked for again; drop them at most once a minute.
    private void Sweep()
    {
        var now = clock.GetUtcNow();
        if (now - _lastSweep < TimeSpan.FromMinutes(1)) return;
        _lastSweep = now;
        foreach (var (sid, entry) in _used)
            if (entry.Expires <= now) _used.TryRemove(sid, out _);
    }
}
