using System.Text.Json;
using System.Text.Json.Serialization;

namespace Koldera.Core.Workers;

// Wire types of contracts/web-worker-{request,result}.schema.json, validated against the schema files. The scanner is a separate process whose output is untrusted input.

public sealed record WebWorkerRequest(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("url")] string Url,
    [property: JsonPropertyName("outputDirectory")] string OutputDirectory,
    [property: JsonPropertyName("limits")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] WebWorkerLimits? Limits,
    [property: JsonPropertyName("policy")] WebWorkerPolicy Policy)
{
    public const string CanonicalOutputDirectory = "/work/out";
}

public sealed record WebWorkerLimits(
    [property: JsonPropertyName("timeoutSeconds")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? TimeoutSeconds,
    [property: JsonPropertyName("maxResponseBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxResponseBytes,
    [property: JsonPropertyName("maxRequests")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? MaxRequests);

public sealed record WebWorkerPolicy(
    [property: JsonPropertyName("allowHttp")] bool AllowHttp,
    [property: JsonPropertyName("proxyServer")] string? ProxyServer);

public sealed record WebWorkerFinding(
    [property: JsonPropertyName("ruleId")] string RuleId,
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("impact")] string Impact,
    [property: JsonPropertyName("message")] string Message,
    [property: JsonPropertyName("selector")] string Selector,
    [property: JsonPropertyName("snippet")] string? Snippet,
    [property: JsonPropertyName("wcagTags")] List<string> WcagTags,
    [property: JsonPropertyName("helpUrl")] string? HelpUrl,
    [property: JsonPropertyName("needsHumanReview")] bool NeedsHumanReview);

public sealed record WebWorkerSummary(
    [property: JsonPropertyName("violations")] int Violations,
    [property: JsonPropertyName("incomplete")] int Incomplete,
    [property: JsonPropertyName("passes")] int Passes,
    [property: JsonPropertyName("inapplicable")] int Inapplicable,
    [property: JsonPropertyName("blockedRequests")] int BlockedRequests);

public sealed record WebWorkerResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("requestedUrl")] string RequestedUrl,
    [property: JsonPropertyName("finalUrl")] string? FinalUrl,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("summary")] WebWorkerSummary Summary,
    [property: JsonPropertyName("findings")] List<WebWorkerFinding> Findings,
    [property: JsonPropertyName("artifacts")] List<WorkerArtifact> Artifacts,
    [property: JsonPropertyName("manualReviewRequired")] bool ManualReviewRequired,
    [property: JsonPropertyName("error")] WorkerError? Error);

public static class WebWorkerContracts
{
    public const string SpecVersion = "1.0";

    private static readonly JsonSerializerOptions Json = new() { DefaultIgnoreCondition = JsonIgnoreCondition.Never };
    private static readonly ContractSchema RequestSchema = SchemaResources.Load("web-worker-request.schema.json");
    private static readonly ContractSchema ResultSchema = SchemaResources.Load("web-worker-result.schema.json");

    public static string SerializeRequest(WebWorkerRequest request)
    {
        var json = JsonSerializer.Serialize(request, Json);
        var errors = SchemaResources.Validate(RequestSchema, json);
        return errors.Count == 0 ? json : throw new InvalidOperationException("web worker request violates schema: " + string.Join("; ", errors));
    }

    public static WebWorkerResult ParseResult(string json, Guid expectedJobId)
    {
        var errors = SchemaResources.Validate(ResultSchema, json);
        if (errors.Count > 0)
        {
            throw new WorkerContractException("web worker result violates schema: " + string.Join("; ", errors));
        }
        var result = JsonSerializer.Deserialize<WebWorkerResult>(json, Json) ?? throw new WorkerContractException("web worker result is empty");
        if (result.SpecVersion != SpecVersion)
        {
            throw new WorkerContractException("web worker result specVersion " + result.SpecVersion + " is not supported");
        }
        if (result.JobId != expectedJobId)
        {
            throw new WorkerContractException("web worker result jobId does not match the dispatched job");
        }
        if (!result.ManualReviewRequired)
        {
            throw new WorkerContractException("web worker result must keep manualReviewRequired=true");
        }
        return result;
    }
}
