namespace Koldera.Core.Workers;

/// <summary>The renderer process and the read-only structure probe that checks what it produced.</summary>
public sealed record HtmlWorkers(IWorkerProcess Render, IWorkerProcess Inspect);

/// <summary>
/// One HTML job in a single execution: render the source to a tagged PDF, probe what the file really
/// carries, then run the PDF engine on it (origin html_generated). Nothing here turns "tagged was
/// requested" into "tagged": a render without usable structure tags fails and keeps the file for review.
/// Findings: the renderer's first, then the PDF engine's.
/// </summary>
public sealed class HtmlToPdfExecutor(DataStore store, HtmlWorkers html, PdfWorkers pdf, CoreOptions options, TimeProvider clock) : IJobExecutor
{
    public const string TaggedOutputUnavailable = "TAGGED_OUTPUT_UNAVAILABLE";
    public const string StructureTagsPresent = "PDF_STRUCTURE_TAGS_PRESENT";
    private const int EngineVersionMaxLength = 200;

    public string Kind => CoreJobKind.HtmlToPdf;

    public async Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct)
    {
        var upload = job.InputUploadId is { } uploadId ? store.GetUpload(uploadId) : null;
        if (upload is null || !upload.Completed) throw new WorkerContractException("the input upload is missing");

        // 1. Render.
        var sourcePath = Path.Combine(scratch, "in", "source.html");
        File.Copy(store.UploadPath(upload.Id), sourcePath, overwrite: true);
        var expectedSource = upload.Sha256.ToLowerInvariant();
        if (await PdfWorkerRun.Sha256Async(sourcePath, ct) != expectedSource)
            throw new WorkerContractException("stored source does not match its recorded SHA-256");

        var maxBytes = options.Limits.HtmlMaxBytes;
        var request = new HtmlRendererRequest(HtmlWorkerContracts.SpecVersion, job.Id, HtmlRendererRequest.CanonicalInputPath, HtmlRendererRequest.CanonicalOutputDirectory,
            maxBytes is null ? null : new HtmlRendererLimits(null, maxBytes, null, null, null),
            new HtmlRendererPage("A4", true), HtmlRendererContent.For(job.ContentSelector));
        var requestPath = Path.Combine(scratch, "request.json");
        var resultPath = Path.Combine(scratch, "result.json");
        await File.WriteAllTextAsync(requestPath, HtmlWorkerContracts.SerializeRequest(request), ct);
        var render = await html.Render.RunAsync(scratch, requestPath, resultPath, timeout, ct);
        if (render.TimedOut) throw new TimeoutException("renderer exceeded its time budget");
        if (!File.Exists(resultPath))
            throw new InvalidOperationException($"renderer exited with code {render.ExitCode} without writing a result");
        var rendered = HtmlWorkerContracts.ParseRendererResult(await File.ReadAllTextAsync(resultPath, ct), job.Id);
        if (rendered.SourceSha256 is not null && rendered.SourceSha256 != expectedSource)
            throw new WorkerContractException("renderer reported a different source SHA-256");

        var findings = rendered.Diagnostics.Select(d => new CoreFinding
        {
            Id = Guid.NewGuid(),
            Source = "html",
            RuleId = d.Code,
            Severity = d.Blocking ? "blocking" : "advisory",
            Selector = d.Line is { } line ? (d.Element is null ? $"line {line}" : $"line {line} <{d.Element}>") : d.Element is null ? null : $"<{d.Element}>",
            Disposition = d.Blocking ? "refused" : "advisory",
            MachineCheck = true,
            Message = d.Message,
            NeedsHumanReview = !d.Blocking,
        }).ToList();

        if (rendered.Status != "completed")
        {
            if (rendered.Artifacts.Count > 0)
                throw new WorkerContractException("renderer published an artifact for a non-completed render");
            var code = rendered.Error?.Code ?? (rendered.Status == "unsupported" ? "UNSUPPORTED_CONTENT" : "RENDER_FAILED");
            var message = rendered.Error?.Message ?? (rendered.Status == "unsupported" ? "the source cannot be rendered as static HTML (see findings)" : "the renderer failed");
            return new JobOutcome(CoreJobState.Failed, rendered.Status, code, message, "not_requested", rendered.EngineVersion, null, null, findings, [], []);
        }

        var declared = rendered.Artifacts.SingleOrDefault(a => a.Kind == "generated_pdf") ?? throw new WorkerContractException("completed render without a generated_pdf artifact");
        var generatedName = ArtifactFiles.RequireOutputName(declared.Path, HtmlRendererRequest.CanonicalOutputDirectory);
        var now = clock.GetUtcNow();
        var retention = now.AddHours(options.RetentionHours);
        var outDir = Path.Combine(store.JobDir(job.Id), "out");
        var generated = await ArtifactFiles.VerifyAndKeepAsync(Path.Combine(scratch, "out", generatedName), "generated_pdf", declared.Sha256, declared.Bytes, outDir, now, retention, ct);
        var generatedPath = Path.Combine(outDir, generated.FileName);

        // 2. Independent structure probe on the exact bytes the renderer produced.
        var probePath = Path.Combine(scratch, "in", "generated.pdf");
        File.Copy(generatedPath, probePath, overwrite: true);
        var inspectPath = Path.Combine(scratch, "inspect.json");
        var probe = await html.Inspect.RunAsync(scratch, probePath, inspectPath, timeout, ct);
        if (probe.TimedOut) throw new TimeoutException("structure probe exceeded its time budget");
        if (!File.Exists(inspectPath))
            throw new InvalidOperationException($"structure probe exited with code {probe.ExitCode} without writing a result");
        var inspect = HtmlWorkerContracts.ParseInspectResult(await File.ReadAllTextAsync(inspectPath, ct));
        if (inspect.Sha256 is not null && inspect.Sha256 != generated.Sha256)
            throw new WorkerContractException("structure probe reported a different SHA-256");
        var engineVersion = Truncate(string.IsNullOrEmpty(rendered.EngineVersion) ? inspect.EngineVersion : rendered.EngineVersion + "; " + inspect.EngineVersion);

        var structure = inspect.Structure;
        if (inspect.Status != "inspected" || structure is null || !structure.UsableTags)
        {
            var reason = inspect.Error?.Message ?? DescribeMissingTags(structure);
            findings.Add(new CoreFinding
            {
                Id = Guid.NewGuid(),
                Source = "pdf",
                RuleId = TaggedOutputUnavailable,
                Severity = "blocking",
                MachineCheck = true,
                Message = "the renderer was asked for a tagged PDF but the output carries no usable structure tags (" + reason + "); the generated file is kept for review and is not treated as accessible",
                NeedsHumanReview = true,
            });
            return new JobOutcome(CoreJobState.Failed, "unsupported", TaggedOutputUnavailable, "the generated PDF has no usable structure tags: " + reason, "not_requested", engineVersion, null, null, findings, [], [generated]);
        }

        // 3. The PDF engine on the generated file, in its own scratch layout; the mode maps to the engine's.
        var pdfScratch = Path.Combine(scratch, "pdf");
        Directory.CreateDirectory(Path.Combine(pdfScratch, "in"));
        Directory.CreateDirectory(Path.Combine(pdfScratch, "out"));
        var engineJob = new CoreJob
        {
            Id = job.Id,
            Mode = job.Mode == "generate_fix" ? "fix" : "analyze",
            Profile = job.Profile,
            ExplicitLanguage = job.ExplicitLanguage,
            ExplicitTitle = job.ExplicitTitle,
            SourceFileName = job.SourceFileName,
        };
        var run = await PdfWorkerRun.ExecuteAsync(pdf.Process, engineJob, generatedPath, generated.Sha256, generated.Bytes, WorkerOrigin.HtmlGenerated,
            PdfJobExecutor.LimitsFrom(options.Limits), pdfScratch, outDir, now, retention, timeout, ct);

        findings.AddRange(run.Findings);
        findings.Add(new CoreFinding
        {
            Id = Guid.NewGuid(),
            Source = "pdf",
            RuleId = StructureTagsPresent,
            Severity = "info",
            MachineCheck = true,
            Message = $"the generated PDF carries a structure tree ({structure.StructureElements} elements, {structure.ContentReferences} content references; roles {DescribeRoles(structure)}); whether the tags are correct, complete and in a meaningful reading order requires human review",
            NeedsHumanReview = true,
        });
        // The probe and the engine are the same program; only append a version string that adds information.
        var finalVersion = string.IsNullOrEmpty(run.EngineVersion) || run.EngineVersion == inspect.EngineVersion ? engineVersion : engineVersion + "; " + run.EngineVersion;

        // 4. Reading-order evidence for the generated PDF (the exact bytes probed above).
        var snapshots = new List<CoreSnapshot>();
        if (pdf.StructureProbe is not null && run.State == CoreJobState.Completed)
        {
            var snapshot = await Snapshots.ProbeStructureAsync(pdf.StructureProbe, scratch, probePath, timeout, ct);
            if (snapshot.Status == "inspected")
            {
                if (snapshot.Sha256 != generated.Sha256)
                    throw new WorkerContractException("structure snapshot reported a different SHA-256");
                snapshots.Add(Snapshots.FromStructure(snapshot, clock.GetUtcNow()));
                findings.AddRange(Snapshots.FindingsFromStructure(snapshot));
            }
        }

        return new JobOutcome(run.State, run.ResultStatus, run.ErrorCode, run.ErrorMessage, run.RemediationStatus, Truncate(finalVersion),
            run.BaselineValidationJson, run.FinalValidationJson, findings, run.Actions, [generated, .. run.Artifacts], snapshots, structure.Pages);
    }

    private static string DescribeMissingTags(InspectStructure? s) => s is null
        ? "the file could not be inspected"
        : $"MarkInfo/Marked={s.MarkInfoMarked.ToString().ToLowerInvariant()}, StructTreeRoot={s.StructTreeRoot.ToString().ToLowerInvariant()}, structureElements={s.StructureElements}, contentReferences={s.ContentReferences}";

    private static string DescribeRoles(InspectStructure s) =>
        s.Roles.Count == 0 ? "none" : string.Join(", ", s.Roles.Take(12).Select(r => $"{r.Role}x{r.Count}"));

    private static string Truncate(string value) => value.Length <= EngineVersionMaxLength ? value : value[..EngineVersionMaxLength];
}
