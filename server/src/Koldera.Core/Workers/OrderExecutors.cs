using System.Text.Json;
using System.Text.Json.Serialization;

namespace Koldera.Core.Workers;

/// <summary>The two <c>reorder</c> verbs: the PDF engine's (structure kids) and the renderer's (DOM siblings).</summary>
public sealed record OrderWorkers(IWorkerProcess PdfReorder, IWorkerProcess HtmlReorder);

/// <summary>
/// The approved permutation as recorded at acceptance: what the reviewer approved in opaque node ids, plus the resolved
/// kid/DOM path the workers need. It and a private copy of the base live beside the job record (never in <c>out</c>,
/// so never served); the copy makes the base independent of retention of the source job. The reviewer is the caller's
/// label or the principal kind, never a name the server looked up.
/// </summary>
public sealed record RemediationPlan(
    [property: JsonPropertyName("specVersion")] string SpecVersion,
    [property: JsonPropertyName("target")] string Target,
    [property: JsonPropertyName("sourceJobId")] Guid SourceJobId,
    [property: JsonPropertyName("baseArtifactSha256")] string BaseArtifactSha256,
    [property: JsonPropertyName("snapshotVersion")] int SnapshotVersion,
    [property: JsonPropertyName("orderedNodeIds")] List<string> OrderedNodeIds,
    [property: JsonPropertyName("reviewer")] string Reviewer,
    [property: JsonPropertyName("approvedAt")] DateTimeOffset ApprovedAt,
    [property: JsonPropertyName("pdfParentPath")] List<int>? PdfParentPath,
    [property: JsonPropertyName("htmlParentPath")] string? HtmlParentPath,
    [property: JsonPropertyName("orderedIndexes")] List<int> OrderedIndexes)
{
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { DefaultIgnoreCondition = JsonIgnoreCondition.Never };

    public static string Dir(DataStore store, Guid jobId) => Path.Combine(store.JobDir(jobId), "remediation");

    public static string PlanPath(DataStore store, Guid jobId) => Path.Combine(Dir(store, jobId), "plan.json");

    public static string BasePath(DataStore store, Guid jobId, string target) =>
        Path.Combine(Dir(store, jobId), target == RemediationTarget.SourceHtml ? "base.html" : "base.pdf");

    public static async Task<RemediationPlan> LoadAsync(DataStore store, Guid jobId, CancellationToken ct)
    {
        var path = PlanPath(store, jobId);
        if (!File.Exists(path)) throw new WorkerContractException("remediation job without a plan");
        await using var stream = File.OpenRead(path);
        return await JsonSerializer.DeserializeAsync<RemediationPlan>(stream, Json, ct) ?? throw new WorkerContractException("remediation plan is empty");
    }
}

internal static class OrderOutcomes
{
    public const string OrderFixRegression = "ORDER_FIX_REGRESSION";

    /// <summary>The worker refused or rolled back: nothing is published; only the worker's own rollback code is reported as a rollback.</summary>
    public static JobOutcome Refused(string status, string? code, string? message, string rolledBackCode, string? engineVersion)
    {
        var errorCode = code ?? "ORDER_FIX_UNSUPPORTED";
        return new JobOutcome(CoreJobState.Failed, status, errorCode, message ?? "the approved order could not be applied", errorCode == rolledBackCode ? "rolled_back" : "not_requested", engineVersion, null, null, [], [], []);
    }

    public static void DeleteQuietly(IEnumerable<string> paths)
    {
        foreach (var path in paths)
        {
            try
            {
                File.Delete(path);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // best effort; the job directory is removed by retention
            }
        }
    }

    /// <summary>The correction job's engine run: analyze mode, no caller-supplied language, title or file name.</summary>
    public static CoreJob EngineJob(CoreJob job) => new() { Id = job.Id, Mode = "analyze", Profile = job.Profile ?? "ua1" };

    public static CoreFinding Applied(string source, string ruleId, string message, RemediationPlan plan, object checks) => new()
    {
        Id = Guid.NewGuid(),
        Source = source,
        RuleId = ruleId,
        Severity = "info",
        MachineCheck = true,
        Message = message,
        NeedsHumanReview = true,
        EvidenceJson = JsonSerializer.Serialize(new { checks, approvedNodeIds = plan.OrderedNodeIds, reviewer = plan.Reviewer }, RemediationPlan.Json),
    };
}

/// <summary>
/// <c>order_pdf</c> (target <c>pdf_tags</c>): the private copy of the base (the source job's exact artifact, re-hashed) goes through
/// the PDF engine's <c>reorder</c> verb, which applies the approved sibling permutation to a copy and refuses or rolls back on any
/// invariant; an applied copy is snapshotted (<c>structure</c>) and validated by the unchanged PDF worker, and published as
/// <c>reordered</c> only if veraPDF reports no rule that the base did not already fail. Never touches the original.
/// </summary>
public sealed class OrderPdfExecutor(DataStore store, PdfWorkers pdf, OrderWorkers order, CoreOptions options, TimeProvider clock) : IJobExecutor
{
    public const string OrderApplied = "ORDER_FIX_APPLIED";

    public string Kind => CoreJobKind.OrderPdf;

    public async Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct)
    {
        var plan = await RemediationPlan.LoadAsync(store, job.Id, ct);
        if (plan.Target != RemediationTarget.PdfTags || plan.PdfParentPath is null)
            throw new WorkerContractException("remediation plan is not a pdf_tags plan");
        var probe = pdf.StructureProbe ?? throw new InvalidOperationException("the structure probe is not configured");

        var inputPath = Path.Combine(scratch, "in", "document.pdf");
        File.Copy(RemediationPlan.BasePath(store, job.Id, plan.Target), inputPath, overwrite: true);
        if (await PdfWorkerRun.Sha256Async(inputPath, ct) != plan.BaseArtifactSha256)
            throw new WorkerContractException("base artifact does not match the approved plan");

        var l = options.Limits;
        var limits = l.PdfMaxBytes is null && l.PdfMaxPages is null && l.JobTimeoutSeconds is null ? null : new PdfReorderLimits(l.PdfMaxBytes, l.PdfMaxPages, l.JobTimeoutSeconds);
        var request = new PdfReorderRequest(OrderWorkerContracts.SpecVersion, job.Id, PdfReorderRequest.CanonicalInputPath, PdfReorderRequest.CanonicalOutputPath,
            plan.BaseArtifactSha256, plan.PdfParentPath, plan.OrderedIndexes, limits);
        var requestPath = Path.Combine(scratch, "reorder-request.json");
        var resultPath = Path.Combine(scratch, "reorder-result.json");
        await File.WriteAllTextAsync(requestPath, OrderWorkerContracts.SerializePdfReorderRequest(request), ct);
        var run = await order.PdfReorder.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        if (run.TimedOut) throw new TimeoutException("reorder exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"reorder exited with code {run.ExitCode} without writing a result");
        var result = OrderWorkerContracts.ParsePdfReorder(await File.ReadAllTextAsync(resultPath, ct), job.Id);
        if (result.Status != "applied")
            return OrderOutcomes.Refused(result.Status, result.Error?.Code, result.Error?.Message, "INTEGRITY_REGRESSION", result.EngineVersion);
        if (result.OutputSha256 is null) throw new WorkerContractException("applied reorder without an output hash");

        var now = clock.GetUtcNow();
        var retention = now.AddHours(options.RetentionHours);
        var outDir = Path.Combine(store.JobDir(job.Id), "out");
        var reordered = await ArtifactFiles.VerifyAndKeepAsync(Path.Combine(scratch, "out", "reordered.pdf"), "reordered", result.OutputSha256, result.OutputBytes, outDir, now, retention, ct);
        var reorderedPath = Path.Combine(outDir, reordered.FileName);
        var kept = new List<string> { reorderedPath };
        try
        {
            // Snapshot and validate the copy exactly as any other PDF.
            var structure = await Snapshots.ProbeStructureAsync(probe, scratch, reorderedPath, timeout, ct);
            if (structure.Status != "inspected" || structure.Sha256 != result.OutputSha256)
                throw new WorkerContractException("the reordered copy could not be snapshotted");
            var workerScratch = Path.Combine(scratch, "pdf");
            Directory.CreateDirectory(Path.Combine(workerScratch, "in"));
            Directory.CreateDirectory(Path.Combine(workerScratch, "out"));
            var validation = await PdfWorkerRun.ExecuteAsync(pdf.Process, OrderOutcomes.EngineJob(job), reorderedPath, reordered.Sha256, reordered.Bytes, WorkerOrigin.Upload,
                PdfJobExecutor.LimitsFrom(options.Limits), workerScratch, outDir, now, retention, timeout, ct);
            kept.AddRange(validation.Artifacts.Select(a => Path.Combine(outDir, a.FileName)));

            // "No worse than the base" is measured against the validation of the artifact the correction started from: the source
            // job's baseline for its uploaded original (or its generated PDF), its final validation for the tagged copy a fix wrote
            // (that copy is what its snapshot describes, see PdfJobExecutor).
            var source = job.SourceJobId is { } sourceId ? store.GetJob(sourceId) : null;
            var baseIsTheSourcesCopy = source is not null && source.Artifacts.Any(a => a.Kind == "remediated" && a.Sha256 == plan.BaseArtifactSha256);
            var reference = baseIsTheSourcesCopy ? source!.FinalValidationJson : source?.BaselineValidationJson;
            var regression = validation.State == CoreJobState.Completed
                ? Regression.Describe(reference, validation.BaselineValidationJson)
                : "the validator did not complete on the corrected copy";
            var engine = string.IsNullOrEmpty(validation.EngineVersion) ? result.EngineVersion : validation.EngineVersion;
            if (regression is not null)
            {
                OrderOutcomes.DeleteQuietly(kept);
                return new JobOutcome(CoreJobState.Failed, "failed", OrderOutcomes.OrderFixRegression,
                    "the reordered copy validates worse than the source (" + regression + "); the correction was rolled back and nothing was published",
                    "rolled_back", engine, validation.BaselineValidationJson, null, [], [], []);
            }

            var findings = new List<CoreFinding>(validation.Findings)
            {
                OrderOutcomes.Applied("pdf", OrderApplied,
                    $"the approved structure order was applied to a copy: kids of the parent now read {string.Join(", ", result.AppliedRoles)}; page count, content streams, text tokens, annotations, MCIDs, ParentTree and rendering verified unchanged; the copy still needs a human reading-order review",
                    plan, result.Checks),
            };
            findings.AddRange(Snapshots.FindingsFromStructure(structure));
            return new JobOutcome(CoreJobState.Completed, "completed", null, null, "applied", engine, reference, validation.BaselineValidationJson,
                findings, [], [reordered, .. validation.Artifacts], [Snapshots.FromStructure(structure, now)], structure.Document?.Pages);
        }
        catch
        {
            OrderOutcomes.DeleteQuietly(kept);
            throw;
        }
    }
}

/// <summary>
/// <c>order_html</c> (target <c>source_html</c>): the private copy of the base source (re-hashed) goes through the renderer's
/// <c>reorder</c> verb (DOM permutation on a copy, re-render, DOM/focus/visual gates; the visual gate keeps its 2 % default).
/// The corrected copy is snapshotted (<c>semantic</c>) and its re-rendered PDF is inspected, validated and snapshotted like any
/// HTML conversion; any new veraPDF failure compared with the source job rolls the whole correction back.
/// </summary>
public sealed class OrderHtmlExecutor(DataStore store, HtmlWorkers html, PdfWorkers pdf, OrderWorkers order, CoreOptions options, TimeProvider clock) : IJobExecutor
{
    public const string OrderApplied = "SOURCE_ORDER_FIX_APPLIED";

    /// <summary>Correctness gate: fraction of content pixels allowed to differ between the original and the corrected render.</summary>
    public const double MaxVisualDiffRatio = 0.02;

    public string Kind => CoreJobKind.OrderHtml;

    public async Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct)
    {
        var plan = await RemediationPlan.LoadAsync(store, job.Id, ct);
        if (plan.Target != RemediationTarget.SourceHtml || plan.HtmlParentPath is null)
            throw new WorkerContractException("remediation plan is not a source_html plan");

        var inputPath = Path.Combine(scratch, "in", "source.html");
        File.Copy(RemediationPlan.BasePath(store, job.Id, plan.Target), inputPath, overwrite: true);
        if (await PdfWorkerRun.Sha256Async(inputPath, ct) != plan.BaseArtifactSha256)
            throw new WorkerContractException("base artifact does not match the approved plan");

        var l = options.Limits;
        var request = new HtmlReorderRequest(OrderWorkerContracts.SpecVersion, job.Id, "/work/in/source.html", "/work/out", plan.BaseArtifactSha256, plan.HtmlParentPath, plan.OrderedIndexes,
            new HtmlReorderLimits(null, l.HtmlMaxBytes, l.PdfMaxPages, null, null, MaxVisualDiffRatio),
            new HtmlRendererPage("A4", true), HtmlRendererContent.For(job.ContentSelector), ChromiumPolicy.From(options));
        var requestPath = Path.Combine(scratch, "reorder-request.json");
        var resultPath = Path.Combine(scratch, "reorder-result.json");
        await File.WriteAllTextAsync(requestPath, OrderWorkerContracts.SerializeHtmlReorderRequest(request), ct);
        var run = await order.HtmlReorder.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        if (run.TimedOut) throw new TimeoutException("reorder exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"reorder exited with code {run.ExitCode} without writing a result");
        var result = OrderWorkerContracts.ParseHtmlReorder(await File.ReadAllTextAsync(resultPath, ct), job.Id);
        if (result.Status != "applied")
        {
            if (result.Artifacts.Count > 0) throw new WorkerContractException("reorder published artifacts for a non-applied result");
            return OrderOutcomes.Refused(result.Status, result.Error?.Code, result.Error?.Message, OrderOutcomes.OrderFixRegression, result.EngineVersion);
        }

        var now = clock.GetUtcNow();
        var retention = now.AddHours(options.RetentionHours);
        var outDir = Path.Combine(store.JobDir(job.Id), "out");
        var kept = new List<CoreArtifact>();
        try
        {
            foreach (var artifact in result.Artifacts)
            {
                var name = ArtifactFiles.RequireOutputName(artifact.Path, "/work/out");
                kept.Add(await ArtifactFiles.VerifyAndKeepAsync(Path.Combine(scratch, "out", name), artifact.Kind, artifact.Sha256, artifact.Bytes, outDir, now, retention, ct));
            }
            return await ValidateAsync(job, plan, result, kept, scratch, outDir, now, retention, timeout, ct);
        }
        catch
        {
            OrderOutcomes.DeleteQuietly(kept.Select(a => Path.Combine(outDir, a.FileName)));
            throw;
        }
    }

    private async Task<JobOutcome> ValidateAsync(CoreJob job, RemediationPlan plan, HtmlReorderResult result, List<CoreArtifact> kept, string scratch, string outDir, DateTimeOffset now, DateTimeOffset retention, TimeSpan timeout, CancellationToken ct)
    {
        var corrected = kept.SingleOrDefault(a => a.Kind == "source_html") ?? throw new WorkerContractException("applied reorder without a corrected source_html");
        var generated = kept.SingleOrDefault(a => a.Kind == "generated_pdf") ?? throw new WorkerContractException("applied reorder without a generated_pdf");
        var generatedPath = Path.Combine(outDir, generated.FileName);
        var maxBytes = options.Limits.HtmlMaxBytes;

        // Reading-order evidence of the corrected source: DOM order, focus order, suspected mismatches.
        var snapshots = new List<CoreSnapshot>();
        var findings = new List<CoreFinding>
        {
            OrderOutcomes.Applied("html", OrderApplied,
                $"the approved sibling order was applied to a copy of the source; {result.Checks.InlineOrderDeclarationsRemoved} inline order declaration(s) removed, DOM sequence verified, keyboard focus {(result.Checks.FocusOrderMeasured == true ? "measured and following the document order" : "not applicable (no focusable content)")}, {(result.Checks.VisualDiffRatio ?? 0) * 100:0.##} % of content pixels differ (tolerance {MaxVisualDiffRatio * 100:0.##} %); the corrected copy still needs a human reading-order review",
                plan, result.Checks),
        };
        if (html.Semantic is not null)
        {
            var probeScratch = Path.Combine(scratch, "semantic");
            Directory.CreateDirectory(Path.Combine(probeScratch, "in"));
            File.Copy(Path.Combine(outDir, corrected.FileName), Path.Combine(probeScratch, "in", "source.html"));
            var semantic = await Snapshots.ProbeSemanticAsync(html.Semantic, probeScratch, job.Id, maxBytes is null ? null : new SemanticLimits(null, maxBytes, null, null, null), timeout, ct, ChromiumPolicy.From(options));
            if (semantic.Status == "completed")
            {
                if (semantic.SourceSha256 != corrected.Sha256)
                    throw new WorkerContractException("semantic collector reported a different corrected-source SHA-256");
                snapshots.Add(Snapshots.FromSemantic(semantic, clock.GetUtcNow()));
                findings.AddRange(Snapshots.FindingsFromSemantic(semantic));
            }
        }

        // Independent structure probe on the exact bytes the renderer produced.
        var inspectPath = Path.Combine(scratch, "inspect.json");
        var probe = await html.Inspect.RunAsync(scratch, generatedPath, inspectPath, timeout, ct);
        if (probe.TimedOut) throw new TimeoutException("structure probe exceeded its time budget");
        if (!File.Exists(inspectPath))
            throw new InvalidOperationException($"structure probe exited with code {probe.ExitCode} without writing a result");
        var inspect = HtmlWorkerContracts.ParseInspectResult(await File.ReadAllTextAsync(inspectPath, ct));
        if (inspect.Sha256 is not null && inspect.Sha256 != generated.Sha256)
            throw new WorkerContractException("structure probe reported a different SHA-256");
        var engineVersion = HtmlToPdfExecutor.Truncate(string.IsNullOrEmpty(result.EngineVersion) ? inspect.EngineVersion : result.EngineVersion + "; " + inspect.EngineVersion);

        var structure = inspect.Structure;
        if (inspect.Status != "inspected" || structure is null || !structure.UsableTags)
        {
            var reason = inspect.Error?.Message ?? HtmlToPdfExecutor.DescribeMissingTags(structure);
            findings.Add(new CoreFinding
            {
                Id = Guid.NewGuid(),
                Source = "pdf",
                RuleId = HtmlToPdfExecutor.TaggedOutputUnavailable,
                Severity = "blocking",
                MachineCheck = true,
                Message = "the renderer was asked for a tagged PDF but the output carries no usable structure tags (" + reason + "); the generated file is kept for review and is not treated as accessible",
                NeedsHumanReview = true,
            });
            return new JobOutcome(CoreJobState.Failed, "unsupported", HtmlToPdfExecutor.TaggedOutputUnavailable, "the generated PDF has no usable structure tags: " + reason, "not_requested", engineVersion, null, null, findings, [], kept);
        }
        if (options.Limits.PdfMaxPages is { } maxPages && structure.Pages > maxPages)
            return new JobOutcome(CoreJobState.Failed, "failed", "LIMIT_EXCEEDED", $"the generated PDF has {structure.Pages} pages, above the limit of {maxPages}", "not_requested", engineVersion, null, null, findings, [], kept, snapshots);

        // The PDF engine on the re-rendered file, in its own scratch layout.
        var pdfScratch = Path.Combine(scratch, "pdf");
        Directory.CreateDirectory(Path.Combine(pdfScratch, "in"));
        Directory.CreateDirectory(Path.Combine(pdfScratch, "out"));
        var run = await PdfWorkerRun.ExecuteAsync(pdf.Process, OrderOutcomes.EngineJob(job), generatedPath, generated.Sha256, generated.Bytes, WorkerOrigin.HtmlGenerated,
            PdfJobExecutor.LimitsFrom(options.Limits), pdfScratch, outDir, now, retention, timeout, ct);
        var runFiles = run.Artifacts.Select(a => Path.Combine(outDir, a.FileName)).ToList();
        try
        {
            findings.AddRange(run.Findings);
            findings.Add(new CoreFinding
            {
                Id = Guid.NewGuid(),
                Source = "pdf",
                RuleId = HtmlToPdfExecutor.StructureTagsPresent,
                Severity = "info",
                MachineCheck = true,
                Message = $"the generated PDF carries a structure tree ({structure.StructureElements} elements, {structure.ContentReferences} content references; roles {HtmlToPdfExecutor.DescribeRoles(structure)}); whether the tags are correct, complete and in a meaningful reading order requires human review",
                NeedsHumanReview = true,
            });
            var finalVersion = string.IsNullOrEmpty(run.EngineVersion) || run.EngineVersion == inspect.EngineVersion ? engineVersion : engineVersion + "; " + run.EngineVersion;

            // Reading-order evidence for the re-rendered PDF (the exact bytes validated above).
            if (pdf.StructureProbe is not null && run.State == CoreJobState.Completed)
            {
                var snapshot = await Snapshots.ProbeStructureAsync(pdf.StructureProbe, scratch, generatedPath, timeout, ct);
                if (snapshot.Status == "inspected")
                {
                    if (snapshot.Sha256 != generated.Sha256)
                        throw new WorkerContractException("structure snapshot reported a different SHA-256");
                    snapshots.Add(Snapshots.FromStructure(snapshot, clock.GetUtcNow()));
                    findings.AddRange(Snapshots.FindingsFromStructure(snapshot));
                }
            }

            // No new veraPDF failures compared with the source job, else roll back.
            if (run.State == CoreJobState.Completed)
            {
                var source = job.SourceJobId is { } sourceId ? store.GetJob(sourceId) : null;
                var regression = Regression.Describe(source?.BaselineValidationJson, run.BaselineValidationJson);
                if (regression is not null)
                {
                    OrderOutcomes.DeleteQuietly(kept.Select(a => Path.Combine(outDir, a.FileName)).Concat(runFiles));
                    return new JobOutcome(CoreJobState.Failed, "failed", OrderOutcomes.OrderFixRegression,
                        "the corrected copy validates worse than the source (" + regression + "); the correction was rolled back and nothing was published",
                        "rolled_back", HtmlToPdfExecutor.Truncate(finalVersion), run.BaselineValidationJson, null, findings.Where(f => f.RuleId != HtmlToPdfExecutor.StructureTagsPresent).ToList(), [], []);
                }
            }
            // The reorder verb already applied the approved order; the analyze run reports "not_requested" for itself.
            var remediationStatus = run.State == CoreJobState.Completed ? "applied" : run.RemediationStatus;
            return new JobOutcome(run.State, run.ResultStatus, run.ErrorCode, run.ErrorMessage, remediationStatus, HtmlToPdfExecutor.Truncate(finalVersion),
                run.BaselineValidationJson, run.FinalValidationJson, findings, run.Actions, [.. kept, .. run.Artifacts], snapshots, structure.Pages);
        }
        catch
        {
            OrderOutcomes.DeleteQuietly(runFiles);
            throw;
        }
    }
}
