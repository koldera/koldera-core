using System.Text.Json;
using System.Text.Json.Serialization;
using Koldera.Core.Documents;

namespace Koldera.Core.Workers;

/// <summary>The Typst renderer process (the <c>document</c> verb of the renderer CLI).</summary>
public sealed record DocumentWorkers(IWorkerProcess Render);

public sealed record DocumentRenderResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("artifacts")] List<DocumentRenderArtifact> Artifacts,
    [property: JsonPropertyName("error")] DocumentRenderError? Error);

public sealed record DocumentRenderArtifact(
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("path")] string Path,
    [property: JsonPropertyName("sha256")] string Sha256,
    [property: JsonPropertyName("bytes")] long Bytes);

public sealed record DocumentRenderError(
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("message")] string Message);

/// <summary>
/// One structured-document job: Typst renders the stored document (plus the image uploads it names) to a
/// tagged PDF, then the PDF engine analyzes that file. A failed veraPDF rule fails the job with
/// <see cref="ValidationFailed"/> but keeps the PDF and the findings, so a person can inspect them.
/// </summary>
public sealed class DocumentExecutor(DataStore store, DocumentWorkers document, PdfWorkers pdf, CoreOptions options, TimeProvider clock) : IJobExecutor
{
    public const string ValidationFailed = "DOCUMENT_VALIDATION_FAILED";
    private const string RequestSchema = "document-render-request.schema.json";
    private const string ResultSchema = "document-render-result.schema.json";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public string Kind => CoreJobKind.DocumentToPdf;

    /// <summary>Where the validated document of a job is stored until the job runs (the job directory, so it leaves with the job).</summary>
    public static string InputPath(DataStore store, Guid jobId) => Path.Combine(store.JobDir(jobId), "document.json");

    public async Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct)
    {
        var stored = InputPath(store, job.Id);
        if (!File.Exists(stored)) throw new WorkerContractException("the stored document is missing");
        var text = await File.ReadAllTextAsync(stored, ct);
        await File.WriteAllTextAsync(Path.Combine(scratch, "in", "document.json"), text, ct);
        using var parsed = JsonDocument.Parse(text);

        // The renderer looks an upload image up as in/images/<uploadId>; the name is the document's own string.
        var images = Path.Combine(scratch, "in", "images");
        Directory.CreateDirectory(images);
        foreach (var image in DocumentImages.Find(parsed.RootElement).Where(i => i.UploadId is not null).DistinctBy(i => i.UploadId))
        {
            if (!Guid.TryParse(image.UploadId, out var uploadId) || store.GetUpload(uploadId) is not { Completed: true } upload)
                throw new WorkerContractException("an image upload is missing");
            var target = Path.Combine(images, image.UploadId!);
            File.Copy(store.UploadPath(upload.Id), target, overwrite: true);
            if (await PdfWorkerRun.Sha256Async(target, ct) != upload.Sha256.ToLowerInvariant())
                throw new WorkerContractException("a stored image does not match its recorded SHA-256");
        }

        var requestPath = Path.Combine(scratch, "request.json");
        var resultPath = Path.Combine(scratch, "result.json");
        var request = JsonSerializer.Serialize(new
        {
            specVersion = "1.0",
            jobId = job.Id,
            documentPath = "/work/in/document.json",
            imagesDirectory = "/work/in/images",
            outputDirectory = "/work/out",
            typstCommand = options.TypstCommand,
        }, Json);
        var requestErrors = SchemaResources.Load(RequestSchema).Validate(request);
        if (requestErrors.Count > 0) throw new WorkerContractException("document render request violates schema: " + string.Join("; ", requestErrors));
        await File.WriteAllTextAsync(requestPath, request, ct);

        var run = await document.Render.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        if (run.TimedOut) throw new TimeoutException("renderer exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"renderer exited with code {run.ExitCode} without writing a result");
        var rendered = ParseResult(await File.ReadAllTextAsync(resultPath, ct), job.Id);

        if (rendered.Status != "completed")
        {
            if (rendered.Artifacts.Count > 0) throw new WorkerContractException("renderer published an artifact for a failed render");
            var error = rendered.Error ?? throw new WorkerContractException("failed render without an error");
            return new JobOutcome(CoreJobState.Failed, "failed", error.Code, error.Message, "not_requested", rendered.EngineVersion, null, null, [], [], []);
        }

        var declared = rendered.Artifacts.SingleOrDefault(a => a.Kind == "generated_pdf") ?? throw new WorkerContractException("completed render without a generated_pdf artifact");
        var generatedName = ArtifactFiles.RequireOutputName(declared.Path, "/work/out");
        var now = clock.GetUtcNow();
        var retention = now.AddHours(options.RetentionHours);
        var outDir = Path.Combine(store.JobDir(job.Id), "out");
        var generated = await ArtifactFiles.VerifyAndKeepAsync(Path.Combine(scratch, "out", generatedName), "generated_pdf", declared.Sha256, declared.Bytes, outDir, now, retention, ct);

        // The engine runs in its own scratch layout, always in analyze mode: the renderer's output is what is judged, never repaired.
        var pdfScratch = Path.Combine(scratch, "pdf");
        Directory.CreateDirectory(Path.Combine(pdfScratch, "in"));
        Directory.CreateDirectory(Path.Combine(pdfScratch, "out"));
        var engineJob = new CoreJob { Id = job.Id, Mode = "analyze", Profile = job.Profile ?? "ua1", SourceFileName = job.SourceFileName };
        var engine = await PdfWorkerRun.ExecuteAsync(pdf.Process, engineJob, Path.Combine(outDir, generated.FileName), generated.Sha256, generated.Bytes, WorkerOrigin.Upload,
            PdfJobExecutor.LimitsFrom(options.Limits), pdfScratch, outDir, now, retention, timeout, ct);

        var version = HtmlToPdfExecutor.Truncate(string.IsNullOrEmpty(engine.EngineVersion) ? rendered.EngineVersion : rendered.EngineVersion + "; " + engine.EngineVersion);
        var artifacts = new List<CoreArtifact> { generated };
        artifacts.AddRange(engine.Artifacts);
        if (engine.State != CoreJobState.Completed)
            return new JobOutcome(engine.State, engine.ResultStatus, engine.ErrorCode, engine.ErrorMessage, engine.RemediationStatus, version, engine.BaselineValidationJson, engine.FinalValidationJson, engine.Findings, engine.Actions, artifacts);

        var failed = FailedRules(engine.FinalValidationJson ?? engine.BaselineValidationJson);
        if (failed.Count > 0)
        {
            var message = $"the generated PDF fails {failed.Count} veraPDF rule(s): {string.Join(", ", failed.Take(10))}; the file and the findings are kept for inspection";
            return new JobOutcome(CoreJobState.Failed, "failed", ValidationFailed, message, engine.RemediationStatus, version, engine.BaselineValidationJson, engine.FinalValidationJson, engine.Findings, engine.Actions, artifacts);
        }
        return new JobOutcome(CoreJobState.Completed, engine.ResultStatus, null, null, engine.RemediationStatus, version, engine.BaselineValidationJson, engine.FinalValidationJson, engine.Findings, engine.Actions, artifacts);
    }

    private static List<string> FailedRules(string? validationJson)
    {
        if (validationJson is null) return [];
        using var doc = JsonDocument.Parse(validationJson);
        return doc.RootElement.GetProperty("failedRules").EnumerateArray().Select(r => r.GetProperty("ruleId").GetString()!).ToList();
    }

    internal static DocumentRenderResult ParseResult(string json, Guid expectedJobId)
    {
        var errors = SchemaResources.Load(ResultSchema).Validate(json);
        if (errors.Count > 0) throw new WorkerContractException("document render result violates schema: " + string.Join("; ", errors));
        var result = JsonSerializer.Deserialize<DocumentRenderResult>(json, Json) ?? throw new WorkerContractException("document render result is empty");
        if (result.JobId != expectedJobId) throw new WorkerContractException("document render result jobId does not match the dispatched job");
        return result;
    }
}
