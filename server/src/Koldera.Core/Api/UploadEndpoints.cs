using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Http.Features;

namespace Koldera.Core;

public static partial class UploadEndpoints
{
    // The upload record lives for an hour; the signed URL only for ten minutes.
    private static readonly TimeSpan RecordTtl = TimeSpan.FromHours(1);
    private static readonly TimeSpan SignatureTtl = TimeSpan.FromMinutes(10);
    public static readonly string[] ImageContentTypes = ["image/png", "image/jpeg", "image/svg+xml"];
    private static readonly string[] ContentTypes = ["application/pdf", "text/html", .. ImageContentTypes];

    [GeneratedRegex("^[0-9a-f]{64}$")]
    private static partial Regex Sha256Pattern();

    public sealed record UploadRequest(string? FileName, long? SizeBytes, string? Sha256, string? ContentType);

    public static void MapUploadEndpoints(this RouteGroupBuilder v1)
    {
        v1.MapPost("/uploads", (UploadRequest req, HttpContext http, CoreOptions options, DataStore store, UrlSigner signer, TimeProvider clock) =>
        {
            var name = (req.FileName ?? "").Trim();
            if (name.Length > 255) name = name[..255];
            if (name.Length == 0) return Invalid("fileName is required");
            if (req.ContentType is null || !ContentTypes.Contains(req.ContentType)) return Invalid("contentType must be application/pdf, text/html, image/png, image/jpeg or image/svg+xml");
            if (req.SizeBytes is not > 0) return Invalid("sizeBytes must be a positive integer");
            if (req.Sha256 is null || !Sha256Pattern().IsMatch(req.Sha256)) return Invalid("sha256 must be 64 lowercase hexadecimal characters");

            var maxBytes = req.ContentType == "text/html" ? options.Limits.HtmlMaxBytes : ImageContentTypes.Contains(req.ContentType) ? options.Limits.ImageMaxBytes : options.Limits.PdfMaxBytes;
            if (maxBytes is { } cap && req.SizeBytes > cap)
                return Problems.Result(422, "FILE_TOO_LARGE", "File too large", $"The configured limit is {cap} bytes.");

            // A session may declare only maxUploads uploads; validation errors above cost nothing, a failure below gives it back.
            var principal = http.GetCorePrincipal();
            var budgets = principal.Kind == "session" ? http.RequestServices.GetRequiredService<SessionBudgets>() : null;
            if (budgets is not null && !budgets.TryReserve(principal, SessionBudgetKind.Uploads))
                return Problems.Result(429, "SESSION_UPLOAD_LIMIT", "Session upload limit reached", "This client session has used its upload budget; mint a new session.");
            var now = clock.GetUtcNow();
            var id = Guid.NewGuid();
            UploadRecord record;
            try
            {
                record = store.CreateUpload(new UploadRecord(
                    id, name, req.SizeBytes.Value, req.Sha256, req.ContentType, RequestContext.Owner(http), now, now.Add(RecordTtl), false));
            }
            catch
            {
                budgets?.Release(principal, SessionBudgetKind.Uploads);
                throw;
            }
            var baseUrl = RequestContext.BaseUrl(http, options);
            return Results.Json(new
            {
                uploadId = id,
                method = "PUT",
                uploadUrl = baseUrl + signer.Sign($"/v1/uploads/{id}/content", SignatureTtl),
                expiresAt = now.Add(SignatureTtl),
                maxBytes,
            }, statusCode: 201);
        });

        // Reachable without a bearer token: the signature is the credential.
        v1.MapPut("/uploads/{id:guid}/content", async (Guid id, string? exp, string? sig, HttpContext http, DataStore store, UrlSigner signer) =>
        {
            if (!signer.Verify(http.Request.Path.Value ?? "", exp, sig))
                return Problems.Result(403, "SIGNATURE_INVALID", "Invalid or expired signature");
            // The declared size bounds the stream, so the server default body limit does not apply.
            var limit = http.Features.Get<IHttpMaxRequestBodySizeFeature>();
            if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = null;
            try
            {
                await store.CompleteUploadAsync(id, http.Request.Body, http.RequestAborted);
                return Results.NoContent();
            }
            catch (UploadException e)
            {
                return e.Code switch
                {
                    "UPLOAD_ALREADY_COMPLETED" => Problems.Result(409, e.Code, "Upload already completed"),
                    "UPLOAD_CONFLICT" => Problems.Result(409, e.Code, "A different file already exists for this upload"),
                    "UPLOAD_EXPIRED" => Problems.Result(410, e.Code, "Upload expired"),
                    "UPLOAD_NOT_FOUND" => Problems.Result(404, e.Code, "Upload not found"),
                    "SIZE_MISMATCH" => Problems.Result(422, e.Code, "Body size differs from the declared size"),
                    _ => Problems.Result(422, e.Code, "Body checksum differs from the declared sha256"),
                };
            }
        }).AllowAnonymous();
    }

    private static IResult Invalid(string detail) => Problems.Result(422, "VALIDATION_ERROR", "Invalid upload declaration", detail);
}
