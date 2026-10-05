using System.Text.Json;
using System.Text.Json.Serialization;

namespace Koldera.Core.Workers;

/// <summary>Public node shape stored in <see cref="CoreSnapshot.NodesJson"/> and served by the API.</summary>
public sealed record PublicOrderNode(
    [property: JsonPropertyName("nodeId")] string NodeId,
    [property: JsonPropertyName("parentId")] string? ParentId,
    [property: JsonPropertyName("role")] string Role,
    [property: JsonPropertyName("logicalIndex")] int LogicalIndex,
    [property: JsonPropertyName("pageNumber")] int? PageNumber,
    [property: JsonPropertyName("origin")] string Origin,
    [property: JsonPropertyName("textPreview")] string? TextPreview,
    [property: JsonPropertyName("boundingBox")] StructureBox? BoundingBox);

/// <summary>Builds snapshots from worker results and runs the probes; shared by every executor that produces order evidence.</summary>
public static class Snapshots
{
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { DefaultIgnoreCondition = JsonIgnoreCondition.Never };

    public static CoreSnapshot FromStructure(StructureResult result, DateTimeOffset now)
    {
        var nodes = result.Nodes.Select(n => new PublicOrderNode(n.NodeId, n.ParentId, n.Role, n.LogicalIndex, n.PageNumber, SnapshotOrigin.PdfStructure, n.TextPreview, n.BoundingBox)).ToList();
        var privateMap = result.Nodes.ToDictionary(n => n.NodeId, n => n.Private);
        return new CoreSnapshot
        {
            Id = Guid.NewGuid(),
            Origin = SnapshotOrigin.PdfStructure,
            Version = 1,
            ArtifactSha256 = result.Sha256 ?? "",
            NodesJson = JsonSerializer.Serialize(nodes, Json),
            PrivateJson = JsonSerializer.Serialize(privateMap, Json),
            FocusOrderJson = JsonSerializer.Serialize(result.FocusOrderNodeIds, Json),
            FocusOrderMeasured = result.FocusOrderMeasured,
            VisualCandidateJson = "[]",
            EngineVersion = Truncate(result.EngineVersion),
            CreatedAt = now,
        };
    }

    public static CoreSnapshot FromSemantic(SemanticResult result, DateTimeOffset now)
    {
        var nodes = result.Nodes.Select(n => new PublicOrderNode(n.NodeId, n.ParentId, n.Role, n.LogicalIndex, null, SnapshotOrigin.HtmlDom, n.TextPreview, n.BoundingBox)).ToList();
        var privateMap = result.Nodes.ToDictionary(n => n.NodeId, n => n.Private);
        return new CoreSnapshot
        {
            Id = Guid.NewGuid(),
            Origin = SnapshotOrigin.HtmlDom,
            Version = 1,
            ArtifactSha256 = result.SourceSha256 ?? "",
            NodesJson = JsonSerializer.Serialize(nodes, Json),
            PrivateJson = JsonSerializer.Serialize(privateMap, Json),
            FocusOrderJson = JsonSerializer.Serialize(result.FocusOrderNodeIds, Json),
            FocusOrderMeasured = result.FocusOrderMeasured,
            VisualCandidateJson = JsonSerializer.Serialize(result.VisualOrderCandidateNodeIds, Json),
            EngineVersion = Truncate(result.EngineVersion),
            CreatedAt = now,
        };
    }

    /// <summary>Structure findings become <c>pdf</c> findings with the three-way category; nothing here is a WCAG failure by itself.</summary>
    public static IEnumerable<CoreFinding> FindingsFromStructure(StructureResult result) =>
        result.Findings.Select(f => new CoreFinding
        {
            Id = Guid.NewGuid(),
            Source = "pdf",
            RuleId = f.Code,
            Severity = "info",
            Disposition = "manual_review",
            MachineCheck = true,
            Message = f.Message,
            NeedsHumanReview = true,
            EvidenceJson = JsonSerializer.Serialize(new { nodeIds = f.NodeIds }, Json),
        });

    public static IEnumerable<CoreFinding> FindingsFromSemantic(SemanticResult result) =>
        result.Findings.Select(f => new CoreFinding
        {
            Id = Guid.NewGuid(),
            Source = "html",
            RuleId = f.Code,
            Severity = f.Category == "suspected_semantic_mismatch" ? "suspected" : "info",
            Disposition = f.Category,
            MachineCheck = true,
            Message = f.Message,
            NeedsHumanReview = true,
            EvidenceJson = JsonSerializer.Serialize(new { nodeIds = f.NodeIds, evidence = f.Evidence }, Json),
        });

    /// <summary>Runs the PDF engine's <c>structure</c> verb on a local PDF; a probe that cannot run is a contract error, never a silent gap.</summary>
    public static async Task<StructureResult> ProbeStructureAsync(IWorkerProcess probe, string scratch, string pdfPath, TimeSpan timeout, CancellationToken ct)
    {
        var resultPath = Path.Combine(scratch, "structure.json");
        var run = await probe.RunAsync(scratch, pdfPath, resultPath, timeout, ct);
        if (run.TimedOut) throw new TimeoutException("structure probe exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"structure probe exited with code {run.ExitCode} without writing a result");
        return OrderWorkerContracts.ParseStructure(await File.ReadAllTextAsync(resultPath, ct));
    }

    /// <summary>
    /// Runs a probe that only adds evidence. A tool that cannot start or writes no result gives null (and a warning naming the tool only):
    /// the job goes on without that evidence. A timeout and a result that breaks its contract still fail the job.
    /// </summary>
    internal static async Task<bool> RunOptionalAsync(IWorkerProcess probe, string tool, ILogger? log, string scratch, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct)
    {
        WorkerRun run;
        try
        {
            run = await probe.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
        {
            log?.LogWarning("optional {Tool} could not be started: {ExceptionType}", tool, e.GetType().Name);
            return false;
        }
        if (run.TimedOut) throw new TimeoutException(tool + " exceeded its time budget");
        if (File.Exists(resultPath)) return true;
        log?.LogWarning("optional {Tool} exited with code {ExitCode} without a result", tool, run.ExitCode);
        return false;
    }

    /// <summary>Like <see cref="ProbeStructureAsync"/> but null when the probe is missing or writes nothing.</summary>
    public static async Task<StructureResult?> TryProbeStructureAsync(IWorkerProcess probe, ILogger? log, string scratch, string pdfPath, TimeSpan timeout, CancellationToken ct)
    {
        var resultPath = Path.Combine(scratch, "structure.json");
        return await RunOptionalAsync(probe, "structure probe", log, scratch, pdfPath, resultPath, timeout, ct)
            ? OrderWorkerContracts.ParseStructure(await File.ReadAllTextAsync(resultPath, ct))
            : null;
    }

    /// <summary>Like <see cref="ProbeSemanticAsync"/> but null when the collector is missing or writes nothing.</summary>
    public static async Task<SemanticResult?> TryProbeSemanticAsync(IWorkerProcess probe, ILogger? log, string scratch, Guid jobId, SemanticLimits? limits, TimeSpan timeout, CancellationToken ct)
    {
        var requestPath = Path.Combine(scratch, "semantic-request.json");
        var resultPath = Path.Combine(scratch, "semantic-result.json");
        await File.WriteAllTextAsync(requestPath, OrderWorkerContracts.SerializeSemanticRequest(new SemanticRequest(OrderWorkerContracts.SpecVersion, jobId, SemanticRequest.CanonicalInputPath, limits)), ct);
        return await RunOptionalAsync(probe, "semantic collector", log, scratch, requestPath, resultPath, timeout, ct)
            ? OrderWorkerContracts.ParseSemantic(await File.ReadAllTextAsync(resultPath, ct), jobId)
            : null;
    }

    /// <summary>Runs the renderer's <c>semantic</c> verb on <c>&lt;scratch&gt;/in/source.html</c>.</summary>
    public static async Task<SemanticResult> ProbeSemanticAsync(IWorkerProcess probe, string scratch, Guid jobId, SemanticLimits? limits, TimeSpan timeout, CancellationToken ct)
    {
        var requestPath = Path.Combine(scratch, "semantic-request.json");
        var resultPath = Path.Combine(scratch, "semantic-result.json");
        await File.WriteAllTextAsync(requestPath, OrderWorkerContracts.SerializeSemanticRequest(new SemanticRequest(OrderWorkerContracts.SpecVersion, jobId, SemanticRequest.CanonicalInputPath, limits)), ct);
        var run = await probe.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        if (run.TimedOut) throw new TimeoutException("semantic collector exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"semantic collector exited with code {run.ExitCode} without writing a result");
        return OrderWorkerContracts.ParseSemantic(await File.ReadAllTextAsync(resultPath, ct), jobId);
    }

    private static string Truncate(string value) => value.Length <= 200 ? value : value[..200];
}
