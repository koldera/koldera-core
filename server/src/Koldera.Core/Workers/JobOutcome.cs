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

    /// <param name="timeout">
    /// The configured job time limit, or <see cref="System.Threading.Timeout.InfiniteTimeSpan"/> when none is configured.
    /// Executors pass it unchanged to <see cref="IWorkerProcess.RunAsync"/> and throw <see cref="TimeoutException"/> when the worker timed out.
    /// </param>
    /// <param name="ct">Cancelled at the job deadline as well (see <see cref="JobRunner"/>), so the limit covers all steps together.</param>
    Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct);
}
