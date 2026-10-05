using System.Collections.Concurrent;

namespace Koldera.Core;

/// <summary>Maps artifact id to job id so a download does not scan every job; rebuilt from the data directory at start.</summary>
public sealed class ArtifactIndex
{
    private readonly DataStore _store;
    private readonly ConcurrentDictionary<Guid, Guid> _jobByArtifact = new();

    public ArtifactIndex(DataStore store)
    {
        _store = store;
        Rebuild();
    }

    public void Register(CoreJob job)
    {
        foreach (var a in job.Artifacts) _jobByArtifact[a.Id] = job.Id;
    }

    /// <summary>A miss rescans the store once, for artifacts of jobs that finished after start and were never listed.</summary>
    public bool TryFind(Guid artifactId, out Guid jobId)
    {
        if (_jobByArtifact.TryGetValue(artifactId, out jobId)) return true;
        Rebuild();
        return _jobByArtifact.TryGetValue(artifactId, out jobId);
    }

    private void Rebuild()
    {
        foreach (var job in _store.AllJobs()) Register(job);
    }
}

/// <summary>Job status, findings, actions and artifact links. Every lookup is limited to the caller's own jobs.</summary>
public static class JobReadEndpoints
{
    private const int PollDelaySeconds = 2;
    private static readonly TimeSpan DownloadTtl = TimeSpan.FromMinutes(10);
    // Every artifact kind the workers can publish (contracts/*-result.schema.json plus the reorder copy).
    private static readonly HashSet<string> DownloadKinds = ["original", "remediated", "baseline_report", "final_report", "generated_pdf", "source_html", "web_report", "reordered"];

    public static void MapJobReadEndpoints(this RouteGroupBuilder v1)
    {
        var jobs = v1.MapGroup("/jobs/{jobId:guid}");

        jobs.MapGet("/", (Guid jobId, HttpContext http, DataStore store) =>
        {
            var job = RequestContext.OwnedJob(http, store, jobId);
            if (job is null) return RequestContext.NotFound();
            if (job.State is CoreJobState.Queued or CoreJobState.Running)
                http.Response.Headers.RetryAfter = PollDelaySeconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
            return Results.Ok(JobResponse.From(job));
        });

        jobs.MapGet("/findings", (Guid jobId, HttpContext http, DataStore store) =>
        {
            var job = RequestContext.OwnedJob(http, store, jobId);
            if (job is null) return RequestContext.NotFound();
            var items = job.Findings.OrderBy(f => f.RuleId, StringComparer.Ordinal).ThenBy(f => f.PageNumber).Select(FindingResponse.From);
            return Results.Ok(new { items });
        });

        jobs.MapGet("/actions", (Guid jobId, HttpContext http, DataStore store) =>
        {
            var job = RequestContext.OwnedJob(http, store, jobId);
            if (job is null) return RequestContext.NotFound();
            return Results.Ok(new { items = job.Actions.OrderBy(a => a.Sequence).Select(ActionResponse.From) });
        });

        jobs.MapGet("/artifacts", (Guid jobId, HttpContext http, DataStore store, ArtifactIndex index, UrlSigner signer, CoreOptions options, TimeProvider clock) =>
        {
            var job = RequestContext.OwnedJob(http, store, jobId);
            if (job is null) return RequestContext.NotFound();
            index.Register(job);
            var now = clock.GetUtcNow();
            var baseUrl = RequestContext.BaseUrl(http, options);
            var items = job.Artifacts.Where(a => a.ExpiresAt > now).OrderBy(a => a.CreatedAt).Select(a =>
                new ArtifactResponse(a.Id, a.Kind, a.Sha256, a.Bytes, baseUrl + signer.Sign($"/v1/artifacts/{a.Id}/content", DownloadTtl), a.ExpiresAt));
            return Results.Ok(new { items });
        });

        // Reachable without a bearer token: the signature is the credential.
        v1.MapGet("/artifacts/{artifactId:guid}/content", (Guid artifactId, string? exp, string? sig, HttpContext http, DataStore store, ArtifactIndex index, UrlSigner signer, TimeProvider clock) =>
        {
            if (!signer.Verify(http.Request.Path.Value ?? "", exp, sig))
                return Problems.Result(403, "SIGNATURE_INVALID", "Invalid or expired signature");
            if (!index.TryFind(artifactId, out var jobId) || store.GetJob(jobId) is not { } job
                || job.Artifacts.FirstOrDefault(a => a.Id == artifactId) is not { } artifact || artifact.ExpiresAt <= clock.GetUtcNow())
                return Problems.Result(404, "NOT_FOUND", "Not found");
            var path = Path.Combine(store.JobDir(jobId), "out", artifact.FileName);
            if (!File.Exists(path)) return Problems.Result(404, "NOT_FOUND", "Not found");

            var extension = Path.GetExtension(artifact.FileName).ToLowerInvariant();
            var contentType = extension switch { ".pdf" => "application/pdf", ".html" => "text/html; charset=utf-8", _ => "application/json" };
            // Never the user's file name, and only a known kind: both could carry anything.
            var kind = DownloadKinds.Contains(artifact.Kind) ? artifact.Kind : "artifact";
            http.Response.Headers.ContentDisposition = $"attachment; filename=\"{kind}{extension}\"";
            http.Response.Headers["X-Content-Type-Options"] = "nosniff";
            // A source_html artifact is caller markup served from the API origin: opened directly, it runs no script and loads nothing.
            http.Response.Headers.ContentSecurityPolicy = "sandbox; default-src 'none'";
            return Results.File(path, contentType);
        }).AllowAnonymous();
    }
}
