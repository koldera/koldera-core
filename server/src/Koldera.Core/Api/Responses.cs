using System.Text.Json;
using System.Text.Json.Serialization;

namespace Koldera.Core;

public sealed record MachineValidationResponse(string Profile, string Status, FailedRuleResponse[] FailedRules)
{
    /// <summary>A fix copy whose only failing check is the PDF/UA identifier, withheld pending human review: neither passed nor failed.</summary>
    public const string DeclarationWithheld = "declaration_withheld";
}

public sealed record FailedRuleResponse(string RuleId, int Count);

public sealed record JobErrorResponse(string Code, string Message);

/// <summary>Wire shape of a job. Execution state, machine validation and manual review stay separate.</summary>
public sealed record JobResponse(
    Guid Id,
    string Kind,
    string? Mode,
    string State,
    DateTimeOffset CreatedAt,
    DateTimeOffset? StartedAt,
    DateTimeOffset? FinishedAt,
    MachineValidationResponse? Validation,
    MachineValidationResponse? BaselineValidation,
    MachineValidationResponse? FinalValidation,
    string? RemediationStatus,
    bool ManualReviewRequired,
    string? EngineVersion,
    JobErrorResponse? Error,
    string? TargetUrl,
    string? ContentSelector)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public static JobResponse From(CoreJob job)
    {
        var baseline = Parse(job.BaselineValidationJson);
        var final = Parse(job.FinalValidationJson);
        return new JobResponse(
            job.Id,
            job.Kind,
            job.Mode,
            job.State,
            job.CreatedAt,
            job.StartedAt,
            job.FinishedAt,
            final ?? baseline,
            baseline,
            final,
            job.RemediationStatus,
            job.ManualReviewRequired,
            job.EngineVersion,
            job.ErrorCode is null ? null : new JobErrorResponse(job.ErrorCode, job.ErrorMessage ?? ""),
            RedactUrl(job.TargetUrl),
            job.ContentSelector);
    }

    /// <summary>Query strings and fragments may carry tokens; the API never echoes them.</summary>
    private static string? RedactUrl(string? url) =>
        url is not null && Uri.TryCreate(url, UriKind.Absolute, out var u) ? u.GetLeftPart(UriPartial.Path) : null;

    private static MachineValidationResponse? Parse(string? json)
    {
        if (string.IsNullOrEmpty(json)) return null;
        var stored = JsonSerializer.Deserialize<StoredValidation>(json, Json);
        return stored is null ? null : new MachineValidationResponse(stored.Profile, stored.Status, (stored.FailedRules ?? []).Select(r => new FailedRuleResponse(r.RuleId, r.Count)).ToArray());
    }

    /// <summary>Persisted subset of the worker's validation object.</summary>
    public sealed record StoredValidation(
        [property: JsonPropertyName("profile")] string Profile,
        [property: JsonPropertyName("status")] string Status,
        [property: JsonPropertyName("failedRules")] StoredFailedRule[]? FailedRules);

    public sealed record StoredFailedRule([property: JsonPropertyName("ruleId")] string RuleId, [property: JsonPropertyName("count")] int Count);
}

public sealed record FindingResponse(string Source, string RuleId, string Message, int? PageNumber, string? Selector, bool NeedsHumanReview, string? Resolution)
{
    public static FindingResponse From(CoreFinding f) => new(f.Source, f.RuleId, f.Message, f.PageNumber, f.Selector, f.NeedsHumanReview, f.Resolution);
}

public sealed record ArtifactResponse(Guid Id, string Kind, string Sha256, long SizeBytes, string DownloadUrl, DateTimeOffset ExpiresAt);

public sealed record ActionResponse(string RuleId, string Outcome, string Reason, string? Before, string? After)
{
    public static ActionResponse From(CoreAction a) => new(a.RuleId, a.Outcome, a.Reason, a.Before, a.After);
}
