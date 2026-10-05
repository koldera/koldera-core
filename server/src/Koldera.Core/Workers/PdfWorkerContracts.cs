using System.Text.Json;
using System.Text.Json.Serialization;

namespace Koldera.Core.Workers;

// The wire types of contracts/pdf-worker-{request,result}.schema.json. Results are validated
// against the schema before any field is trusted; the Java worker is a separate process
// and its output is treated as untrusted input. A missing limit means unlimited.

public sealed record WorkerRequest(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("inputPath")] string InputPath,
    [property: JsonPropertyName("outputDirectory")] string OutputDirectory,
    [property: JsonPropertyName("mode")] string Mode,
    [property: JsonPropertyName("profile")] string Profile,
    [property: JsonPropertyName("explicitLanguage")] string? ExplicitLanguage,
    [property: JsonPropertyName("explicitTitle")] string? ExplicitTitle,
    [property: JsonPropertyName("sourceFileName")] string? SourceFileName,
    [property: JsonPropertyName("origin")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Origin,
    [property: JsonPropertyName("limits")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] WorkerLimits? Limits = null)
{
    public const string CanonicalInputPath = "/work/in/document.pdf";
    public const string CanonicalOutputDirectory = "/work/out";
}

/// <summary>Where the PDF being processed came from (schema: optional, absent = <see cref="Upload"/>).</summary>
public static class WorkerOrigin
{
    public const string Upload = "upload";
    public const string HtmlGenerated = "html_generated";
}

public sealed record WorkerLimits(
    [property: JsonPropertyName("maxBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxBytes,
    [property: JsonPropertyName("maxPages")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? MaxPages,
    [property: JsonPropertyName("timeoutSeconds")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? TimeoutSeconds);

public sealed record WorkerValidation(
    [property: JsonPropertyName("profile")] string Profile,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("failedRules")] List<WorkerFailedRule> FailedRules,
    [property: JsonPropertyName("rawReportPath")] string? RawReportPath);

public sealed record WorkerFailedRule([property: JsonPropertyName("ruleId")] string RuleId, [property: JsonPropertyName("count")] int Count);

public sealed record WorkerAction(
    [property: JsonPropertyName("ruleId")] string RuleId,
    [property: JsonPropertyName("outcome")] string Outcome,
    [property: JsonPropertyName("reason")] string Reason,
    [property: JsonPropertyName("before")] string? Before,
    [property: JsonPropertyName("after")] string? After);

public sealed record WorkerFinding(
    [property: JsonPropertyName("ruleId")] string RuleId,
    [property: JsonPropertyName("message")] string Message,
    [property: JsonPropertyName("pageNumber")] int? PageNumber,
    [property: JsonPropertyName("needsHumanReview")] bool NeedsHumanReview,
    // fixed_in_copy | still_failing on machine checks of an applied fix; null otherwise (analyze, rolled back, review items, notes).
    [property: JsonPropertyName("resolution")] string? Resolution = null);

public sealed record WorkerArtifact(
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("path")] string Path,
    [property: JsonPropertyName("sha256")] string Sha256,
    [property: JsonPropertyName("bytes")] long Bytes);

public sealed record WorkerError([property: JsonPropertyName("code")] string Code, [property: JsonPropertyName("message")] string Message);

public sealed record WorkerResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("originalSha256")] string? OriginalSha256,
    [property: JsonPropertyName("engineVersion")] string? EngineVersion,
    [property: JsonPropertyName("baseline")] WorkerValidation Baseline,
    [property: JsonPropertyName("finalValidation")] WorkerValidation? FinalValidation,
    [property: JsonPropertyName("remediationStatus")] string RemediationStatus,
    [property: JsonPropertyName("actions")] List<WorkerAction> Actions,
    [property: JsonPropertyName("findings")] List<WorkerFinding> Findings,
    [property: JsonPropertyName("artifacts")] List<WorkerArtifact> Artifacts,
    [property: JsonPropertyName("manualReviewRequired")] bool ManualReviewRequired,
    [property: JsonPropertyName("error")] WorkerError? Error);

public static class WorkerContracts
{
    public const string SpecVersion = "1.0";

    private static readonly JsonSerializerOptions Json = new() { DefaultIgnoreCondition = JsonIgnoreCondition.Never };
    private static readonly ContractSchema RequestSchema = SchemaResources.Load("pdf-worker-request.schema.json");
    private static readonly ContractSchema ResultSchema = SchemaResources.Load("pdf-worker-result.schema.json");

    public static string SerializeRequest(WorkerRequest request)
    {
        var json = JsonSerializer.Serialize(request, Json);
        var errors = SchemaResources.Validate(RequestSchema, json);
        return errors.Count == 0 ? json : throw new InvalidOperationException("worker request violates schema: " + string.Join("; ", errors));
    }

    /// <summary>Parses a result file; schema violations or an unexpected jobId are hard errors, never a pass.</summary>
    public static WorkerResult ParseResult(string json, Guid expectedJobId)
    {
        var errors = SchemaResources.Validate(ResultSchema, json);
        if (errors.Count > 0)
        {
            throw SchemaResources.Violation(ResultSchema, json, "worker result");
        }
        var result = JsonSerializer.Deserialize<WorkerResult>(json, Json) ?? throw new WorkerContractException("worker result is empty");
        if (result.SpecVersion != SpecVersion)
        {
            throw new WorkerContractException("worker result specVersion " + result.SpecVersion + " is not supported");
        }
        if (result.JobId != expectedJobId)
        {
            throw new WorkerContractException("worker result jobId does not match the dispatched job");
        }
        if (!result.ManualReviewRequired)
        {
            throw new WorkerContractException("worker result must keep manualReviewRequired=true");
        }
        return result;
    }

}

/// <summary>Contract schemas shared by every worker adapter, read from the contracts directory.</summary>
public static class SchemaResources
{
    /// <summary>KOLDERA_CONTRACTS_DIR, else the "contracts" folder the build copies next to the assembly.</summary>
    public static string ContractsDirectory =>
        Environment.GetEnvironmentVariable("KOLDERA_CONTRACTS_DIR") is { Length: > 0 } dir
            ? dir
            : Path.Combine(AppContext.BaseDirectory, "contracts");

    public static IReadOnlyList<string> Validate(ContractSchema schema, string json) => schema.Validate(json);

    /// <summary>A contract violation: the client message is redacted, the log detail also names unexpected properties.</summary>
    public static WorkerContractException Violation(ContractSchema schema, string json, string what) =>
        new(what + " violates schema: " + string.Join("; ", schema.Validate(json)),
            what + " violates schema: " + string.Join("; ", schema.Validate(json, redact: false)));

    public static ContractSchema Load(string fileName)
    {
        var path = Path.Combine(ContractsDirectory, fileName);
        if (!File.Exists(path)) throw new InvalidOperationException("missing contract schema " + path);
        return ContractSchema.FromText(File.ReadAllText(path));
    }
}
