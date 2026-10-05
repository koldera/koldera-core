namespace Koldera.Core;

/// <summary>Who is calling and what they may see; every owner-scoped route goes through here.</summary>
public static class RequestContext
{
    // Server (and auth-disabled) principals share the owner "server"; each client session owns only what it created.
    public static string Owner(HttpContext http) =>
        http.GetCorePrincipal() is { Kind: "session" } s ? "session:" + s.SessionId : "server";

    public static bool Owns(HttpContext http, CoreJob job) => job.Owner == Owner(http);

    /// <summary>The externally visible base URL for signed links.</summary>
    public static string BaseUrl(HttpContext http, CoreOptions options) =>
        (options.PublicUrl ?? $"{http.Request.Scheme}://{http.Request.Host}").TrimEnd('/');

    /// <summary>The job when it exists and belongs to the caller; otherwise null, so callers answer 404 either way.</summary>
    public static CoreJob? OwnedJob(HttpContext http, DataStore store, Guid id) =>
        store.GetJob(id) is { } job && Owns(http, job) ? job : null;

    public static IResult NotFound() => Problems.Result(404, "NOT_FOUND", "Not found");
}
