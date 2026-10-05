namespace Koldera.Core.Workers;

/// <summary>The PDF engine's two processes: the request/result verb and the optional read-only structure probe.</summary>
public sealed record PdfWorkers(IWorkerProcess Process, IWorkerProcess? StructureProbe);

/// <summary>
/// Runs the PDF engine on an upload: worker <c>completed</c> means job completed, <c>unsupported</c> or
/// <c>failed</c> means job failed. A completed run is followed by the structure probe when one is configured.
/// </summary>
public sealed class PdfJobExecutor(DataStore store, PdfWorkers workers, CoreOptions options, TimeProvider clock) : IJobExecutor
{
    public string Kind => CoreJobKind.Pdf;

    public async Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct)
    {
        var upload = job.InputUploadId is { } uploadId ? store.GetUpload(uploadId) : null;
        if (upload is null || !upload.Completed) throw new WorkerContractException("the input upload is missing");

        var now = clock.GetUtcNow();
        var outDir = Path.Combine(store.JobDir(job.Id), "out");
        var run = await PdfWorkerRun.ExecuteAsync(workers.Process, job, store.UploadPath(upload.Id), upload.Sha256, upload.SizeBytes,
            WorkerOrigin.Upload, LimitsFrom(options.Limits), scratch, outDir, now, now.AddHours(options.RetentionHours), timeout, ct);
        var findings = run.Findings;
        int? pages = null;
        var snapshots = new List<CoreSnapshot>();
        if (workers.StructureProbe is not null && run.State == CoreJobState.Completed)
        {
            // The order evidence describes the original as uploaded, except that a fix which wrote a tagged copy
            // makes that copy the subject: an untagged original has no structure to review.
            var subject = job.Mode == "fix" ? run.Artifacts.FirstOrDefault(a => a.Kind == "remediated") : null;
            var subjectPath = subject is null ? Path.Combine(scratch, "in", "document.pdf") : Path.Combine(outDir, subject.FileName);
            if (!File.Exists(subjectPath)) throw new WorkerContractException("tagged copy missing on disk for the structure probe");
            var structure = await Snapshots.ProbeStructureAsync(workers.StructureProbe, scratch, subjectPath, timeout, ct);
            if (structure.Status == "inspected")
            {
                if (structure.Sha256 != (subject?.Sha256 ?? upload.Sha256.ToLowerInvariant()))
                    throw new WorkerContractException("structure probe reported a different SHA-256 than the artifact it was given");
                snapshots.Add(Snapshots.FromStructure(structure, clock.GetUtcNow()));
                findings = [.. findings, .. Snapshots.FindingsFromStructure(structure)];
            }
            pages = structure.Document?.Pages;
        }
        return new JobOutcome(run.State, run.ResultStatus, run.ErrorCode, run.ErrorMessage, run.RemediationStatus, run.EngineVersion,
            run.BaselineValidationJson, run.FinalValidationJson, findings, run.Actions, run.Artifacts, snapshots, pages);
    }

    /// <summary>Only configured caps are sent; with none configured the request carries no limits at all.</summary>
    private static WorkerLimits? LimitsFrom(Limits l) =>
        l.PdfMaxBytes is null && l.PdfMaxPages is null && l.JobTimeoutSeconds is null
            ? null
            : new WorkerLimits(l.PdfMaxBytes, l.PdfMaxPages, l.JobTimeoutSeconds);
}
