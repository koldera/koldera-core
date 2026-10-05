using System.Text.Json.Nodes;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests.Support;

/// <summary>What a worker stub was asked: the scratch root, the request (or probe input) path and the time limit it got.</summary>
public sealed record WorkerCall(string ScratchRoot, string RequestPath, TimeSpan Timeout)
{
    /// <summary>The request document (the scratch directory is gone once the job ends); empty for a probe, whose input is a file.</summary>
    public string RequestJson { get; init; } = "";
}

/// <summary>
/// A stub whose answer depends on the bytes it is given (read from the scratch layout or the probe input), so one
/// stub can serve both the source job and the correction job, which work on different files.
/// </summary>
public sealed class InputAwareWorker(Func<WorkerCall, string> result, Func<WorkerCall, IReadOnlyDictionary<string, byte[]>>? outputs = null) : IWorkerProcess
{
    public List<WorkerCall> Calls { get; } = [];

    public async Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
    {
        var call = new WorkerCall(scratchRoot, requestPath, timeout) { RequestJson = requestPath.EndsWith(".json", StringComparison.Ordinal) ? await File.ReadAllTextAsync(requestPath, ct) : "" };
        Calls.Add(call);
        if (outputs is not null)
        {
            var outDir = Path.Combine(scratchRoot, "out");
            Directory.CreateDirectory(outDir);
            foreach (var (name, bytes) in outputs(call)) await File.WriteAllBytesAsync(Path.Combine(outDir, name), bytes, ct);
        }
        await File.WriteAllTextAsync(resultPath, result(call), ct);
        return new WorkerRun(0, false, "");
    }
}

/// <summary>Blocks inside the worker until released, so a test can hold the single runner loop.</summary>
public sealed class BlockingReorder(IWorkerProcess inner) : IWorkerProcess
{
    public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public TaskCompletionSource Release { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public async Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
    {
        Started.TrySetResult();
        await Release.Task.WaitAsync(ct);
        return await inner.RunAsync(scratchRoot, requestPath, resultPath, timeout, ct);
    }
}

/// <summary>Reading-order worker stubs: structure and semantic trees, reorder verbs, and input-echoing engine/inspect probes.</summary>
public static class OrderStubs
{
    private static string Jobid(string requestJson) => JsonNode.Parse(requestJson)!["jobId"]!.GetValue<string>();

    private static string Sha(string path) => Fixtures.Sha256(File.ReadAllBytes(path));

    /// <summary>
    /// The PDF engine's <c>process</c> verb: it reports the hash of the file it was given, so it serves the source job and
    /// the correction job alike. In mode <c>fix</c> it writes <paramref name="fixedCopy"/> as the tagged copy (final validation
    /// <paramref name="fixFinalFailedRules"/>); <paramref name="mutate"/> may change a result given the hash of its input.
    /// </summary>
    public static InputAwareWorker PdfEngine(byte[]? fixedCopy = null, (string Rule, int Count)[]? fixFinalFailedRules = null, Action<JsonObject, string>? mutate = null) =>
        new(call =>
        {
            var request = File.ReadAllText(call.RequestPath);
            var input = Sha(Path.Combine(call.ScratchRoot, "in", "document.pdf"));
            var fix = fixedCopy is not null && JsonNode.Parse(request)!["mode"]!.GetValue<string>() == "fix";
            JsonArray artifacts = [];
            JsonObject? final = null;
            if (fix)
            {
                artifacts.Add(new JsonObject { ["kind"] = "remediated", ["path"] = "/work/out/remediated.pdf", ["sha256"] = Fixtures.Sha256(fixedCopy!), ["bytes"] = fixedCopy!.Length });
                final = new JsonObject
                {
                    ["profile"] = "ua1", ["status"] = (fixFinalFailedRules ?? []).Length == 0 ? "passed" : "failed",
                    ["failedRules"] = new JsonArray((fixFinalFailedRules ?? []).Select(r => (JsonNode)new JsonObject { ["ruleId"] = r.Rule, ["count"] = r.Count }).ToArray()),
                };
            }
            var result = JsonNode.Parse(StubResults.Result(request, "completed", fix ? "applied" : "not_requested", final, [], artifacts, null, input))!.AsObject();
            mutate?.Invoke(result, input);
            return result.ToJsonString();
        }, call => fixedCopy is null ? new Dictionary<string, byte[]>() : new Dictionary<string, byte[]> { ["remediated.pdf"] = fixedCopy });

    /// <summary>A structure tree (Document with P kids) for the file it probes; <paramref name="paragraphsOf"/> gives the texts in structure order.</summary>
    public static InputAwareWorker StructureTree(Func<byte[], string[]> paragraphsOf) =>
        new(call =>
        {
            var bytes = File.ReadAllBytes(call.RequestPath);
            var texts = paragraphsOf(bytes);
            var nodes = new JsonArray
            {
                Node("d0", null, "Document", 0, null, null, new JsonObject { ["path"] = new JsonArray(0), ["objectRef"] = null, ["kidKinds"] = "elements", ["pages"] = new JsonArray(1), ["contentRefs"] = texts.Length, ["objectRefs"] = 0, ["elementKids"] = texts.Length }),
            };
            for (var i = 0; i < texts.Length; i++)
                nodes.Add(Node("p" + i, "d0", "P", i, 1, texts[i], new JsonObject { ["path"] = new JsonArray(0, i), ["objectRef"] = null, ["kidKinds"] = "content", ["pages"] = new JsonArray(1), ["contentRefs"] = 1, ["objectRefs"] = 0, ["elementKids"] = 0 }));
            return new JsonObject
            {
                ["specVersion"] = "1.0", ["status"] = "inspected", ["sha256"] = Fixtures.Sha256(bytes), ["bytes"] = bytes.Length, ["engineVersion"] = "stub-structure",
                ["document"] = new JsonObject { ["pages"] = 1, ["usableTags"] = true, ["signed"] = false, ["encrypted"] = false, ["hasAcroForm"] = false, ["annotations"] = 0, ["pageDigests"] = new JsonArray() },
                ["nodes"] = nodes, ["focusOrderNodeIds"] = new JsonArray(), ["focusOrderMeasured"] = false, ["findings"] = new JsonArray(), ["error"] = null,
            }.ToJsonString();
        });

    /// <summary>A DOM node: the semantic contract has no page number.</summary>
    private static JsonObject DomNode(string id, string? parent, string role, int index, string? text, JsonObject priv)
    {
        var node = Node(id, parent, role, index, null, text, priv);
        node.Remove("pageNumber");
        return node;
    }

    private static JsonObject Node(string id, string? parent, string role, int index, int? page, string? text, JsonObject priv) => new()
    {
        ["nodeId"] = id, ["parentId"] = parent, ["role"] = role, ["logicalIndex"] = index, ["pageNumber"] = page, ["textPreview"] = text, ["boundingBox"] = null, ["private"] = priv,
    };

    /// <summary>The renderer's <c>semantic</c> verb on scratch/in/source.html: a main with an h1 and one p per further text, CSS-order candidates being the last two.</summary>
    public static InputAwareWorker SemanticTree(Func<byte[], string[]> textsOf) =>
        new(call =>
        {
            var bytes = File.ReadAllBytes(Path.Combine(call.ScratchRoot, "in", "source.html"));
            var texts = textsOf(bytes);
            JsonObject Priv(string path, string tag, int children) => new()
            {
                ["domPath"] = path, ["tag"] = tag, ["elementChildren"] = children, ["focusable"] = false, ["tabindex"] = null,
                ["cssOrder"] = 0, ["inlineOrder"] = false, ["parentDisplay"] = "flex", ["parentFlexReversed"] = false,
            };
            var nodes = new JsonArray { DomNode("m", null, "main", 0, null, Priv("1/0", "main", texts.Length)) };
            for (var i = 0; i < texts.Length; i++)
                nodes.Add(DomNode("c" + i, "m", i == 0 ? "h1" : "p", i, texts[i], Priv($"1/0/{i}", i == 0 ? "h1" : "p", 0)));
            return new JsonObject
            {
                ["specVersion"] = "1.0", ["jobId"] = Jobid(File.ReadAllText(call.RequestPath)), ["status"] = "completed",
                ["sourceSha256"] = Fixtures.Sha256(bytes), ["engineVersion"] = "stub-semantic", ["nodes"] = nodes,
                ["focusOrderNodeIds"] = new JsonArray(), ["focusOrderMeasured"] = false,
                ["visualOrderCandidateNodeIds"] = new JsonArray("c" + (texts.Length - 1)),
                ["findings"] = new JsonArray(), ["error"] = null,
            }.ToJsonString();
        });

    /// <summary>The <c>inspect</c> probe for the file it is given; tags are usable unless <paramref name="usableTagsOf"/> says otherwise.</summary>
    public static InputAwareWorker InspectEchoing(Func<byte[], bool>? usableTagsOf = null) =>
        new(call =>
        {
            var bytes = File.ReadAllBytes(call.RequestPath);
            var usable = usableTagsOf?.Invoke(bytes) ?? true;
            return new JsonObject
            {
                ["specVersion"] = "1.0", ["status"] = "inspected", ["sha256"] = Fixtures.Sha256(bytes), ["bytes"] = bytes.Length, ["engineVersion"] = "stub-inspect",
                ["structure"] = new JsonObject
                {
                    ["pages"] = 1, ["encrypted"] = false, ["markInfoMarked"] = usable, ["structTreeRoot"] = usable,
                    ["structureElements"] = usable ? 5 : 0, ["contentReferences"] = usable ? 5 : 0, ["usableTags"] = usable,
                    ["roles"] = new JsonArray(new JsonObject { ["role"] = "P", ["count"] = 3 }), ["language"] = "en", ["title"] = "Page",
                },
                ["error"] = null,
            }.ToJsonString();
        });

    private static JsonObject PdfChecks(bool value) => new()
    {
        ["pageCountUnchanged"] = value, ["contentStreamsUnchanged"] = value, ["textTokensUnchanged"] = value, ["annotationsUnchanged"] = value,
        ["mcidInventoryUnchanged"] = value, ["parentTreeUnchanged"] = value, ["renderIdentical"] = value, ["sequenceMatchesApproved"] = value, ["nodeInventoryUnchanged"] = value,
    };

    private static string PdfReorderResult(string request, string status, byte[]? output, JsonObject? error) => new JsonObject
    {
        ["specVersion"] = "1.0", ["jobId"] = Jobid(request), ["status"] = status, ["engineVersion"] = "stub-reorder",
        ["baseSha256"] = JsonNode.Parse(request)!["baseSha256"]!.GetValue<string>(),
        ["outputSha256"] = output is null ? null : Fixtures.Sha256(output), ["outputBytes"] = output?.Length ?? 0,
        ["checks"] = PdfChecks(output is not null), ["appliedRoles"] = output is null ? new JsonArray() : new JsonArray("P", "P", "P"), ["error"] = error,
    }.ToJsonString();

    /// <summary>The PDF <c>reorder</c> verb applied the approved order and wrote <paramref name="output"/> as reordered.pdf.</summary>
    public static StubWorker PdfReorderApplied(byte[] output) =>
        new(request => PdfReorderResult(request, "applied", output, null), new Dictionary<string, byte[]> { ["reordered.pdf"] = output });

    /// <summary>The PDF <c>reorder</c> verb applied it but declares a hash that is not the written file's.</summary>
    public static StubWorker PdfReorderLying(byte[] written, byte[] declared) =>
        new(request => PdfReorderResult(request, "applied", declared, null), new Dictionary<string, byte[]> { ["reordered.pdf"] = written });

    public static StubWorker PdfReorderRefused(string status, string code, string message) =>
        new(request => PdfReorderResult(request, status, null, new JsonObject { ["code"] = code, ["message"] = message }));

    private static string HtmlReorderResult(string request, string status, byte[]? source, byte[]? pdf, JsonObject? error) => new JsonObject
    {
        ["specVersion"] = "1.0", ["jobId"] = Jobid(request), ["status"] = status, ["engineVersion"] = "stub-html-reorder",
        ["baseSha256"] = JsonNode.Parse(request)!["baseSha256"]!.GetValue<string>(),
        ["checks"] = new JsonObject
        {
            ["domSequenceMatchesApproved"] = source is not null, ["focusOrderMeasured"] = false, ["focusSequenceFollowsDom"] = null,
            ["inlineOrderDeclarationsRemoved"] = source is null ? 0 : 3, ["visualDiffRatio"] = source is null ? null : 0.004, ["visualWithinTolerance"] = source is null ? null : true, ["pages"] = source is null ? null : 1,
        },
        ["artifacts"] = source is null || pdf is null ? new JsonArray() : new JsonArray(
            new JsonObject { ["kind"] = "source_html", ["path"] = "/work/out/source.html", ["sha256"] = Fixtures.Sha256(source), ["bytes"] = source.Length },
            new JsonObject { ["kind"] = "generated_pdf", ["path"] = "/work/out/generated.pdf", ["sha256"] = Fixtures.Sha256(pdf), ["bytes"] = pdf.Length }),
        ["diagnostics"] = new JsonArray(), ["manualReviewRequired"] = true, ["error"] = error,
    }.ToJsonString();

    /// <summary>The renderer's <c>reorder</c> verb applied the order: a corrected source and its re-rendered PDF.</summary>
    public static StubWorker HtmlReorderApplied(byte[] source, byte[] pdf) =>
        new(request => HtmlReorderResult(request, "applied", source, pdf, null), new Dictionary<string, byte[]> { ["source.html"] = source, ["generated.pdf"] = pdf });

    public static StubWorker HtmlReorderRefused(string code, string message) =>
        new(request => HtmlReorderResult(request, "unsupported", null, null, new JsonObject { ["code"] = code, ["message"] = message }));
}
