using System.Security.Cryptography;
using System.Text.Json;

namespace Koldera.Core.Workers;

/// <summary>What one run of the PDF worker produced, in the shape the job record stores.</summary>
public sealed record PdfWorkerRunResult(
    string State,
    string ResultStatus,
    string? ErrorCode,
    string? ErrorMessage,
    string RemediationStatus,
    string? EngineVersion,
    string? BaselineValidationJson,
    string? FinalValidationJson,
    List<CoreFinding> Findings,
    List<CoreAction> Actions,
    List<CoreArtifact> Artifacts);

/// <summary>
/// The PDF worker invocation: the input is copied into scratch and re-hashed, the worker's copy of it is
/// checked against the recorded hash and never kept, every other artifact is verified before it is kept.
/// </summary>
public static class PdfWorkerRun
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public static async Task<PdfWorkerRunResult> ExecuteAsync(
        IWorkerProcess worker, CoreJob job, string inputSourcePath, string expectedSha256, long expectedBytes,
        string origin, WorkerLimits? limits, string scratch, string jobOutDir, DateTimeOffset now, DateTimeOffset retentionExpiry,
        TimeSpan timeout, CancellationToken ct)
    {
        var expected = expectedSha256.ToLowerInvariant();
        var inputPath = Path.Combine(scratch, "in", "document.pdf");
        File.Copy(inputSourcePath, inputPath, overwrite: true);
        if (await Sha256Async(inputPath, ct) != expected)
            throw new WorkerContractException("stored input does not match its recorded SHA-256");

        var request = new WorkerRequest(WorkerContracts.SpecVersion, job.Id, WorkerRequest.CanonicalInputPath, WorkerRequest.CanonicalOutputDirectory,
            job.Mode ?? "analyze", job.Profile ?? "ua1", job.ExplicitLanguage, job.ExplicitTitle, job.SourceFileName, origin, limits);
        var requestPath = Path.Combine(scratch, "request.json");
        var resultPath = Path.Combine(scratch, "result.json");
        await File.WriteAllTextAsync(requestPath, WorkerContracts.SerializeRequest(request), ct);

        var run = await worker.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        if (run.TimedOut) throw new TimeoutException("worker exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"worker exited with code {run.ExitCode} without writing a result");
        var result = WorkerContracts.ParseResult(await WorkerOutput.ReadResultAsync(resultPath, ct), job.Id);
        if (result.OriginalSha256 is not null && result.OriginalSha256 != expected)
            throw new WorkerContractException("worker reported a different original SHA-256");

        var kept = new List<CoreArtifact>();
        foreach (var artifact in result.Artifacts)
        {
            if (artifact.Kind == "original")
            {
                if (artifact.Sha256 != expected || artifact.Bytes != expectedBytes)
                    throw new WorkerContractException("worker original artifact does not match the stored input");
                continue;
            }
            var name = ArtifactFiles.RequireOutputName(artifact.Path, WorkerRequest.CanonicalOutputDirectory);
            kept.Add(await ArtifactFiles.VerifyAndKeepAsync(Path.Combine(scratch, "out", name), artifact.Kind, artifact.Sha256, artifact.Bytes, jobOutDir, now, retentionExpiry, ct));
        }

        var state = result.Status == "completed" ? CoreJobState.Completed : CoreJobState.Failed;
        var errorCode = result.Error?.Code ?? (result.Status == "unsupported" ? "UNSUPPORTED_INPUT" : null);
        var errorMessage = result.Error?.Message ?? (result.Status == "unsupported" ? "the document could not be processed (see findings)" : null);
        var findings = result.Findings.Select(f => new CoreFinding
        {
            Id = Guid.NewGuid(),
            Source = "pdf",
            RuleId = f.RuleId,
            PageNumber = f.PageNumber,
            MachineCheck = !f.NeedsHumanReview,
            Message = f.Message,
            NeedsHumanReview = f.NeedsHumanReview,
            Resolution = f.Resolution,
        }).ToList();
        // Sequence is the action's position in the worker's own result array, which is the order the API serves.
        var actions = result.Actions.Select((a, i) => new CoreAction
        {
            Id = Guid.NewGuid(),
            RuleId = a.RuleId,
            Outcome = a.Outcome,
            Reason = a.Reason,
            Before = a.Before,
            After = a.After,
            Sequence = i,
        }).ToList();
        return new PdfWorkerRunResult(state, result.Status, errorCode, errorMessage, result.RemediationStatus, result.EngineVersion,
            ValidationJson(result.Baseline), result.FinalValidation is null ? null : ValidationJson(result.FinalValidation), findings, actions, kept);
    }

    private static string ValidationJson(WorkerValidation v) =>
        JsonSerializer.Serialize(new { profile = v.Profile, status = v.Status, failedRules = v.FailedRules.Select(r => new { ruleId = r.RuleId, count = r.Count }) }, Json);

    public static async Task<string> Sha256Async(string path, CancellationToken ct)
    {
        await using var stream = File.OpenRead(path);
        return Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, ct));
    }
}
