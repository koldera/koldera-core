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
}
