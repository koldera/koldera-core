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

/// <summary>
/// Every POST under /v1 takes JSON. A cross-site form can send text/plain without a CORS preflight, so a route that
/// parsed such a body would be drivable from any web page on an instance without authentication. The signed upload
/// PUT carries raw bytes and is not a POST, so it is unaffected.
/// </summary>
internal sealed class JsonContentTypeMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext http)
    {
        if (HttpMethods.IsPost(http.Request.Method) && http.Request.Path.StartsWithSegments("/v1")
            && !(System.Net.Http.Headers.MediaTypeHeaderValue.TryParse(http.Request.ContentType, out var type)
                 && string.Equals(type.MediaType, "application/json", StringComparison.OrdinalIgnoreCase)))
        {
            await Problems.Result(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type", "Send the body as application/json.").ExecuteAsync(http);
            return;
        }
        await next(http);
    }
}

/// <summary>
/// With KOLDERA_AUTH=none every caller is the owner, and a CORS check alone does not stop DNS rebinding: a web page can
/// point its own host name at 127.0.0.1 and then talk to this server as a same-origin page. Such a request still names
/// the attacker's host in Host, so only KOLDERA_ALLOWED_HOSTS (default loopback names, any port) plus the
/// KOLDERA_PUBLIC_URL host are answered; <c>*</c> switches the check off. With token authentication the token protects.
/// </summary>
internal sealed class HostFilterMiddleware(RequestDelegate next, CoreOptions options)
{
    private readonly HashSet<string> _allowed = BuildAllowed(options);

    private static HashSet<string> BuildAllowed(CoreOptions options)
    {
        var allowed = new HashSet<string>(options.AllowedHosts ?? CoreOptions.DefaultAllowedHosts, StringComparer.OrdinalIgnoreCase);
        if (Uri.TryCreate(options.PublicUrl, UriKind.Absolute, out var publicUrl)) allowed.Add(CoreOptions.NormalizeHost(publicUrl.Authority));
        return allowed;
    }

    public Task InvokeAsync(HttpContext http)
    {
        if (options.Auth != "none" || _allowed.Contains("*")) return next(http);
        var host = CoreOptions.NormalizeHost(http.Request.Host.Value ?? "");
        if (host.Length > 0 && _allowed.Contains(host)) return next(http);
        return Problems.Result(421, "HOST_NOT_ALLOWED", "Host not allowed",
            "With KOLDERA_AUTH=none only the host names in KOLDERA_ALLOWED_HOSTS are answered.").ExecuteAsync(http);
    }
}
