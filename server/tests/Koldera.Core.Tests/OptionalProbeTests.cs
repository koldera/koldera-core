using System.ComponentModel;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>Probes that only add evidence (structure, semantic, inspect) must not fail a job when the tool is missing or writes nothing.</summary>
public class OptionalProbeTests
{
    private sealed class NoResultWorker : IWorkerProcess
    {
        public Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct) =>
            Task.FromResult(new WorkerRun(1, false, "boom"));
    }

    private sealed class MissingBinaryWorker : IWorkerProcess
    {
        public Task<WorkerRun> RunAsync(string scratchRoot, string requestPath, string resultPath, TimeSpan timeout, CancellationToken ct) =>
            throw new Win32Exception(2, "no such file");
    }

    public static IEnumerable<object[]> Broken() =>
    [
        [new NoResultWorker()],
        [new MissingBinaryWorker()],
    ];

    private static string GeneratedSha => Fixtures.Sha256(Fixtures.SmallPdf());

    [Theory]
    [MemberData(nameof(Broken))]
    public async Task A_pdf_job_completes_without_a_snapshot_when_the_structure_probe_is_broken(IWorkerProcess probe)
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), probe);
        var id = await f.CreatePdfJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Empty(f.Store.GetJob(id)!.Snapshots);
    }

    [Theory]
    [MemberData(nameof(Broken))]
    public async Task An_html_job_completes_without_snapshots_when_the_semantic_and_structure_probes_are_broken(IWorkerProcess probe)
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), probe, htmlRender: StubResults.HtmlRenderCompleted(),
            inspect: StubResults.InspectResult(GeneratedSha), semantic: probe);
        var id = await f.CreateHtmlJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Empty(f.Store.GetJob(id)!.Snapshots);
    }

    [Theory]
    [MemberData(nameof(Broken))]
    public async Task An_html_job_still_reaches_the_engine_when_the_inspect_probe_is_broken(IWorkerProcess inspect)
    {
        var pdf = StubResults.PdfAnalyzeCompleted();
        using var f = CoreFactory.WithStubWorkers(pdf, htmlRender: StubResults.HtmlRenderCompleted(), inspect: inspect);
        var id = await f.CreateHtmlJob();
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.Single(pdf.Calls); // the engine, not the probe, judges the file
    }

    [Fact]
    public async Task A_structure_probe_that_reports_another_hash_is_still_a_contract_error()
    {
        using var f = CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), StubResults.StructureInspected(new string('a', 64)));
        var job = await f.WaitForJob(await f.CreatePdfJob());
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("WORKER_CONTRACT", job.GetProperty("error").GetProperty("code").GetString());
    }
}
