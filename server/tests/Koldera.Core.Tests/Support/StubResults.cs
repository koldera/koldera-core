using System.Text.Json;
using System.Text.Json.Nodes;

namespace Koldera.Core.Tests.Support;

/// <summary>Worker results that satisfy the contract schemas, for <see cref="StubWorker"/>.</summary>
public static class StubResults
{
    private static JsonObject Validation(string status, params (string Rule, int Count)[] failed) => new()
    {
        ["profile"] = "ua1",
        ["status"] = status,
        ["failedRules"] = new JsonArray(failed.Select(f => (JsonNode)new JsonObject { ["ruleId"] = f.Rule, ["count"] = f.Count }).ToArray()),
    };

    private static string Result(string request, string status, string remediation, JsonObject? final, JsonArray actions, JsonArray artifacts, JsonObject? error = null) =>
        new JsonObject
        {
            ["specVersion"] = "1.0",
            ["jobId"] = JsonNode.Parse(request)!["jobId"]!.GetValue<string>(),
            ["status"] = status,
            ["originalSha256"] = Fixtures.Sha256(Fixtures.SmallPdf()),
            ["engineVersion"] = "stub-engine",
            ["baseline"] = Validation("failed", ("7.1-3", 2)),
            ["finalValidation"] = final,
            ["remediationStatus"] = remediation,
            ["actions"] = actions,
            ["findings"] = new JsonArray(new JsonObject { ["ruleId"] = "7.1-3", ["message"] = "Document title is missing.", ["pageNumber"] = 1, ["needsHumanReview"] = false }),
            ["artifacts"] = artifacts,
            ["manualReviewRequired"] = true,
            ["error"] = error,
        }.ToJsonString();

    public static StubWorker PdfAnalyzeCompleted() =>
        new(request => Result(request, "completed", "not_requested", null, [], []));

    /// <summary>A fix that wrote a "remediated.pdf" with these bytes.</summary>
    public static StubWorker PdfFixApplied(byte[]? remediated = null)
    {
        var bytes = remediated ?? Fixtures.OtherPdf();
        var artifact = new JsonObject { ["kind"] = "remediated", ["path"] = "/work/out/remediated.pdf", ["sha256"] = Fixtures.Sha256(bytes), ["bytes"] = bytes.Length };
        var action = new JsonObject { ["ruleId"] = "PDF_TITLE_V1", ["outcome"] = "applied", ["reason"] = "title set", ["before"] = null, ["after"] = "Small" };
        return new StubWorker(
            request => Result(request, "completed", "applied", Validation("passed"), new JsonArray(action), new JsonArray(artifact.DeepClone())),
            new Dictionary<string, byte[]> { ["remediated.pdf"] = bytes });
    }

    /// <summary>The worker reports <c>unsupported</c> for the input.</summary>
    public static StubWorker PdfUnsupported() =>
        new(request => Result(request, "unsupported", "skipped", null, [], [], new JsonObject { ["code"] = "UNSUPPORTED_ENCRYPTED", ["message"] = "encrypted" }));

    /// <summary>A structure probe result for the document with this hash.</summary>
    public static StubWorker StructureInspected(string sha256, int pages = 1)
    {
        var json = new JsonObject
        {
            ["specVersion"] = "1.0",
            ["status"] = "inspected",
            ["sha256"] = sha256,
            ["bytes"] = 100,
            ["engineVersion"] = "stub-engine",
            ["document"] = new JsonObject
            {
                ["pages"] = pages, ["usableTags"] = true, ["signed"] = false, ["encrypted"] = false, ["hasAcroForm"] = false, ["annotations"] = 0,
                ["pageDigests"] = new JsonArray(),
            },
            ["nodes"] = new JsonArray(),
            ["focusOrderNodeIds"] = new JsonArray(),
            ["focusOrderMeasured"] = false,
            ["findings"] = new JsonArray(new JsonObject { ["code"] = "ORDER_NOTE", ["message"] = "check the order", ["nodeIds"] = new JsonArray() }),
            ["error"] = null,
        }.ToJsonString();
        return StubWorker.Fixed(json);
    }

    /// <summary>The renderer's result for a request; <paramref name="artifacts"/> are declared as is.</summary>
    private static string RenderResult(string request, string status, JsonArray diagnostics, JsonArray artifacts, JsonObject? error) =>
        new JsonObject
        {
            ["specVersion"] = "1.0",
            ["jobId"] = JsonNode.Parse(request)!["jobId"]!.GetValue<string>(),
            ["status"] = status,
            ["sourceSha256"] = null,
            ["engineVersion"] = "stub-renderer",
            ["taggedRequested"] = true,
            ["pages"] = status == "completed" ? 1 : null,
            ["blockedRequests"] = 0,
            ["diagnostics"] = diagnostics,
            ["artifacts"] = artifacts,
            ["manualReviewRequired"] = true,
            ["error"] = error,
        }.ToJsonString();

    private static JsonObject Diagnostic(string code, bool blocking) => new()
    {
        ["code"] = code, ["message"] = "advisory from the renderer", ["blocking"] = blocking, ["line"] = 1, ["element"] = "html",
    };

    /// <summary>A render that wrote "generated.pdf" (the small PDF, so the PDF stub's original hash matches) with one advisory diagnostic.</summary>
    public static StubWorker HtmlRenderCompleted()
    {
        var bytes = Fixtures.SmallPdf();
        var artifact = new JsonObject { ["kind"] = "generated_pdf", ["path"] = "/work/out/generated.pdf", ["sha256"] = Fixtures.Sha256(bytes), ["bytes"] = bytes.Length };
        return new StubWorker(
            request => RenderResult(request, "completed", new JsonArray(Diagnostic("HTML_LANG_MISSING", false)), new JsonArray(artifact), null),
            new Dictionary<string, byte[]> { ["generated.pdf"] = bytes });
    }

    /// <summary>The renderer refuses the source with a blocking diagnostic and the given error code.</summary>
    public static StubWorker HtmlRenderUnsupported(string errorCode = "UNSUPPORTED_CONTENT") =>
        new(request => RenderResult(request, "unsupported", new JsonArray(Diagnostic("HTML_NOT_A_DOCUMENT", true)), [],
            new JsonObject { ["code"] = errorCode, ["message"] = "cannot be rendered" }));

    /// <summary>The inspect probe for the generated PDF: with usable tags, or without any.</summary>
    public static StubWorker InspectResult(string sha256, bool usableTags = true) =>
        StubWorker.Fixed(new JsonObject
        {
            ["specVersion"] = "1.0",
            ["status"] = "inspected",
            ["sha256"] = sha256,
            ["bytes"] = 100,
            ["engineVersion"] = "stub-inspect",
            ["structure"] = new JsonObject
            {
                ["pages"] = 2, ["encrypted"] = false, ["markInfoMarked"] = usableTags, ["structTreeRoot"] = usableTags,
                ["structureElements"] = usableTags ? 5 : 0, ["contentReferences"] = usableTags ? 5 : 0, ["usableTags"] = usableTags,
                ["roles"] = new JsonArray(new JsonObject { ["role"] = "P", ["count"] = 3 }), ["language"] = "en", ["title"] = "Page",
            },
            ["error"] = null,
        }.ToJsonString());

    /// <summary>The PDF engine's analyze result with arbitrary changes, for the fail-closed checks.</summary>
    public static StubWorker PdfCustom(Action<JsonObject> mutate, IReadOnlyDictionary<string, byte[]>? outputs = null) =>
        new(request =>
        {
            var result = JsonNode.Parse(Result(request, "completed", "not_requested", null, [], []))!.AsObject();
            mutate(result);
            return result.ToJsonString();
        }, outputs);

    /// <summary>The renderer's semantic collector: one node and one suspected-mismatch finding for the source with this hash.</summary>
    public static StubWorker SemanticCollected(string sourceSha256, string status = "completed") =>
        new(request => new JsonObject
        {
            ["specVersion"] = "1.0",
            ["jobId"] = JsonNode.Parse(request)!["jobId"]!.GetValue<string>(),
            ["status"] = status,
            ["sourceSha256"] = sourceSha256,
            ["engineVersion"] = "stub-semantic",
            ["nodes"] = status != "completed" ? new JsonArray() : new JsonArray(new JsonObject
            {
                ["nodeId"] = "n1", ["parentId"] = null, ["role"] = "h1", ["logicalIndex"] = 0, ["textPreview"] = "Hello", ["boundingBox"] = null,
                ["private"] = new JsonObject
                {
                    ["domPath"] = "1/0", ["tag"] = "h1", ["elementChildren"] = 0, ["focusable"] = false, ["tabindex"] = null,
                    ["cssOrder"] = 0, ["inlineOrder"] = false, ["parentDisplay"] = "block", ["parentFlexReversed"] = false,
                },
            }),
            ["focusOrderNodeIds"] = new JsonArray(),
            ["focusOrderMeasured"] = false,
            ["visualOrderCandidateNodeIds"] = new JsonArray(),
            ["findings"] = status != "completed" ? new JsonArray() : new JsonArray(new JsonObject
            {
                ["code"] = "DOM_ORDER_NOTE", ["category"] = "suspected_semantic_mismatch", ["message"] = "CSS order differs from DOM order", ["nodeIds"] = new JsonArray("n1"), ["evidence"] = null,
            }),
            ["error"] = status == "completed" ? null : new JsonObject { ["code"] = "UNSUPPORTED_CONTENT", ["message"] = "not collectable" },
        }.ToJsonString());
}
