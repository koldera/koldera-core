using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Http.Features;

namespace Koldera.Core;

public static partial class UploadEndpoints
{
    // The upload record lives for an hour; the signed URL only for ten minutes.
    private static readonly TimeSpan RecordTtl = TimeSpan.FromHours(1);
    private static readonly TimeSpan SignatureTtl = TimeSpan.FromMinutes(10);
    private static readonly string[] ContentTypes = ["application/pdf", "text/html"];

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
            if (req.ContentType is null || !ContentTypes.Contains(req.ContentType)) return Invalid("contentType must be application/pdf or text/html");
            if (req.SizeBytes is not > 0) return Invalid("sizeBytes must be a positive integer");
            if (req.Sha256 is null || !Sha256Pattern().IsMatch(req.Sha256)) return Invalid("sha256 must be 64 lowercase hexadecimal characters");

            long? maxBytes = req.ContentType == "text/html" ? options.Limits.HtmlMaxBytes : options.Limits.PdfMaxBytes;
            if (maxBytes is { } cap && req.SizeBytes > cap)
                return Problems.Result(422, "FILE_TOO_LARGE", "File too large", $"The configured limit is {cap} bytes.");

            var now = clock.GetUtcNow();
            var id = Guid.NewGuid();
            var record = store.CreateUpload(new UploadRecord(
                id, name, req.SizeBytes.Value, req.Sha256, req.ContentType, Owner(http), now, now.Add(RecordTtl), false));
            var baseUrl = (options.PublicUrl ?? $"{http.Request.Scheme}://{http.Request.Host}").TrimEnd('/');
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

    // Server principals own what they create; session owners ("session:<sid>") arrive with sessions.
    private static string Owner(HttpContext http) => "server";

    private static IResult Invalid(string detail) => Problems.Result(422, "VALIDATION_ERROR", "Invalid upload declaration", detail);
}
