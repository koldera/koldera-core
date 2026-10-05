using Koldera.Core.Workers;

namespace Koldera.Core;

public sealed record PdfJobRequest(Guid UploadId, string? Mode, string? Profile, string? ExplicitLanguage, string? ExplicitTitle = null);

public sealed record HtmlJobRequest(Guid UploadId, string? Mode, string? Profile, string? ContentSelector, string? ExplicitLanguage = null, string? ExplicitTitle = null);

public sealed record WebJobRequest(string? Url);

public static class JobEndpoints
{
    public static void MapJobEndpoints(this RouteGroupBuilder v1)
    {
        v1.MapPost("/pdf/jobs", CreatePdfAsync);
        v1.MapPost("/html-to-pdf/jobs", CreateHtmlAsync);
        v1.MapPost("/web/jobs", CreateWebAsync);
    }

    private static async Task<IResult> CreateWebAsync(WebJobRequest request, HttpContext http, CoreOptions options, DataStore store, JobQueue queue, TimeProvider clock)
    {
        if (!options.Features.Contains("web")) return RequestContext.NotFound();
        // Syntax only; the scanner repeats the checks with live DNS. The reason never repeats the URL.
        var reason = ScanTargetPolicy.Reject(request.Url, options.WebAllowHttp, out var target);
        if (reason is not null || target is null) return Invalid("url rejected: " + reason);

        var job = new CoreJob
        {
            Id = Guid.NewGuid(),
            Kind = CoreJobKind.Web,
            Mode = "scan",
            State = CoreJobState.Queued,
            Profile = WebJobExecutor.Profile,
            TargetUrl = target.AbsoluteUri, // query and fragment already removed: they may carry tokens
            Owner = RequestContext.Owner(http),
            CreatedAt = clock.GetUtcNow(),
        };
        return await AcceptAsync(job, http, store, queue);
    }

    private static async Task<IResult> CreatePdfAsync(PdfJobRequest request, HttpContext http, DataStore store, JobQueue queue, TimeProvider clock)
    {
        if (request.Mode is not ("analyze" or "fix")) return Invalid("mode must be analyze or fix");
        if (request.Profile != "ua1") return Invalid("profile must be ua1");
        if (request.ExplicitLanguage is not null && !JobRequestRules.IsLanguageTag(request.ExplicitLanguage))
            return Invalid(JobRequestRules.LanguageError);
        var explicitTitle = TitleInput.Normalize(request.ExplicitTitle, out var titleError);
        if (titleError is not null) return Invalid(titleError);

        var upload = store.GetUpload(request.UploadId);
        var now = clock.GetUtcNow();
        if (upload is null || upload.Owner != RequestContext.Owner(http) || upload.ExpiresAt <= now && !upload.Completed)
            return Invalid("uploadId is unknown or expired");
        if (!upload.Completed)
            return Problems.Result(409, "UPLOAD_INCOMPLETE", "Upload not completed", "Send the file to the upload URL first.");
        if (upload.ContentType != "application/pdf")
            return Problems.Result(415, "UNSUPPORTED_INPUT", "Unsupported input", $"uploadId refers to a {upload.ContentType} upload; PDF jobs need application/pdf");
        if (!await JobRequestRules.HasPdfHeaderAsync(store.UploadPath(upload.Id), http.RequestAborted))
            return Problems.Result(415, "UNSUPPORTED_INPUT", "Unsupported input", "uploaded file is not a PDF (missing %PDF- header)");

        var job = new CoreJob
        {
            Id = Guid.NewGuid(),
            Kind = CoreJobKind.Pdf,
            Mode = request.Mode,
            State = CoreJobState.Queued,
            Profile = request.Profile,
            ExplicitLanguage = request.ExplicitLanguage,
            ExplicitTitle = explicitTitle,
            SourceFileName = upload.FileName,
            InputUploadId = upload.Id,
            Owner = RequestContext.Owner(http),
            CreatedAt = now,
        };
        return await AcceptAsync(job, http, store, queue);
    }

    private static async Task<IResult> CreateHtmlAsync(HtmlJobRequest request, HttpContext http, CoreOptions options, DataStore store, JobQueue queue, TimeProvider clock)
    {
        if (!options.Features.Contains("html")) return RequestContext.NotFound();
        if (request.Mode is not ("generate_analyze" or "generate_fix")) return Invalid("mode must be generate_analyze or generate_fix");
        if (request.Profile != "ua1") return Invalid("profile must be ua1");
        if (request.ExplicitLanguage is not null && !JobRequestRules.IsLanguageTag(request.ExplicitLanguage))
            return Invalid(JobRequestRules.LanguageError);
        var selector = JobRequestRules.NormalizeSelector(request.ContentSelector, out var selectorError);
        if (selectorError is not null) return Invalid(selectorError);
        var explicitTitle = TitleInput.Normalize(request.ExplicitTitle, out var titleError);
        if (titleError is not null) return Invalid(titleError);

        var upload = store.GetUpload(request.UploadId);
        var now = clock.GetUtcNow();
        if (upload is null || upload.Owner != RequestContext.Owner(http) || upload.ExpiresAt <= now && !upload.Completed)
            return Invalid("uploadId is unknown or expired");
        if (!upload.Completed)
            return Problems.Result(409, "UPLOAD_INCOMPLETE", "Upload not completed", "Send the file to the upload URL first.");
        if (upload.ContentType != "text/html")
            return Invalid($"uploadId refers to a {upload.ContentType} upload; HTML-to-PDF jobs need a text/html upload");
        if (await JobRequestRules.SniffHtmlAsync(store.UploadPath(upload.Id), http.RequestAborted) is { } unsupported)
            return Problems.Result(415, "UNSUPPORTED_INPUT", "Unsupported input", unsupported);

        var job = new CoreJob
        {
            Id = Guid.NewGuid(),
            Kind = CoreJobKind.HtmlToPdf,
            Mode = request.Mode,
            State = CoreJobState.Queued,
            Profile = request.Profile,
            ExplicitLanguage = request.ExplicitLanguage,
            ExplicitTitle = explicitTitle,
            ContentSelector = selector,
            SourceFileName = upload.FileName,
            InputUploadId = upload.Id,
            Owner = RequestContext.Owner(http),
            CreatedAt = now,
        };
        return await AcceptAsync(job, http, store, queue);
    }

    /// <summary>Saves the job as queued before enqueueing it, so a runner that picks it up at once finds it; a full queue removes it again.</summary>
    internal static async Task<IResult> AcceptAsync(CoreJob job, HttpContext http, DataStore store, JobQueue queue)
    {
        // A session may start only maxJobs jobs; the reservation is given back if the job is not accepted after all.
        var principal = http.GetCorePrincipal();
        var budgets = principal.Kind == "session" ? http.RequestServices.GetRequiredService<SessionBudgets>() : null;
        if (budgets is not null && !budgets.TryReserve(principal, SessionBudgetKind.Jobs))
            return Problems.Result(429, "SESSION_JOB_LIMIT", "Session job limit reached", "This client session has used its job budget; mint a new session.");
        try { await store.SaveJobAsync(job); }
        catch { budgets?.Release(principal, SessionBudgetKind.Jobs); throw; }
        if (!queue.TryEnqueue(job.Id))
        {
            budgets?.Release(principal, SessionBudgetKind.Jobs);
            store.DeleteJob(job.Id);
            http.Response.Headers.RetryAfter = "30";
            return Problems.Result(503, "QUEUE_FULL", "Job queue is full", "Try again shortly.");
        }
        http.Response.Headers.Location = $"/v1/jobs/{job.Id}";
        return Results.Json(JobResponse.From(job), statusCode: 202);
    }

    private static IResult Invalid(string detail) => Problems.Result(422, "VALIDATION_ERROR", "Invalid job request", detail);
}
