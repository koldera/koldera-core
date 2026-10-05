using System.Text.Json;
using System.Text.Json.Serialization;

namespace Koldera.Core.Workers;

// Wire types of contracts/html-renderer-{request,result}.schema.json and
// contracts/pdf-inspect-result.schema.json. Both processes are separate and their output is
// untrusted input: every document is validated against the schema before use.

public sealed record HtmlRendererRequest(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("inputPath")] string InputPath,
    [property: JsonPropertyName("outputDirectory")] string OutputDirectory,
    [property: JsonPropertyName("limits")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] HtmlRendererLimits? Limits,
    [property: JsonPropertyName("page")] HtmlRendererPage Page,
    [property: JsonPropertyName("content")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] HtmlRendererContent? Content = null,
    [property: JsonPropertyName("policy")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] ChromiumPolicy? Policy = null)
{
    public const string CanonicalInputPath = "/work/in/source.html";
    public const string CanonicalOutputDirectory = "/work/out";
}

/// <summary>Content selection of an HTML job: only the outermost elements matching the CSS selector are printed.</summary>
public sealed record HtmlRendererContent([property: JsonPropertyName("selector")] string Selector)
{
    public static HtmlRendererContent? For(string? selector) => string.IsNullOrWhiteSpace(selector) ? null : new HtmlRendererContent(selector);
}

public sealed record HtmlRendererLimits(
    [property: JsonPropertyName("timeoutSeconds")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? TimeoutSeconds,
    [property: JsonPropertyName("maxBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxBytes,
    [property: JsonPropertyName("maxPages")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? MaxPages,
    [property: JsonPropertyName("maxInlineImageBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxInlineImageBytes,
    [property: JsonPropertyName("maxTotalInlineImageBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxTotalInlineImageBytes);

public sealed record HtmlRendererPage(
    [property: JsonPropertyName("format")] string Format,
    [property: JsonPropertyName("printBackground")] bool PrintBackground);

public sealed record HtmlRendererDiagnostic(
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("message")] string Message,
    [property: JsonPropertyName("blocking")] bool Blocking,
    [property: JsonPropertyName("line")] int? Line,
    [property: JsonPropertyName("element")] string? Element);

public sealed record HtmlRendererResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("sourceSha256")] string? SourceSha256,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("taggedRequested")] bool TaggedRequested,
    [property: JsonPropertyName("pages")] int? Pages,
    [property: JsonPropertyName("blockedRequests")] int BlockedRequests,
    [property: JsonPropertyName("diagnostics")] List<HtmlRendererDiagnostic> Diagnostics,
    [property: JsonPropertyName("artifacts")] List<WorkerArtifact> Artifacts,
    [property: JsonPropertyName("manualReviewRequired")] bool ManualReviewRequired,
    [property: JsonPropertyName("error")] WorkerError? Error);

public sealed record InspectRole([property: JsonPropertyName("role")] string Role, [property: JsonPropertyName("count")] int Count);

public sealed record InspectStructure(
    [property: JsonPropertyName("pages")] int Pages,
    [property: JsonPropertyName("encrypted")] bool Encrypted,
    [property: JsonPropertyName("markInfoMarked")] bool MarkInfoMarked,
    [property: JsonPropertyName("structTreeRoot")] bool StructTreeRoot,
    [property: JsonPropertyName("structureElements")] int StructureElements,
    [property: JsonPropertyName("contentReferences")] int ContentReferences,
    [property: JsonPropertyName("usableTags")] bool UsableTags,
    [property: JsonPropertyName("roles")] List<InspectRole> Roles,
    [property: JsonPropertyName("language")] string? Language,
    [property: JsonPropertyName("title")] string? Title);

public sealed record InspectResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("sha256")] string? Sha256,
    [property: JsonPropertyName("bytes")] long Bytes,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("structure")] InspectStructure? Structure,
    [property: JsonPropertyName("error")] WorkerError? Error);

public static class HtmlWorkerContracts
{
    public const string SpecVersion = "1.0";

    private static readonly JsonSerializerOptions Json = new() { DefaultIgnoreCondition = JsonIgnoreCondition.Never };
    private static readonly ContractSchema RequestSchema = SchemaResources.Load("html-renderer-request.schema.json");
    private static readonly ContractSchema ResultSchema = SchemaResources.Load("html-renderer-result.schema.json");
    private static readonly ContractSchema InspectSchema = SchemaResources.Load("pdf-inspect-result.schema.json");

    public static string SerializeRequest(HtmlRendererRequest request)
    {
        var json = JsonSerializer.Serialize(request, Json);
        var errors = SchemaResources.Validate(RequestSchema, json);
        return errors.Count == 0 ? json : throw new InvalidOperationException("html renderer request violates schema: " + string.Join("; ", errors));
    }

    public static HtmlRendererResult ParseRendererResult(string json, Guid expectedJobId)
    {
        var errors = SchemaResources.Validate(ResultSchema, json);
        if (errors.Count > 0)
        {
            throw SchemaResources.Violation(ResultSchema, json, "html renderer result");
        }
        var result = JsonSerializer.Deserialize<HtmlRendererResult>(json, Json) ?? throw new WorkerContractException("html renderer result is empty");
        if (result.SpecVersion != SpecVersion)
        {
            throw new WorkerContractException("html renderer result specVersion " + result.SpecVersion + " is not supported");
        }
        if (result.JobId != expectedJobId)
        {
            throw new WorkerContractException("html renderer result jobId does not match the dispatched job");
        }
        if (!result.ManualReviewRequired || !result.TaggedRequested)
        {
            throw new WorkerContractException("html renderer result must keep manualReviewRequired=true and taggedRequested=true");
        }
        return result;
    }

    public static InspectResult ParseInspectResult(string json)
    {
        var errors = SchemaResources.Validate(InspectSchema, json);
        if (errors.Count > 0)
        {
            throw SchemaResources.Violation(InspectSchema, json, "inspect result");
        }
        var result = JsonSerializer.Deserialize<InspectResult>(json, Json) ?? throw new WorkerContractException("inspect result is empty");
        if (result.SpecVersion != SpecVersion)
        {
            throw new WorkerContractException("inspect result specVersion " + result.SpecVersion + " is not supported");
        }
        return result;
    }
}

/// <summary>
/// The request's <c>policy</c> block for workers that start Chromium. Sent only when the server was configured with
/// KOLDERA_CHROMIUM_SANDBOX=true (workers run with a cleared environment, so the server passes the choice in the request);
/// absent means false, Playwright's default.
/// </summary>
public sealed record ChromiumPolicy([property: JsonPropertyName("chromiumSandbox")] bool ChromiumSandbox)
{
    public static ChromiumPolicy? From(CoreOptions options) => options.ChromiumSandbox ? new ChromiumPolicy(true) : null;
}
