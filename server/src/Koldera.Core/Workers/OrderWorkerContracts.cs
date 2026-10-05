using System.Text.Json;
using System.Text.Json.Serialization;

namespace Koldera.Core.Workers;

// Wire types of the reading-order worker contracts: the Java `structure` / `reorder` verbs
// and the Node `semantic` / `reorder` verbs. Every document is validated against the
// schema before any field is trusted; the `private` blocks never leave the server.

public sealed record StructureBox([property: JsonPropertyName("x")] double X, [property: JsonPropertyName("y")] double Y, [property: JsonPropertyName("width")] double Width, [property: JsonPropertyName("height")] double Height);

public sealed record StructurePrivate(
    [property: JsonPropertyName("path")] List<int> Path,
    [property: JsonPropertyName("objectRef")] string? ObjectRef,
    [property: JsonPropertyName("kidKinds")] string KidKinds,
    [property: JsonPropertyName("pages")] List<int> Pages,
    [property: JsonPropertyName("contentRefs")] int ContentRefs,
    [property: JsonPropertyName("objectRefs")] int ObjectRefs,
    [property: JsonPropertyName("elementKids")] int ElementKids);

public sealed record StructureNodeWire(
    [property: JsonPropertyName("nodeId")] string NodeId,
    [property: JsonPropertyName("parentId")] string? ParentId,
    [property: JsonPropertyName("role")] string Role,
    [property: JsonPropertyName("logicalIndex")] int LogicalIndex,
    [property: JsonPropertyName("pageNumber")] int? PageNumber,
    [property: JsonPropertyName("textPreview")] string? TextPreview,
    [property: JsonPropertyName("boundingBox")] StructureBox? BoundingBox,
    [property: JsonPropertyName("private")] StructurePrivate Private);

public sealed record StructurePageDigest(
    [property: JsonPropertyName("page")] int Page,
    [property: JsonPropertyName("contentStreamSha256")] string ContentStreamSha256,
    [property: JsonPropertyName("textTokenMultisetSha256")] string TextTokenMultisetSha256,
    [property: JsonPropertyName("annotationCount")] int AnnotationCount,
    [property: JsonPropertyName("mcidCount")] int McidCount);

public sealed record StructureDocument(
    [property: JsonPropertyName("pages")] int Pages,
    [property: JsonPropertyName("usableTags")] bool UsableTags,
    [property: JsonPropertyName("signed")] bool Signed,
    [property: JsonPropertyName("encrypted")] bool Encrypted,
    [property: JsonPropertyName("hasAcroForm")] bool HasAcroForm,
    [property: JsonPropertyName("annotations")] int Annotations,
    [property: JsonPropertyName("pageDigests")] List<StructurePageDigest> PageDigests);

public sealed record StructureFinding([property: JsonPropertyName("code")] string Code, [property: JsonPropertyName("message")] string Message, [property: JsonPropertyName("nodeIds")] List<string> NodeIds);

public sealed record StructureResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("sha256")] string? Sha256,
    [property: JsonPropertyName("bytes")] long Bytes,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("document")] StructureDocument? Document,
    [property: JsonPropertyName("nodes")] List<StructureNodeWire> Nodes,
    [property: JsonPropertyName("focusOrderNodeIds")] List<string> FocusOrderNodeIds,
    [property: JsonPropertyName("focusOrderMeasured")] bool FocusOrderMeasured,
    [property: JsonPropertyName("findings")] List<StructureFinding> Findings,
    [property: JsonPropertyName("error")] WorkerError? Error);

public sealed record PdfReorderLimits([property: JsonPropertyName("maxBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxBytes, [property: JsonPropertyName("maxPages")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? MaxPages, [property: JsonPropertyName("timeoutSeconds")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? TimeoutSeconds);

public sealed record PdfReorderRequest(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("inputPath")] string InputPath,
    [property: JsonPropertyName("outputPath")] string OutputPath,
    [property: JsonPropertyName("baseSha256")] string BaseSha256,
    [property: JsonPropertyName("parentPath")] List<int> ParentPath,
    [property: JsonPropertyName("orderedKidIndexes")] List<int> OrderedKidIndexes,
    [property: JsonPropertyName("limits")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] PdfReorderLimits? Limits)
{
    public const string CanonicalInputPath = "/work/in/document.pdf";
    public const string CanonicalOutputPath = "/work/out/reordered.pdf";
}

public sealed record PdfReorderChecks(
    [property: JsonPropertyName("pageCountUnchanged")] bool? PageCountUnchanged,
    [property: JsonPropertyName("contentStreamsUnchanged")] bool? ContentStreamsUnchanged,
    [property: JsonPropertyName("textTokensUnchanged")] bool? TextTokensUnchanged,
    [property: JsonPropertyName("annotationsUnchanged")] bool? AnnotationsUnchanged,
    [property: JsonPropertyName("mcidInventoryUnchanged")] bool? McidInventoryUnchanged,
    [property: JsonPropertyName("parentTreeUnchanged")] bool? ParentTreeUnchanged,
    [property: JsonPropertyName("renderIdentical")] bool? RenderIdentical,
    [property: JsonPropertyName("sequenceMatchesApproved")] bool? SequenceMatchesApproved,
    [property: JsonPropertyName("nodeInventoryUnchanged")] bool? NodeInventoryUnchanged);

public sealed record PdfReorderResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("baseSha256")] string? BaseSha256,
    [property: JsonPropertyName("outputSha256")] string? OutputSha256,
    [property: JsonPropertyName("outputBytes")] long OutputBytes,
    [property: JsonPropertyName("checks")] PdfReorderChecks Checks,
    [property: JsonPropertyName("appliedRoles")] List<string> AppliedRoles,
    [property: JsonPropertyName("error")] WorkerError? Error);

public sealed record SemanticPrivate(
    [property: JsonPropertyName("domPath")] string DomPath,
    [property: JsonPropertyName("tag")] string Tag,
    [property: JsonPropertyName("elementChildren")] int ElementChildren,
    [property: JsonPropertyName("focusable")] bool Focusable,
    [property: JsonPropertyName("tabindex")] int? Tabindex,
    [property: JsonPropertyName("cssOrder")] int CssOrder,
    [property: JsonPropertyName("inlineOrder")] bool InlineOrder,
    [property: JsonPropertyName("parentDisplay")] string ParentDisplay,
    [property: JsonPropertyName("parentFlexReversed")] bool ParentFlexReversed);

public sealed record SemanticNodeWire(
    [property: JsonPropertyName("nodeId")] string NodeId,
    [property: JsonPropertyName("parentId")] string? ParentId,
    [property: JsonPropertyName("role")] string Role,
    [property: JsonPropertyName("logicalIndex")] int LogicalIndex,
    [property: JsonPropertyName("textPreview")] string? TextPreview,
    [property: JsonPropertyName("boundingBox")] StructureBox? BoundingBox,
    [property: JsonPropertyName("private")] SemanticPrivate Private);

public sealed record SemanticFinding(
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("category")] string Category,
    [property: JsonPropertyName("message")] string Message,
    [property: JsonPropertyName("nodeIds")] List<string> NodeIds,
    [property: JsonPropertyName("evidence")] string? Evidence);

public sealed record SemanticLimits(
    [property: JsonPropertyName("timeoutSeconds")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? TimeoutSeconds,
    [property: JsonPropertyName("maxBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxBytes,
    [property: JsonPropertyName("maxNodes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? MaxNodes,
    [property: JsonPropertyName("maxInlineImageBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxInlineImageBytes,
    [property: JsonPropertyName("maxTotalInlineImageBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxTotalInlineImageBytes);

public sealed record SemanticRequest(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("inputPath")] string InputPath,
    [property: JsonPropertyName("limits")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] SemanticLimits? Limits,
    [property: JsonPropertyName("policy")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] ChromiumPolicy? Policy = null)
{
    public const string CanonicalInputPath = "/work/in/source.html";
}

public sealed record SemanticResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("sourceSha256")] string? SourceSha256,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("nodes")] List<SemanticNodeWire> Nodes,
    [property: JsonPropertyName("focusOrderNodeIds")] List<string> FocusOrderNodeIds,
    [property: JsonPropertyName("focusOrderMeasured")] bool FocusOrderMeasured,
    [property: JsonPropertyName("visualOrderCandidateNodeIds")] List<string> VisualOrderCandidateNodeIds,
    [property: JsonPropertyName("findings")] List<SemanticFinding> Findings,
    [property: JsonPropertyName("error")] WorkerError? Error);

public sealed record HtmlReorderLimits(
    [property: JsonPropertyName("timeoutSeconds")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? TimeoutSeconds,
    [property: JsonPropertyName("maxBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxBytes,
    [property: JsonPropertyName("maxPages")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? MaxPages,
    [property: JsonPropertyName("maxInlineImageBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxInlineImageBytes,
    [property: JsonPropertyName("maxTotalInlineImageBytes")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? MaxTotalInlineImageBytes,
    [property: JsonPropertyName("maxVisualDiffRatio")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] double? MaxVisualDiffRatio);

public sealed record HtmlReorderRequest(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("inputPath")] string InputPath,
    [property: JsonPropertyName("outputDirectory")] string OutputDirectory,
    [property: JsonPropertyName("baseSha256")] string BaseSha256,
    [property: JsonPropertyName("parentPath")] string ParentPath,
    [property: JsonPropertyName("orderedChildIndexes")] List<int> OrderedChildIndexes,
    [property: JsonPropertyName("limits")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] HtmlReorderLimits? Limits,
    [property: JsonPropertyName("page")] HtmlRendererPage Page,
    [property: JsonPropertyName("content")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] HtmlRendererContent? Content = null,
    [property: JsonPropertyName("policy")] [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] ChromiumPolicy? Policy = null);

public sealed record HtmlReorderChecks(
    [property: JsonPropertyName("domSequenceMatchesApproved")] bool? DomSequenceMatchesApproved,
    [property: JsonPropertyName("focusOrderMeasured")] bool? FocusOrderMeasured,
    [property: JsonPropertyName("focusSequenceFollowsDom")] bool? FocusSequenceFollowsDom,
    [property: JsonPropertyName("inlineOrderDeclarationsRemoved")] int InlineOrderDeclarationsRemoved,
    [property: JsonPropertyName("visualDiffRatio")] double? VisualDiffRatio,
    [property: JsonPropertyName("visualWithinTolerance")] bool? VisualWithinTolerance,
    [property: JsonPropertyName("pages")] int? Pages);

public sealed record HtmlReorderResult(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("jobId")] Guid JobId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("engineVersion")] string EngineVersion,
    [property: JsonPropertyName("baseSha256")] string? BaseSha256,
    [property: JsonPropertyName("checks")] HtmlReorderChecks Checks,
    [property: JsonPropertyName("artifacts")] List<WorkerArtifact> Artifacts,
    [property: JsonPropertyName("diagnostics")] List<HtmlRendererDiagnostic> Diagnostics,
    [property: JsonPropertyName("manualReviewRequired")] bool ManualReviewRequired,
    [property: JsonPropertyName("error")] WorkerError? Error);

public static class OrderWorkerContracts
{
    public const string SpecVersion = "1.0";

    private static readonly JsonSerializerOptions Json = new() { DefaultIgnoreCondition = JsonIgnoreCondition.Never };
    private static readonly ContractSchema Structure = SchemaResources.Load("pdf-structure-result.schema.json");
    private static readonly ContractSchema PdfReorderRequestSchema = SchemaResources.Load("pdf-reorder-request.schema.json");
    private static readonly ContractSchema PdfReorderResultSchema = SchemaResources.Load("pdf-reorder-result.schema.json");
    private static readonly ContractSchema SemanticRequestSchema = SchemaResources.Load("html-semantic-request.schema.json");
    private static readonly ContractSchema SemanticResultSchema = SchemaResources.Load("html-semantic-result.schema.json");
    private static readonly ContractSchema HtmlReorderRequestSchema = SchemaResources.Load("html-reorder-request.schema.json");
    private static readonly ContractSchema HtmlReorderResultSchema = SchemaResources.Load("html-reorder-result.schema.json");

    public static StructureResult ParseStructure(string json) => Parse<StructureResult>(Structure, json, "structure result");

    public static string SerializePdfReorderRequest(PdfReorderRequest request) => Serialize(PdfReorderRequestSchema, request, "pdf reorder request");

    public static PdfReorderResult ParsePdfReorder(string json, Guid expectedJobId)
    {
        var result = Parse<PdfReorderResult>(PdfReorderResultSchema, json, "pdf reorder result");
        if (result.JobId != expectedJobId)
        {
            throw new WorkerContractException("pdf reorder result jobId does not match the dispatched job");
        }
        return result;
    }

    public static string SerializeSemanticRequest(SemanticRequest request) => Serialize(SemanticRequestSchema, request, "semantic request");

    public static SemanticResult ParseSemantic(string json, Guid expectedJobId)
    {
        var result = Parse<SemanticResult>(SemanticResultSchema, json, "semantic result");
        if (result.JobId != expectedJobId)
        {
            throw new WorkerContractException("semantic result jobId does not match the dispatched job");
        }
        return result;
    }

    public static string SerializeHtmlReorderRequest(HtmlReorderRequest request) => Serialize(HtmlReorderRequestSchema, request, "html reorder request");

    public static HtmlReorderResult ParseHtmlReorder(string json, Guid expectedJobId)
    {
        var result = Parse<HtmlReorderResult>(HtmlReorderResultSchema, json, "html reorder result");
        if (result.JobId != expectedJobId)
        {
            throw new WorkerContractException("html reorder result jobId does not match the dispatched job");
        }
        if (!result.ManualReviewRequired)
        {
            throw new WorkerContractException("html reorder result must keep manualReviewRequired=true");
        }
        return result;
    }

    private static string Serialize<T>(ContractSchema schema, T value, string what)
    {
        var json = JsonSerializer.Serialize(value, Json);
        var errors = SchemaResources.Validate(schema, json);
        return errors.Count == 0 ? json : throw new InvalidOperationException(what + " violates schema: " + string.Join("; ", errors));
    }

    private static T Parse<T>(ContractSchema schema, string json, string what)
    {
        var errors = SchemaResources.Validate(schema, json);
        if (errors.Count > 0)
        {
            throw SchemaResources.Violation(schema, json, what);
        }
        var result = JsonSerializer.Deserialize<T>(json, Json) ?? throw new WorkerContractException(what + " is empty");
        var spec = typeof(T).GetProperty("SpecVersion")?.GetValue(result) as string;
        if (spec != SpecVersion)
        {
            throw new WorkerContractException(what + " specVersion " + spec + " is not supported");
        }
        return result;
    }
}
