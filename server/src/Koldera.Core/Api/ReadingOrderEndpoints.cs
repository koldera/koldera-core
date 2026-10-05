using System.Text.Json;
using Koldera.Core.Workers;

namespace Koldera.Core;

public sealed record ReadingOrderNodeResponse(string NodeId, string? ParentId, string Role, int LogicalIndex, int? PageNumber, string Origin, string? TextPreview, StructureBox? BoundingBox);

/// <summary>Wire shape of the reading-order snapshot: evidence bound to artifact hashes, never a verdict.</summary>
public sealed record ReadingOrderResponse(
    Guid JobId,
    string SourceArtifactSha256,
    int SnapshotVersion,
    IReadOnlyList<ReadingOrderNodeResponse> Nodes,
    bool ReviewRequired,
    string ReadingOrderReview,
    string FocusOrderReview,
    IReadOnlyList<string> FocusOrderNodeIds,
    IReadOnlyList<string> VisualOrderCandidateNodeIds,
    IReadOnlyDictionary<string, string> ArtifactHashes);

/// <param name="Reviewer">
/// Optional audit label (1-200 printable characters) recorded with the correction. It is asserted by the caller, not
/// authenticated: without it the record carries the principal kind ("server").
/// </param>
public sealed record ReadingOrderRemediationRequest(string BaseArtifactSha256, int SnapshotVersion, string Target, List<string> OrderedNodeIds, bool Acknowledged, string? Reviewer = null);

/// <summary>
/// Reading-order evidence and the explicit, narrow correction path. <c>GET .../reading-order</c> serves a job's snapshots (PDF
/// structure and/or HTML DOM order, each bound to the SHA-256 of its artifact); the private kid/DOM-path mapping never leaves the
/// server. <c>POST .../reading-order/remediations</c> queues an <c>order_pdf</c> or <c>order_html</c> job for one approved
/// permutation of one parent's children; the workers apply it to a copy and roll back on any regression.
/// </summary>
public static class ReadingOrderEndpoints
{
    private const string Unsupported = "ORDER_FIX_UNSUPPORTED";
    private const string Stale = "STALE_READING_ORDER_SNAPSHOT";

    public static void MapReadingOrderEndpoints(this RouteGroupBuilder v1)
    {
        v1.MapGet("/jobs/{jobId:guid}/reading-order", Get);
        v1.MapPost("/jobs/{jobId:guid}/reading-order/remediations", CreateRemediationAsync);
    }

    private static IResult Get(Guid jobId, HttpContext http, DataStore store)
    {
        var job = RequestContext.OwnedJob(http, store, jobId);
        if (job is null || job.Snapshots.Count == 0) return RequestContext.NotFound();
        return Results.Ok(Merge(jobId, job.Snapshots));
    }

    /// <summary>
    /// A job carries at most one snapshot per origin. The response lists the PDF structure order first (the artifact people
    /// receive), then the HTML DOM order; each node names its origin so a client can never confuse the two sequences. The
    /// artifact hash is the PDF's when present.
    /// </summary>
    public static ReadingOrderResponse Merge(Guid jobId, IReadOnlyList<CoreSnapshot> snapshots)
    {
        var ordered = snapshots.OrderBy(s => s.Origin == SnapshotOrigin.PdfStructure ? 0 : 1).ToList();
        var nodes = new List<ReadingOrderNodeResponse>();
        var focus = new List<string>();
        var visual = new List<string>();
        var focusMeasured = false;
        foreach (var snapshot in ordered)
        {
            var stored = JsonSerializer.Deserialize<List<PublicOrderNode>>(snapshot.NodesJson, Snapshots.Json) ?? [];
            nodes.AddRange(stored.Select(n => new ReadingOrderNodeResponse(n.NodeId, n.ParentId, n.Role, n.LogicalIndex, n.PageNumber, n.Origin, n.TextPreview, n.BoundingBox)));
            focus.AddRange(JsonSerializer.Deserialize<List<string>>(snapshot.FocusOrderJson, Snapshots.Json) ?? []);
            visual.AddRange(JsonSerializer.Deserialize<List<string>>(snapshot.VisualCandidateJson, Snapshots.Json) ?? []);
            focusMeasured |= snapshot.FocusOrderMeasured;
        }
        return new ReadingOrderResponse(
            jobId,
            ordered[0].ArtifactSha256,
            ordered.Max(s => s.Version),
            nodes,
            true,
            "needs_review",
            focusMeasured ? "needs_review" : "not_run",
            focus,
            visual,
            ordered.ToDictionary(s => s.Origin, s => s.ArtifactSha256));
    }

    private static async Task<IResult> CreateRemediationAsync(Guid jobId, ReadingOrderRemediationRequest request, HttpContext http, DataStore store, JobQueue queue, CoreOptions options, TimeProvider clock)
    {
        if (!request.Acknowledged)
            return Invalid("acknowledged must be true: the reviewer confirms the approved order was checked by a person");
        if (request.Target is not (RemediationTarget.SourceHtml or RemediationTarget.PdfTags))
            return Invalid("target must be source_html or pdf_tags");
        if (request.OrderedNodeIds is null || request.OrderedNodeIds.Count < 2 || request.OrderedNodeIds.Distinct(StringComparer.Ordinal).Count() != request.OrderedNodeIds.Count || request.OrderedNodeIds.Any(string.IsNullOrEmpty))
            return Invalid("orderedNodeIds must list at least two distinct node ids");
        if (!IsSha256(request.BaseArtifactSha256))
            return Invalid("baseArtifactSha256 must be 64 lowercase hex characters");
        var reviewer = RequestContext.Owner(http);
        if (request.Reviewer is not null)
        {
            var label = request.Reviewer.Trim();
            if (label.Length == 0 || label.EnumerateRunes().Count() > 200 || label.Any(char.IsControl))
                return Invalid("reviewer must be 1-200 printable characters or omitted");
            reviewer = label;
        }

        var source = RequestContext.OwnedJob(http, store, jobId);
        if (source is null) return RequestContext.NotFound();
        if (source.State != CoreJobState.Completed)
            return Problems.Result(409, Stale, "Job not completed", "Reading-order corrections need a completed source job.");
        if (source.Kind is not (CoreJobKind.Pdf or CoreJobKind.HtmlToPdf))
            return Problems.Result(422, Unsupported, "Order correction unsupported", "Only PDF and HTML-to-PDF jobs carry a correctable reading order.");
        var origin = request.Target == RemediationTarget.SourceHtml ? SnapshotOrigin.HtmlDom : SnapshotOrigin.PdfStructure;
        if (request.Target == RemediationTarget.SourceHtml && source.Kind != CoreJobKind.HtmlToPdf)
            return Problems.Result(422, Unsupported, "Order correction unsupported", "source_html corrections apply to HTML-to-PDF jobs only.");
        var snapshot = source.Snapshots.FirstOrDefault(s => s.Origin == origin);
        if (snapshot is null)
            return Problems.Result(422, Unsupported, "Order correction unsupported", "The job has no " + origin + " reading-order snapshot.");
        if (snapshot.ArtifactSha256 != request.BaseArtifactSha256 || snapshot.Version != request.SnapshotVersion)
            return Problems.Result(409, Stale, "Stale reading-order snapshot", "baseArtifactSha256/snapshotVersion do not match the job's current snapshot; reload the reading order and review again.");

        var now = clock.GetUtcNow();
        var baseFile = ResolveBase(source, origin, request.BaseArtifactSha256, store, options, now);
        if (baseFile.Expired)
            return Problems.Result(409, "ARTIFACT_EXPIRED", "Artifact expired", "The artifact the snapshot describes has expired.");
        if (baseFile.Path is null)
            return Problems.Result(409, Stale, "Stale reading-order snapshot", "The artifact the snapshot describes is no longer stored.");

        var resolved = ResolvePlan(snapshot, request.OrderedNodeIds);
        if (resolved.Problem is not null)
            return Problems.Result(422, Unsupported, "Order correction unsupported", resolved.Problem);

        var job = new CoreJob
        {
            Id = Guid.NewGuid(),
            Kind = request.Target == RemediationTarget.SourceHtml ? CoreJobKind.OrderHtml : CoreJobKind.OrderPdf,
            Mode = "reorder",
            State = CoreJobState.Queued,
            Profile = source.Profile,
            ExplicitLanguage = source.ExplicitLanguage,
            ExplicitTitle = source.ExplicitTitle,
            SourceFileName = source.SourceFileName,
            ContentSelector = source.ContentSelector,
            SourceJobId = jobId,
            RemediationTarget = request.Target,
            ReviewerIdentity = reviewer,
            Owner = source.Owner,
            CreatedAt = now,
        };
        var plan = new RemediationPlan(OrderWorkerContracts.SpecVersion, request.Target, jobId, request.BaseArtifactSha256, snapshot.Version, request.OrderedNodeIds, reviewer, now,
            resolved.PdfParentPath, resolved.HtmlParentPath, resolved.OrderedIndexes!);
        // The plan and a private copy of the exact base are written before the job becomes visible to the runner.
        try
        {
            Directory.CreateDirectory(RemediationPlan.Dir(store, job.Id));
            await File.WriteAllBytesAsync(RemediationPlan.PlanPath(store, job.Id), JsonSerializer.SerializeToUtf8Bytes(plan, RemediationPlan.Json), http.RequestAborted);
            File.Copy(baseFile.Path, RemediationPlan.BasePath(store, job.Id, request.Target));
        }
        catch
        {
            store.DeleteJob(job.Id);
            throw;
        }
        return await JobEndpoints.AcceptAsync(job, http, store, queue);
    }

    private sealed record BaseFile(string? Path, bool Expired);

    /// <summary>
    /// The base is the exact object the snapshot describes: the uploaded original of a PDF job, the tagged copy a fix wrote, the
    /// generated PDF of an HTML-to-PDF job, or the uploaded HTML source. Null path with Expired false means it is not stored any more.
    /// </summary>
    private static BaseFile ResolveBase(CoreJob source, string origin, string sha256, DataStore store, CoreOptions options, DateTimeOffset now)
    {
        string[] artifactKinds = origin == SnapshotOrigin.HtmlDom ? [] : source.Kind == CoreJobKind.Pdf ? ["remediated"] : ["generated_pdf"];
        var artifact = source.Artifacts.Where(a => a.Sha256 == sha256 && artifactKinds.Contains(a.Kind)).OrderByDescending(a => a.CreatedAt).FirstOrDefault();
        if (artifact is not null)
        {
            var path = Path.Combine(store.JobDir(source.Id), "out", artifact.FileName);
            return artifact.ExpiresAt <= now || !File.Exists(path) ? new BaseFile(null, true) : new BaseFile(path, false);
        }
        if ((origin == SnapshotOrigin.HtmlDom || source.Kind == CoreJobKind.Pdf) && source.InputUploadId is { } uploadId
            && store.GetUpload(uploadId) is { Completed: true } upload && string.Equals(upload.Sha256, sha256, StringComparison.OrdinalIgnoreCase))
        {
            var path = store.UploadPath(upload.Id);
            return upload.CreatedAt.AddHours(options.RetentionHours) <= now ? new BaseFile(null, true) : File.Exists(path) ? new BaseFile(path, false) : new BaseFile(null, false);
        }
        return new BaseFile(null, false);
    }

    private sealed record ResolvedPlan(string? Problem, List<int>? PdfParentPath, string? HtmlParentPath, List<int>? OrderedIndexes);

    /// <summary>
    /// The approved ids must be every child of exactly one parent in the snapshot, in a new order. Their private paths give the
    /// workers the parent and the kid/child indexes; the workers re-verify all of it against the artifact itself.
    /// </summary>
    private static ResolvedPlan ResolvePlan(CoreSnapshot snapshot, List<string> orderedNodeIds)
    {
        var nodes = JsonSerializer.Deserialize<List<PublicOrderNode>>(snapshot.NodesJson, Snapshots.Json) ?? [];
        var byId = nodes.ToDictionary(n => n.NodeId, StringComparer.Ordinal);
        var chosen = new List<PublicOrderNode>();
        foreach (var id in orderedNodeIds)
        {
            if (!byId.TryGetValue(id, out var node))
                return new ResolvedPlan("orderedNodeIds contains an id that is not in the job's " + snapshot.Origin + " snapshot", null, null, null);
            chosen.Add(node);
        }
        var parentId = chosen[0].ParentId;
        if (chosen.Any(n => n.ParentId != parentId))
            return new ResolvedPlan("orderedNodeIds must all be children of the same parent (one sibling group per correction)", null, null, null);
        var siblings = nodes.Where(n => n.ParentId == parentId).OrderBy(n => n.LogicalIndex).ToList();
        if (siblings.Count != chosen.Count)
            return new ResolvedPlan($"orderedNodeIds must be the complete sibling group ({siblings.Count} children), not a subset", null, null, null);
        if (siblings.Select(n => n.NodeId).SequenceEqual(chosen.Select(n => n.NodeId), StringComparer.Ordinal))
            return new ResolvedPlan("orderedNodeIds equals the current order; nothing to correct", null, null, null);

        if (snapshot.Origin == SnapshotOrigin.PdfStructure)
        {
            var privateMap = JsonSerializer.Deserialize<Dictionary<string, StructurePrivate>>(snapshot.PrivateJson, Snapshots.Json) ?? [];
            // A null parent is the structure tree root itself (its kids are always elements).
            List<int> parentPath = [];
            if (parentId is not null)
            {
                if (!privateMap.TryGetValue(parentId, out var parentPrivate) || parentPrivate.KidKinds != "elements")
                    return new ResolvedPlan("the parent's kids are not all structure elements; this group cannot be reordered", null, null, null);
                parentPath = parentPrivate.Path;
            }
            var indexes = new List<int>();
            foreach (var node in chosen)
            {
                if (!privateMap.TryGetValue(node.NodeId, out var p) || p.Path.Count != parentPath.Count + 1)
                    return new ResolvedPlan("node mapping is inconsistent with its parent", null, null, null);
                indexes.Add(p.Path[^1]);
            }
            return new ResolvedPlan(null, parentPath, null, indexes);
        }
        else
        {
            var privateMap = JsonSerializer.Deserialize<Dictionary<string, SemanticPrivate>>(snapshot.PrivateJson, Snapshots.Json) ?? [];
            if (parentId is null || !privateMap.TryGetValue(parentId, out var parentPrivate))
                return new ResolvedPlan("node mapping is inconsistent with its parent", null, null, null);
            var indexes = new List<int>();
            foreach (var node in chosen)
            {
                if (!privateMap.TryGetValue(node.NodeId, out var p) || !p.DomPath.StartsWith(parentPrivate.DomPath + "/", StringComparison.Ordinal))
                    return new ResolvedPlan("node mapping is inconsistent with its parent", null, null, null);
                var last = p.DomPath[(parentPrivate.DomPath.Length + 1)..];
                if (!int.TryParse(last, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var index))
                    return new ResolvedPlan("node mapping is inconsistent with its parent", null, null, null);
                indexes.Add(index);
            }
            return new ResolvedPlan(null, null, parentPrivate.DomPath, indexes);
        }
    }

    private static bool IsSha256(string? value) => value is { Length: 64 } && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static IResult Invalid(string detail) => Problems.Result(422, "VALIDATION_ERROR", "Invalid reading-order request", detail);
}
