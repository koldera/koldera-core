using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Authorization;

namespace Koldera.Core;

/// <summary>CORS for /v1, decided per request (a static policy cannot see a session token).
/// Signed upload/artifact URLs answer any origin with <c>*</c>, never credentials: the signature is the credential.
/// A preflight cannot carry a token, so it is answered for any origin (the real response is still unreadable without Allow-Origin).
/// The actual response gets Allow-Origin only for an origin in KOLDERA_CORS_ORIGINS or the origin bound in the valid
/// session token sent with it; a session token from any other origin is refused with 401 by the auth handler.
/// With KOLDERA_AUTH=none preflights too are answered only for configured origins. A literal * in KOLDERA_CORS_ORIGINS is not supported and allows nothing.</summary>
internal sealed partial class CorsMiddleware(RequestDelegate next, CoreOptions options, ClientSessionTokens sessions)
{
    private const string AllowedMethods = "GET, POST, PUT";
    private const string AllowedHeaders = "authorization, content-type, idempotency-key, prefer";
    private const string ExposedHeaders = "Retry-After, Preference-Applied, Location, Idempotent-Replayed, Content-Disposition";

    [GeneratedRegex("^/v1/(uploads|artifacts)/[0-9a-fA-F-]{36}/content$")]
    private static partial Regex SignedRoute();

    private readonly HashSet<string> _configured = new(
        options.CorsOrigins.Select(o => ClientSessionTokens.NormalizeOrigin(o) ?? o.TrimEnd('/').ToLowerInvariant()),
        StringComparer.OrdinalIgnoreCase);

    public Task InvokeAsync(HttpContext http)
    {
        if (!http.Request.Path.StartsWithSegments("/v1")) return next(http);
        // Always, so a shared cache never serves one origin's CORS answer to another.
        http.Response.Headers.Append("Vary", "Origin");
        var origin = http.Request.Headers.Origin.ToString();
        if (origin.Length == 0) return next(http);

        var h = http.Response.Headers;
        var signed = SignedRoute().IsMatch(http.Request.Path.Value ?? "");

        if (HttpMethods.IsOptions(http.Request.Method) && http.Request.Headers.ContainsKey("Access-Control-Request-Method"))
        {
            // With auth disabled anyone who can reach the port is the owner, so only configured origins may even preflight:
            // otherwise any website could drive a local open instance from the visitor's browser.
            if (!signed && options.Auth == "none" && !_configured.Contains(origin.TrimEnd('/')))
            {
                http.Response.StatusCode = StatusCodes.Status204NoContent;
                return Task.CompletedTask;
            }
            h["Access-Control-Allow-Origin"] = signed ? "*" : origin;
            h["Access-Control-Allow-Methods"] = AllowedMethods;
            h["Access-Control-Allow-Headers"] = AllowedHeaders;
            h["Access-Control-Max-Age"] = "600";
            http.Response.StatusCode = StatusCodes.Status204NoContent;
            return Task.CompletedTask;
        }

        if (signed) h["Access-Control-Allow-Origin"] = "*";
        else if (IsAllowed(http, origin)) h["Access-Control-Allow-Origin"] = origin;
        else return next(http);
        h["Access-Control-Expose-Headers"] = ExposedHeaders;
        return next(http);
    }

    private bool IsAllowed(HttpContext http, string origin)
    {
        if (_configured.Contains(origin.TrimEnd('/'))) return true;
        var header = http.Request.Headers.Authorization.ToString();
        const string prefix = "Bearer ";
        return header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            && sessions.Resolve(header[prefix.Length..].Trim()) is { AllowedOrigin: { } bound }
            && string.Equals(bound, origin.TrimEnd('/'), StringComparison.OrdinalIgnoreCase);
    }
}

/// <summary>What a session principal may do: no minting, and each route needs its scope. /v1/info is open to every session.</summary>
internal sealed class SessionPolicyMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext http)
    {
        if (http.Items[CorePrincipalExtensions.ItemKey] is CorePrincipal { Kind: "session" } session
            && http.Request.Path.StartsWithSegments("/v1")
            && http.GetEndpoint()?.Metadata.GetMetadata<IAllowAnonymous>() is null)
        {
            var path = http.Request.Path.Value?.TrimEnd('/');
            if (path == "/v1/client-sessions")
            {
                await Problems.Result(403, "FORBIDDEN", "Forbidden", "A client session cannot mint sessions.").ExecuteAsync(http);
                return;
            }
            if (path != "/v1/info")
            {
                var needed = HttpMethods.IsGet(http.Request.Method) || HttpMethods.IsHead(http.Request.Method) ? SessionScopes.Read : SessionScopes.Create;
                if (session.Scopes?.Contains(needed) != true)
                {
                    await Problems.Result(403, "INSUFFICIENT_SCOPE", "Insufficient scope", $"This session token lacks the {needed} scope.").ExecuteAsync(http);
                    return;
                }
            }
        }
        await next(http);
    }
}
