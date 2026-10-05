using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace Koldera.Core;

/// <summary>Who is calling. Kind is "server", "session" or "anonymous" (auth disabled, server rights).</summary>
public sealed record CorePrincipal(string Kind, string? SessionId = null, string? AllowedOrigin = null, int? MaxJobs = null,
    IReadOnlyList<string>? Scopes = null, DateTimeOffset? ExpiresAt = null, int? MaxUploads = null);

public static class CorePrincipalExtensions
{
    internal const string ItemKey = "koldera.principal";

    public static CorePrincipal GetCorePrincipal(this HttpContext context) =>
        context.Items[ItemKey] as CorePrincipal ?? throw new InvalidOperationException("No authenticated principal on this request.");
}

/// <summary>Turns a bearer token into a principal. One resolver per token kind; null means "not mine / invalid".</summary>
public interface ITokenResolver
{
    CorePrincipal? Resolve(string token);
}

/// <summary>The active server tokens, resolved once at startup.</summary>
public sealed class ServerTokens(IReadOnlyList<string> tokens) : ITokenResolver
{
    public IReadOnlyList<string> Tokens { get; } = tokens;

    public CorePrincipal? Resolve(string token)
    {
        var candidate = Encoding.UTF8.GetBytes(token);
        var match = false;
        // Every configured token is compared, so timing does not reveal which one matched.
        foreach (var t in Tokens)
            match |= TokenAuth.FixedTimeEquals(candidate, Encoding.UTF8.GetBytes(t));
        return match ? new CorePrincipal("server") : null;
    }
}

public static class TokenAuth
{
    public const string Scheme = "Bearer";

    public static IReadOnlyList<string> ResolveTokens(CoreOptions options, ILogger log)
    {
        if (options.Auth == "none") return [];
        var configured = (options.Token ?? "")
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Distinct(StringComparer.Ordinal)
            .ToList();
        if (configured.Count > 0) return configured;

        var generated = Base64Url(RandomNumberGenerator.GetBytes(32));
        var json = JsonSerializer.Serialize(new { @event = "token_generated", token = generated });
        // The only place the generated token is ever logged.
        log.LogWarning("{Event}", json);
        return [generated];
    }

    internal static bool FixedTimeEquals(byte[] a, byte[] b)
    {
        var length = Math.Max(a.Length, b.Length);
        var pa = new byte[length];
        var pb = new byte[length];
        a.CopyTo(pa, 0);
        b.CopyTo(pb, 0);
        var contentEqual = CryptographicOperations.FixedTimeEquals(pa, pb);
        return contentEqual & (a.Length == b.Length);
    }

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static IServiceCollection AddCoreAuthentication(this IServiceCollection services)
    {
        services.AddSingleton(sp =>
        {
            var log = sp.GetRequiredService<ILoggerFactory>().CreateLogger("Koldera.Core.Auth");
            return new ServerTokens(ResolveTokens(sp.GetRequiredService<CoreOptions>(), log));
        });
        services.AddSingleton<ITokenResolver>(sp => sp.GetRequiredService<ServerTokens>());
        services.AddAuthentication(Scheme).AddScheme<AuthenticationSchemeOptions, BearerHandler>(Scheme, null);
        services.AddAuthorization();
        return services;
    }
}

internal sealed class BearerHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    CoreOptions core,
    IEnumerable<ITokenResolver> resolvers) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        if (core.Auth == "none") return Task.FromResult(Succeed(new CorePrincipal("anonymous")));

        var header = Request.Headers.Authorization.ToString();
        const string prefix = "Bearer ";
        if (!header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            return Task.FromResult(AuthenticateResult.NoResult());
        var token = header[prefix.Length..].Trim();
        if (token.Length == 0) return Task.FromResult(AuthenticateResult.NoResult());

        foreach (var resolver in resolvers)
            if (resolver.Resolve(token) is { } principal)
            {
                // A browser token only works from the origin it was minted for, and a browser always sends Origin.
                if (principal.Kind == "session"
                    && !string.Equals(Request.Headers.Origin.ToString().TrimEnd('/'), principal.AllowedOrigin, StringComparison.OrdinalIgnoreCase))
                    return Task.FromResult(AuthenticateResult.Fail("Session token used from another origin."));
                return Task.FromResult(Succeed(principal));
            }
        return Task.FromResult(AuthenticateResult.Fail("Invalid bearer token."));
    }

    private AuthenticateResult Succeed(CorePrincipal principal)
    {
        Context.Items[CorePrincipalExtensions.ItemKey] = principal;
        var identity = new ClaimsIdentity([new Claim(ClaimTypes.Name, principal.Kind)], TokenAuth.Scheme);
        return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), TokenAuth.Scheme));
    }

    protected override async Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        Response.Headers.WWWAuthenticate = "Bearer";
        await Problems.Result(401, "UNAUTHORIZED", "Unauthorized", "A valid bearer token is required.").ExecuteAsync(Context);
    }
}
