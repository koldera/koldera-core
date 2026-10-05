using System.Text.RegularExpressions;

namespace Koldera.Core;

public sealed record PdfJobRequest(Guid UploadId, string? Mode, string? Profile, string? ExplicitLanguage, string? ExplicitTitle = null);

public static partial class JobEndpoints
{
    private static readonly byte[] PdfMagic = "%PDF-"u8.ToArray();

    [GeneratedRegex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")]
    private static partial Regex LanguageTag();

    public static void MapJobEndpoints(this RouteGroupBuilder v1)
    {
        v1.MapPost("/pdf/jobs", CreatePdfAsync);
    }

    private static async Task<IResult> CreatePdfAsync(PdfJobRequest request, HttpContext http, DataStore store, JobQueue queue, TimeProvider clock)
    {
        if (request.Mode is not ("analyze" or "fix")) return Invalid("mode must be analyze or fix");
        if (request.Profile != "ua1") return Invalid("profile must be ua1");
        if (request.ExplicitLanguage is not null && !LanguageTag().IsMatch(request.ExplicitLanguage))
            return Invalid("explicitLanguage must be a well-formed BCP 47 tag");
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
        if (!await HasPdfHeaderAsync(store.UploadPath(upload.Id), http.RequestAborted))
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

    /// <summary>Saves the job as queued before enqueueing it, so a runner that picks it up at once finds it; a full queue removes it again.</summary>
    internal static async Task<IResult> AcceptAsync(CoreJob job, HttpContext http, DataStore store, JobQueue queue)
    {
        await store.SaveJobAsync(job);
        if (!queue.TryEnqueue(job.Id))
        {
            store.DeleteJob(job.Id);
            http.Response.Headers.RetryAfter = "30";
            return Problems.Result(503, "QUEUE_FULL", "Job queue is full", "Try again shortly.");
        }
        http.Response.Headers.Location = $"/v1/jobs/{job.Id}";
        return Results.Json(JobResponse.From(job), statusCode: 202);
    }

    private static async Task<bool> HasPdfHeaderAsync(string path, CancellationToken ct)
    {
        // The first 1024 bytes may carry the header after a short preamble (ISO 32000 permits an offset).
        var buffer = new byte[1024];
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        var n = await fs.ReadAsync(buffer, ct);
        return buffer.AsSpan(0, n).IndexOf(PdfMagic) >= 0;
    }

    private static IResult Invalid(string detail) => Problems.Result(422, "VALIDATION_ERROR", "Invalid job request", detail);
}
