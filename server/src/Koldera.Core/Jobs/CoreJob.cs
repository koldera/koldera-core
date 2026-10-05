namespace Koldera.Core;

public static class CoreJobKind
{
    public const string Pdf = "pdf";
    public const string HtmlToPdf = "html_to_pdf";
    public const string Web = "web";
    public const string OrderPdf = "order_pdf";
    public const string OrderHtml = "order_html";
}

public static class CoreJobState
{
    public const string Queued = "queued";
    public const string Running = "running";
    public const string Completed = "completed";
    public const string Failed = "failed";
}

/// <summary>A job as persisted in job.json under the job's directory.</summary>
public sealed class CoreJob
{
    public Guid Id { get; set; }
    public string Kind { get; set; } = CoreJobKind.Pdf;
    public string? Mode { get; set; }
    public string State { get; set; } = CoreJobState.Queued;
    public string? Profile { get; set; }
    public string? ExplicitLanguage { get; set; }
    public string? ExplicitTitle { get; set; }
    public string? SourceFileName { get; set; }
    public string? ContentSelector { get; set; }
    public string? TargetUrl { get; set; }
    public Guid? InputUploadId { get; set; }
    public Guid? SourceJobId { get; set; }
    public string? RemediationTarget { get; set; }
    public string? ReviewerIdentity { get; set; }
    public string Owner { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? StartedAt { get; set; }
    public DateTimeOffset? FinishedAt { get; set; }
    public string? EngineVersion { get; set; }
    public string? ResultStatus { get; set; }
    public string? RemediationStatus { get; set; }
    public bool ManualReviewRequired { get; set; } = true;
    public string? BaselineValidationJson { get; set; }
    public string? FinalValidationJson { get; set; }
    public string? ErrorCode { get; set; }
    public string? ErrorMessage { get; set; }
    public int? Pages { get; set; }
    public List<CoreFinding> Findings { get; set; } = [];
    public List<CoreAction> Actions { get; set; } = [];
    public List<CoreArtifact> Artifacts { get; set; } = [];
    public List<CoreSnapshot> Snapshots { get; set; } = [];
}

public sealed record CoreFinding
{
    public Guid Id { get; init; }
    public string Source { get; init; } = "pdf";
    public string RuleId { get; init; } = "";
    public string? Severity { get; init; }
    public int? PageNumber { get; init; }
    public string? Selector { get; init; }
    public string? Disposition { get; init; }
    public bool MachineCheck { get; init; }
    public string Message { get; init; } = "";
    public bool NeedsHumanReview { get; init; } = true;
    /// <summary><c>fixed_in_copy</c> or <c>still_failing</c> on machine checks of an applied fix; null otherwise.</summary>
    public string? Resolution { get; init; }
    public string? EvidenceJson { get; init; }
}

public sealed record CoreAction
{
    public Guid Id { get; init; }
    public string RuleId { get; init; } = "";
    public string Outcome { get; init; } = "";
    public string Reason { get; init; } = "";
    public string? Before { get; init; }
    public string? After { get; init; }
    /// <summary>Position in the engine's own run order.</summary>
    public int Sequence { get; init; }
}

public sealed record CoreArtifact(Guid Id, string Kind, string FileName, string Sha256, long Bytes, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt);

/// <summary>The order evidence of a job: public node list plus the private mapping only the workers use.</summary>
public static class SnapshotOrigin
{
    public const string PdfStructure = "pdf_structure";
    public const string HtmlDom = "html_dom";
}

public sealed record CoreSnapshot
{
    public Guid Id { get; init; }
    public string Origin { get; init; } = SnapshotOrigin.PdfStructure;
    public int Version { get; init; } = 1;
    /// <summary>SHA-256 of the exact artifact the nodes describe.</summary>
    public string ArtifactSha256 { get; init; } = "";
    /// <summary>JSON array of public nodes (nodeId, parentId, role, logicalIndex, pageNumber, textPreview, boundingBox).</summary>
    public string NodesJson { get; init; } = "[]";
    /// <summary>JSON object nodeId -> private mapping; never served.</summary>
    public string PrivateJson { get; init; } = "{}";
    public string FocusOrderJson { get; init; } = "[]";
    public bool FocusOrderMeasured { get; init; }
    public string VisualCandidateJson { get; init; } = "[]";
    public string EngineVersion { get; init; } = "";
    public DateTimeOffset CreatedAt { get; init; }
}

/// <summary>What a reading-order correction changes: the HTML source of an HTML-to-PDF job or the structure tags of a PDF.</summary>
public static class RemediationTarget
{
    public const string SourceHtml = "source_html";
    public const string PdfTags = "pdf_tags";
}
