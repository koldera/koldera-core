using System.Text;
using System.Text.Json;
using Koldera.Core.Workers;

namespace Koldera.Core.Cli;

/// <summary>An artifact copied to the output directory.</summary>
internal sealed record WrittenArtifact(CoreArtifact Artifact, string Path);

/// <summary>What the command line prints and which exit code a finished job maps to.</summary>
internal static class Summary
{
    public const int Ok = 0;
    public const int FailedRules = 2;
    public const int JobFailed = 3;
    public const int Usage = 64;

    private const int ListedFindings = 10;
    private static readonly JsonSerializerOptions Web = new(JsonSerializerDefaults.Web);

    /// <summary>
    /// 0 when the machine verdict is "passed" or "declaration_withheld" (only the PDF/UA identifier is missing, withheld pending
    /// human review); 2 when it is "failed" or rules failed (a document whose generated PDF fails veraPDF counts as 2);
    /// 3 for a failed job and for a completed one without a verdict (not_run, error, missing).
    /// </summary>
    public static int ExitCode(CoreJob job)
    {
        if (job.State != CoreJobState.Completed)
            return job.ErrorCode == DocumentExecutor.ValidationFailed ? FailedRules : JobFailed;
        var v = JobResponse.From(job).Validation;
        if (v is null) return JobFailed;
        if (v.Status == MachineValidationResponse.DeclarationWithheld) return Ok;
        if (v.Status == "failed" || v.FailedRules.Length > 0) return FailedRules;
        return v.Status == "passed" ? Ok : JobFailed;
    }

    /// <summary>The <c>--json</c> document: the API shapes, with a local path instead of a download URL on each artifact.</summary>
    public static string Json(CoreJob job, IReadOnlyList<WrittenArtifact> written) =>
        JsonSerializer.Serialize(new
        {
            job = JobResponse.From(job),
            findings = job.Findings.Select(FindingResponse.From),
            actions = job.Actions.OrderBy(a => a.Sequence).Select(ActionResponse.From),
            artifacts = written.Select(w => new { id = w.Artifact.Id, kind = w.Artifact.Kind, sha256 = w.Artifact.Sha256, sizeBytes = w.Artifact.Bytes, path = w.Path }),
        }, Web);

    public static string Text(CoreJob job, IReadOnlyList<WrittenArtifact> written)
    {
        var r = JobResponse.From(job);
        var sb = new StringBuilder();
        sb.AppendLine($"Job {r.Id}: {r.State}" + (r.TargetUrl is null ? "" : $" ({r.TargetUrl})"));
        if (r.Error is { } e) sb.AppendLine($"Error: {e.Code}: {e.Message}");

        var label = Label(r.Validation?.Profile ?? job.Profile);
        if (r.Validation is { } v)
        {
            sb.AppendLine($"{label} machine check: {Describe(v)}");
            if (v.Status == MachineValidationResponse.DeclarationWithheld)
                sb.AppendLine("  The PDF/UA identifier (pdfuaid) was not written: declaring PDF/UA requires a human review. This is not a pass.");
            // A fix has a before and an after; show the starting point when it differs.
            if (r.BaselineValidation is { } b && r.FinalValidation is not null && Describe(b) != Describe(v))
                sb.AppendLine($"  before the fix: {Describe(b)}");
        }
        else
        {
            sb.AppendLine($"{label} machine check: not run");
        }
        if (job.RemediationStatus is { } s && s != "not_requested")
            sb.AppendLine($"Remediation: {s} ({job.Actions.Count(a => a.Outcome == "applied")} of {job.Actions.Count} steps applied)");

        var review = job.Findings.Where(f => f.NeedsHumanReview).ToList();
        sb.AppendLine($"Findings: {job.Findings.Count} ({review.Count} need human review)");
        foreach (var f in review.Take(ListedFindings))
            sb.AppendLine($"  - {f.RuleId}: {f.Message}" + (f.PageNumber is { } p ? $" (page {p})" : ""));
        if (review.Count > ListedFindings) sb.AppendLine($"  ... and {review.Count - ListedFindings} more (use --json for all)");

        foreach (var w in written) sb.AppendLine($"Wrote {w.Artifact.Kind}: {w.Path}");
        sb.AppendLine("Automatic checks only; a person still has to review the result.");
        return sb.ToString();
    }

    private static string Label(string? profile) => profile switch
    {
        "ua1" => "PDF/UA-1",
        WebJobExecutor.Profile => "WCAG 2.2 AA automated",
        null => "PDF/UA-1",
        _ => profile,
    };

    private static string Describe(MachineValidationResponse v)
    {
        if (v.FailedRules.Length == 0) return v.Status;
        var n = v.FailedRules.Length;
        var rules = string.Join(", ", v.FailedRules.Take(5).Select(f => $"{f.RuleId} x{f.Count}")) + (n > 5 ? ", ..." : "");
        return $"{v.Status} ({n} {(n == 1 ? "rule" : "rules")} failed: {rules})";
    }
}
