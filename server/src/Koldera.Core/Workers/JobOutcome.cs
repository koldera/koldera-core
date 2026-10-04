namespace Koldera.Core.Workers;

/// <summary>Everything a finished execution wants persisted on the job.</summary>
public sealed record JobOutcome(
    string State,
    string ResultStatus,
    string? ErrorCode,
    string? ErrorMessage,
    string RemediationStatus,
    string? EngineVersion,
    string? BaselineValidationJson,
    string? FinalValidationJson,
    IReadOnlyList<CoreFinding> Findings,
    IReadOnlyList<CoreAction> Actions,
    IReadOnlyList<CoreArtifact> Artifacts,
    IReadOnlyList<CoreSnapshot>? Snapshots = null,
    int? Pages = null);

/// <summary>Kind-specific work: prepare scratch, run the worker, verify its output, keep artifacts.</summary>
public interface IJobExecutor
{
    string Kind { get; }
    Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, CancellationToken ct);
}
