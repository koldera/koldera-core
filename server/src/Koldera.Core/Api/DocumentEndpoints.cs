using System.Text.Json;
using System.Text.Json.Serialization;
using Koldera.Core.Documents;
using Koldera.Core.Workers;

namespace Koldera.Core;

public static class DocumentEndpoints
{
    private sealed record Problem(
        string Type,
        string Title,
        int Status,
        string Code,
        string Detail,
        IReadOnlyList<ValidationError> Errors);

    public const string FileImageNotAllowed = "FILE_IMAGE_NOT_ALLOWED";
    public const string ImageUploadInvalid = "IMAGE_UPLOAD_INVALID";

    private static readonly JsonSerializerOptions Web = new(JsonSerializerDefaults.Web);

    public static void MapDocumentEndpoints(this RouteGroupBuilder v1)
    {
        v1.MapPost("/documents/jobs", CreateAsync);
    }

    private static async Task<IResult> CreateAsync(JsonElement document, HttpContext http, CoreOptions options, DataStore store, JobQueue queue, TimeProvider clock)
    {
        if (!options.Features.Contains("document")) return RequestContext.NotFound();
        if (document.ValueKind != JsonValueKind.Object) return Invalid([new ValidationError("", DocumentErrorCodes.Schema, "The request body must be a document object.")]);

        var errors = DocumentValidator.Validate(document);
        if (errors.Count > 0) return Invalid(errors);

        // The validator accepts both image sources; over HTTP only an upload can be a source.
        var now = clock.GetUtcNow();
        var owner = RequestContext.Owner(http);
        var problems = new List<ValidationError>();
        var unsniffed = new List<(string Path, UploadRecord Upload)>();
        foreach (var image in DocumentImages.Find(document))
        {
            if (image.UploadId is null)
            {
                problems.Add(new ValidationError(image.Path, FileImageNotAllowed, "An image must reference an uploaded file (uploadId); a file path is only available to the command-line tool."));
                continue;
            }
            var upload = Guid.TryParse(image.UploadId, out var id) ? store.GetUpload(id) : null;
            if (upload is null || upload.Owner != owner || !upload.Completed && upload.ExpiresAt <= now)
                problems.Add(new ValidationError(image.Path, ImageUploadInvalid, "uploadId is unknown or expired."));
            else if (!upload.Completed)
                problems.Add(new ValidationError(image.Path, ImageUploadInvalid, "The image upload is not completed; send the file to the upload URL first."));
            else if (!UploadEndpoints.ImageContentTypes.Contains(upload.ContentType))
                problems.Add(new ValidationError(image.Path, ImageUploadInvalid, $"uploadId refers to a {upload.ContentType} upload; an image block needs image/png, image/jpeg or image/svg+xml."));
            else
                unsniffed.Add((image.Path, upload));
        }
        if (problems.Count > 0) return Invalid(problems);

        foreach (var (path, upload) in unsniffed.DistinctBy(u => u.Upload.Id))
        {
            if (await ImageSniffer.MismatchAsync(store.UploadPath(upload.Id), upload.ContentType, http.RequestAborted) is { } mismatch)
                return Problems.Result(415, "UNSUPPORTED_INPUT", "Unsupported input", $"{path}: {mismatch}");
        }

        var job = new CoreJob
        {
            Id = Guid.NewGuid(),
            Kind = CoreJobKind.DocumentToPdf,
            Mode = "generate_analyze",
            State = CoreJobState.Queued,
            Profile = "ua1",
            Owner = owner,
            CreatedAt = now,
        };
        // Stored before the job record so a runner that picks the job up at once finds its input.
        await File.WriteAllTextAsync(DocumentExecutor.InputPath(store, job.Id), document.GetRawText(), http.RequestAborted);
        return await JobEndpoints.AcceptAsync(job, http, store, queue);
    }

    private static IResult Invalid(IReadOnlyList<ValidationError> errors) =>
        Results.Json(
            new Problem("about:blank", "Invalid document", 422, "VALIDATION_ERROR",
                errors.Count == 1 ? errors[0].Message : $"The document has {errors.Count} problems; see errors.", errors),
            Web, contentType: "application/problem+json", statusCode: 422);
}
