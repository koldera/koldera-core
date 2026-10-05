namespace Koldera.Core;

public static class ClientSessionEndpoints
{
    public const int DefaultMaxJobs = 10;
    public const int MaxJobsLimit = 1000;
    public const int MaxUploadsLimit = 10000;

    public sealed record ClientSessionRequest(string? AllowedOrigin, string[]? Scopes, int? MaxJobs, int? TtlSeconds, int? MaxUploads = null);

    public static void MapClientSessionEndpoints(this RouteGroupBuilder v1)
    {
        // Server principals only (SessionPolicy refuses a session before it gets here; the check is repeated for safety).
        v1.MapPost("/client-sessions", (ClientSessionRequest req, HttpContext http, ClientSessionTokens tokens) =>
        {
            if (http.GetCorePrincipal().Kind == "session")
                return Problems.Result(403, "FORBIDDEN", "Forbidden", "A client session cannot mint sessions.");

            var origin = ClientSessionTokens.NormalizeOrigin(req.AllowedOrigin);
            if (origin is null) return Invalid("allowedOrigin must be an absolute http(s) origin without a path, e.g. https://app.example");

            var scopes = req.Scopes is null ? SessionScopes.All : req.Scopes.Distinct(StringComparer.Ordinal).ToArray();
            if (scopes.Length == 0 || scopes.Any(s => !SessionScopes.All.Contains(s)))
                return Invalid($"scopes must be a non-empty subset of {string.Join(", ", SessionScopes.All)}");

            var maxJobs = req.MaxJobs ?? DefaultMaxJobs;
            if (maxJobs is < 1 or > MaxJobsLimit) return Invalid($"maxJobs must be between 1 and {MaxJobsLimit}");

            // A document may reference several images, so a job can need many uploads: ten per job unless set.
            var maxUploads = req.MaxUploads ?? Math.Min(maxJobs * 10, MaxUploadsLimit);
            if (maxUploads is < 1 or > MaxUploadsLimit) return Invalid($"maxUploads must be between 1 and {MaxUploadsLimit}");

            var ttl = req.TtlSeconds is { } s ? TimeSpan.FromSeconds(s) : ClientSessionTokens.DefaultTtl;
            if (ttl < TimeSpan.FromSeconds(1) || ttl > ClientSessionTokens.MaxTtl)
                return Invalid($"ttlSeconds must be between 1 and {(int)ClientSessionTokens.MaxTtl.TotalSeconds}");

            var issued = tokens.Issue(origin, scopes, maxJobs, maxUploads, ttl);
            // publishableKey: the hosted platform's SDKs expect it, core has no publishable keys, so it is always null.
            return Results.Json(new { sessionToken = issued.Token, expiresAt = issued.ExpiresAt, publishableKey = (string?)null }, statusCode: 201);
        });
    }

    private static IResult Invalid(string detail) => Problems.Result(422, "VALIDATION_ERROR", "Invalid client session request", detail);
}
