using System.Text.Json;

namespace Koldera.Core.Workers;

/// <summary>The scanner process (Node, axe-core in Chromium).</summary>
public sealed record WebWorkers(IWorkerProcess Process);

/// <summary>
/// Runs the scanner for a <c>web</c> job. The target was syntax-checked at acceptance and is checked
/// again by the scanner with live DNS and the egress proxy. Findings are automated axe results
/// (incomplete means human review); a scan that finished is <c>completed</c> however many violations it lists.
/// Nothing here logs or echoes the target.
/// </summary>
public sealed class WebJobExecutor(DataStore store, WebWorkers web, CoreOptions options, TimeProvider clock) : IJobExecutor
{
    public const string Profile = "wcag22aa_automated";

    public string Kind => CoreJobKind.Web;

    public async Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(job.TargetUrl)) throw new WorkerContractException("web job has no target URL");

        var l = options.Limits;
        var limits = l.JobTimeoutSeconds is null && l.WebMaxResponseBytes is null && l.WebMaxRequests is null
            ? null
            : new WebWorkerLimits(l.JobTimeoutSeconds, l.WebMaxResponseBytes, l.WebMaxRequests);
        var request = new WebWorkerRequest(WebWorkerContracts.SpecVersion, job.Id, job.TargetUrl, WebWorkerRequest.CanonicalOutputDirectory, limits,
            new WebWorkerPolicy(options.WebAllowHttp, string.IsNullOrWhiteSpace(options.WebProxy) ? null : options.WebProxy, options.ChromiumSandbox ? true : null));
        var requestPath = Path.Combine(scratch, "request.json");
        var resultPath = Path.Combine(scratch, "result.json");
        await File.WriteAllTextAsync(requestPath, WebWorkerContracts.SerializeRequest(request), ct);

        var run = await web.Process.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        if (run.TimedOut) throw new TimeoutException("scanner exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"scanner exited with code {run.ExitCode} without writing a result");
        var result = WebWorkerContracts.ParseResult(await WorkerOutput.ReadResultAsync(resultPath, ct), job.Id);

        var now = clock.GetUtcNow();
        var retention = now.AddHours(options.RetentionHours);
        var outDir = Path.Combine(store.JobDir(job.Id), "out");
        var kept = new List<CoreArtifact>();
        foreach (var artifact in result.Artifacts)
        {
            var name = ArtifactFiles.RequireOutputName(artifact.Path, WebWorkerRequest.CanonicalOutputDirectory);
            kept.Add(await ArtifactFiles.VerifyAndKeepAsync(Path.Combine(scratch, "out", name), artifact.Kind, artifact.Sha256, artifact.Bytes, outDir, now, retention, ct));
        }

        var state = result.Status == "completed" ? CoreJobState.Completed : CoreJobState.Failed;
        var errorCode = result.Error?.Code ?? (result.Status == "unsupported" ? "UNSUPPORTED_INPUT" : null);
        var errorMessage = result.Error?.Message ?? (result.Status == "unsupported" ? "the URL could not be scanned" : null);
        var findings = result.Findings.Select(f => new CoreFinding
        {
            Id = Guid.NewGuid(),
            Source = "web",
            RuleId = f.RuleId,
            Severity = f.Impact,
            Selector = f.Selector,
            Disposition = f.Kind,
            MachineCheck = f.Kind == "violation",
            Message = f.Message,
            NeedsHumanReview = f.NeedsHumanReview,
            EvidenceJson = JsonSerializer.Serialize(new { snippet = f.Snippet, wcagTags = f.WcagTags, helpUrl = f.HelpUrl }),
        }).ToList();
        findings.Add(new CoreFinding
        {
            Id = Guid.NewGuid(),
            Source = "web",
            RuleId = "MANUAL_REVIEW_REQUIRED",
            MachineCheck = false,
            Message = "automated checks cover a subset of WCAG; keyboard operability, meaningful sequence, content and context require human review; absence of violations is not conformance",
            NeedsHumanReview = true,
        });
        var validation = state == CoreJobState.Completed
            ? JsonSerializer.Serialize(new { profile = Profile, status = result.Summary.Violations == 0 ? "passed" : "failed", failedRules = result.Findings.Where(f => f.Kind == "violation").GroupBy(f => f.RuleId).Select(g => new { ruleId = g.Key, count = g.Count() }) })
            : JsonSerializer.Serialize(new { profile = Profile, status = "error", failedRules = Array.Empty<object>() });
        return new JobOutcome(state, result.Status, errorCode, errorMessage, "not_requested", result.EngineVersion, validation, null, findings, [], kept);
    }
}
