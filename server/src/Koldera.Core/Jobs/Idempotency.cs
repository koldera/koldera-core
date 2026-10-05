using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;

namespace Koldera.Core;

public sealed record StoredResponse(int Status, string? ContentType, string? Location, byte[] Body);

public enum IdempotencyOutcome { Miss, Hit, Conflict }

/// <summary>
/// Remembers successful POST responses per (principal, Idempotency-Key) for 24 hours so a retried request returns the
/// original answer instead of creating a second job. In memory: a restart forgets every key, so a retry across a
/// restart may create a duplicate.
/// </summary>
public sealed class Idempotency(TimeProvider clock)
{
    public static readonly TimeSpan Retention = TimeSpan.FromHours(24);
    private const int LockStripes = 64;

    private sealed record Entry(string RequestHash, StoredResponse Response, DateTimeOffset Expires);

    private readonly ConcurrentDictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim[] _locks = Enumerable.Range(0, LockStripes).Select(_ => new SemaphoreSlim(1, 1)).ToArray();
    private DateTimeOffset _lastSweep = DateTimeOffset.MinValue;

    /// <summary>Same key and same request hash is a hit; same key with another hash is a conflict.</summary>
    public IdempotencyOutcome TryGet(string principalKey, string key, string requestHash, out StoredResponse? response)
    {
        response = null;
        if (!_entries.TryGetValue(Compose(principalKey, key), out var entry)) return IdempotencyOutcome.Miss;
        if (entry.Expires <= clock.GetUtcNow()) return IdempotencyOutcome.Miss;
        if (!string.Equals(entry.RequestHash, requestHash, StringComparison.Ordinal)) return IdempotencyOutcome.Conflict;
        response = entry.Response;
        return IdempotencyOutcome.Hit;
    }

    public void Store(string principalKey, string key, string requestHash, StoredResponse response)
    {
        var now = clock.GetUtcNow();
        Sweep(now);
        _entries[Compose(principalKey, key)] = new Entry(requestHash, response, now.Add(Retention));
    }

    /// <summary>Serializes requests that share a key, so two concurrent identical requests create one job (a striped lock, nothing to clean up).</summary>
    public async Task<IDisposable> LockAsync(string principalKey, string key, CancellationToken ct)
    {
        var gate = _locks[(int)((uint)Compose(principalKey, key).GetHashCode() % LockStripes)];
        await gate.WaitAsync(ct);
        return new Releaser(gate);
    }

    private static string Compose(string principalKey, string key) => principalKey + "\n" + key;

    private void Sweep(DateTimeOffset now)
    {
        if (now - _lastSweep < TimeSpan.FromMinutes(1)) return;
        _lastSweep = now;
        foreach (var (k, e) in _entries)
            if (e.Expires <= now) _entries.TryRemove(k, out _);
    }

    private sealed class Releaser(SemaphoreSlim gate) : IDisposable
    {
        public void Dispose() => gate.Release();
    }
}

/// <summary>
/// Applies <see cref="Idempotency"/> to every POST under /v1 that carries an Idempotency-Key. Only 2xx answers are
/// stored: a validation error, a full queue or an incomplete upload must stay retryable under the same key.
/// </summary>
internal sealed class IdempotencyMiddleware(RequestDelegate next, Idempotency store)
{
    private const int MaxKeyLength = 255;

    public async Task InvokeAsync(HttpContext http)
    {
        var request = http.Request;
        if (!HttpMethods.IsPost(request.Method) || !request.Path.StartsWithSegments("/v1")
            || !request.Headers.TryGetValue("Idempotency-Key", out var raw)
            || http.GetEndpoint()?.Metadata.GetMetadata<Microsoft.AspNetCore.Authorization.IAllowAnonymous>() is not null
            || http.Items[CorePrincipalExtensions.ItemKey] is not CorePrincipal principal)
        {
            await next(http);
            return;
        }

        var key = raw.ToString().Trim();
        if (key.Length is 0 or > MaxKeyLength || key.Any(char.IsControl))
        {
            await Problems.Result(422, "VALIDATION_ERROR", "Invalid Idempotency-Key", $"The key must be 1-{MaxKeyLength} printable characters.").ExecuteAsync(http);
            return;
        }
        var principalKey = principal.Kind == "session" ? "session:" + principal.SessionId : "server";

        // A document body has no size cap by design (see DocumentEndpoints); hashing it must not add one.
        if (request.Path.Value == "/v1/documents/jobs")
        {
            var limit = http.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>();
            if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = null;
        }
        request.EnableBuffering(bufferThreshold: 1 << 20, bufferLimit: long.MaxValue);
        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        hasher.AppendData(Encoding.UTF8.GetBytes(request.Method + "\n" + request.Path.Value + "\n"));
        var chunk = new byte[16 * 1024];
        int n;
        while ((n = await request.Body.ReadAsync(chunk, http.RequestAborted)) > 0) hasher.AppendData(chunk.AsSpan(0, n));
        request.Body.Position = 0;
        var hash = Convert.ToHexString(hasher.GetHashAndReset());

        using var gate = await store.LockAsync(principalKey, key, http.RequestAborted);
        switch (store.TryGet(principalKey, key, hash, out var stored))
        {
            case IdempotencyOutcome.Conflict:
                await Problems.Result(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "This Idempotency-Key was already used for a different request.").ExecuteAsync(http);
                return;
            case IdempotencyOutcome.Hit:
                http.Response.StatusCode = stored!.Status;
                if (stored.ContentType is not null) http.Response.ContentType = stored.ContentType;
                if (stored.Location is not null) http.Response.Headers.Location = stored.Location;
                http.Response.Headers["Idempotent-Replayed"] = "true";
                await http.Response.Body.WriteAsync(stored.Body, http.RequestAborted);
                return;
        }

        var original = http.Response.Body;
        await using var capture = new MemoryStream();
        http.Response.Body = capture;
        try { await next(http); }
        finally { http.Response.Body = original; }

        var status = http.Response.StatusCode;
        if (status is >= 200 and < 300)
            store.Store(principalKey, key, hash, new StoredResponse(status, http.Response.ContentType,
                http.Response.Headers.Location.ToString() is { Length: > 0 } loc ? loc : null, capture.ToArray()));
        capture.Position = 0;
        await capture.CopyToAsync(original, http.RequestAborted);
    }
}
