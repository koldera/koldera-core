using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>
/// Reading-order evidence and the approved-correction path against stub workers:
/// everything the server decides itself (validation, refusal codes, base selection, gates, artifacts, evidence) is
/// asserted here; what the real Java/Node verbs do to a PDF or a DOM is covered by the engine's own suites.
/// </summary>
public class ReadingOrderTests
{
    private static readonly byte[] CorrectedHtml = Encoding.UTF8.GetBytes("<!doctype html><html lang=\"en\"><head><title>Page</title></head><body><main><h1>Heading</h1><p>Gamma</p><p>Beta</p><p>Alpha</p></main></body></html>");

    private static string Sha(byte[] bytes) => Fixtures.Sha256(bytes);

    private static string[] PdfTexts(byte[] bytes) => bytes.AsSpan().SequenceEqual(Fixtures.OtherPdf())
        ? ["Gamma paragraph.", "Alpha paragraph.", "Beta paragraph."]
        : ["Alpha paragraph.", "Beta paragraph.", "Gamma paragraph."];

    private static string[] HtmlTexts(byte[] bytes) => bytes.AsSpan().SequenceEqual(CorrectedHtml)
        ? ["Heading", "Gamma", "Beta", "Alpha"]
        : ["Heading", "Alpha", "Beta", "Gamma"];

    private static CoreFactory PdfFactory(IWorkerProcess? reorder = null, IWorkerProcess? pdf = null, Dictionary<string, string?>? env = null, bool structureProbe = true) =>
        CoreFactory.WithStubWorkers(pdf ?? OrderStubs.PdfEngine(), structureProbe ? OrderStubs.StructureTree(PdfTexts) : null, env,
            pdfReorder: reorder ?? OrderStubs.PdfReorderApplied(Fixtures.OtherPdf()));

    private static CoreFactory HtmlFactory(IWorkerProcess? reorder = null, IWorkerProcess? pdf = null, IWorkerProcess? inspect = null, Dictionary<string, string?>? env = null) =>
        CoreFactory.WithStubWorkers(pdf ?? OrderStubs.PdfEngine(), OrderStubs.StructureTree(PdfTexts), env,
            htmlRender: StubResults.HtmlRenderCompleted(), inspect: inspect ?? OrderStubs.InspectEchoing(), semantic: OrderStubs.SemanticTree(HtmlTexts),
            htmlReorder: reorder ?? OrderStubs.HtmlReorderApplied(CorrectedHtml, Fixtures.OtherPdf()));

    private static object Body(string sha, IEnumerable<string> ids, string target = "pdf_tags", int version = 1, bool acknowledged = true, string? reviewer = null) =>
        new { baseArtifactSha256 = sha, snapshotVersion = version, target, orderedNodeIds = ids, acknowledged, reviewer };

    private static Task<HttpResponseMessage> Post(CoreFactory f, Guid jobId, object body) =>
        f.AuthorizedClient().PostAsJsonAsync($"/v1/jobs/{jobId}/reading-order/remediations", body);

    private static async Task<JsonElement> Get(CoreFactory f, Guid jobId) =>
        await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{jobId}/reading-order");

    private static string[] Ids(JsonElement snapshot, string role, string? origin = null) =>
        snapshot.GetProperty("nodes").EnumerateArray().Where(n => n.GetProperty("role").GetString() == role && (origin is null || n.GetProperty("origin").GetString() == origin))
            .Select(n => n.GetProperty("nodeId").GetString()!).ToArray();

    private static string[] Previews(JsonElement snapshot, string role, string? origin = null) =>
        snapshot.GetProperty("nodes").EnumerateArray().Where(n => n.GetProperty("role").GetString() == role && (origin is null || n.GetProperty("origin").GetString() == origin))
            .Select(n => n.GetProperty("textPreview").GetString()!).ToArray();

    private static async Task<(string Code, string Detail)> Problem(HttpResponseMessage r)
    {
        var json = await r.Content.ReadFromJsonAsync<JsonElement>();
        return (json.GetProperty("code").GetString()!, json.TryGetProperty("detail", out var d) ? d.GetString()! : "");
    }

    private static async Task<(Guid Id, JsonElement Snapshot)> CompletedPdfJob(CoreFactory f, string mode = "analyze")
    {
        var id = await f.CreatePdfJob(mode);
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
        return (id, await Get(f, id));
    }

    private static async Task<Guid> Accepted(HttpResponseMessage r)
    {
        Assert.Equal(HttpStatusCode.Accepted, r.StatusCode);
        return (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    // ---- GET: the snapshot is evidence bound to a hash ---------------------------------------------------

    [Fact]
    public async Task A_tagged_pdfs_snapshot_is_served_as_evidence_without_the_private_mapping()
    {
        using var f = PdfFactory();
        var (_, snapshot) = await CompletedPdfJob(f);
        Assert.Equal(Sha(Fixtures.SmallPdf()), snapshot.GetProperty("sourceArtifactSha256").GetString());
        Assert.Equal(Sha(Fixtures.SmallPdf()), snapshot.GetProperty("artifactHashes").GetProperty("pdf_structure").GetString());
        Assert.Equal(1, snapshot.GetProperty("snapshotVersion").GetInt32());
        Assert.True(snapshot.GetProperty("reviewRequired").GetBoolean());
        Assert.Equal("needs_review", snapshot.GetProperty("readingOrderReview").GetString());
        Assert.Equal("not_run", snapshot.GetProperty("focusOrderReview").GetString());
        var nodes = snapshot.GetProperty("nodes").EnumerateArray().ToList();
        Assert.All(nodes, n => Assert.Equal("pdf_structure", n.GetProperty("origin").GetString()));
        Assert.DoesNotContain(nodes, n => n.TryGetProperty("private", out _));
        Assert.Equal(["Alpha paragraph.", "Beta paragraph.", "Gamma paragraph."], Previews(snapshot, "P"));
        var document = Ids(snapshot, "Document").Single();
        Assert.All(nodes.Where(n => n.GetProperty("role").GetString() == "P"), p => Assert.Equal(document, p.GetProperty("parentId").GetString()));
    }

    [Fact]
    public async Task A_job_without_a_snapshot_an_unknown_job_and_another_owners_job_are_404_and_no_token_is_401()
    {
        using var f = PdfFactory(structureProbe: false);
        var id = await f.CreatePdfJob();
        await f.WaitForJob(id);
        Assert.Equal(HttpStatusCode.NotFound, (await f.AuthorizedClient().GetAsync($"/v1/jobs/{id}/reading-order")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await f.AuthorizedClient().GetAsync($"/v1/jobs/{Guid.NewGuid()}/reading-order")).StatusCode);

        var foreign = Guid.NewGuid();
        await f.Store.SaveJobAsync(new CoreJob
        {
            Id = foreign, Kind = CoreJobKind.Pdf, State = CoreJobState.Completed, Owner = "session:other", CreatedAt = f.Clock.GetUtcNow(),
            Snapshots = [new CoreSnapshot { Id = Guid.NewGuid(), ArtifactSha256 = new string('a', 64), CreatedAt = f.Clock.GetUtcNow() }],
        });
        Assert.Equal(HttpStatusCode.NotFound, (await f.AuthorizedClient().GetAsync($"/v1/jobs/{foreign}/reading-order")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await Post(f, foreign, Body(new string('a', 64), ["a", "b"]))).StatusCode);

        Assert.Equal(HttpStatusCode.Unauthorized, (await f.CreateClient().GetAsync($"/v1/jobs/{id}/reading-order")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.CreateClient().PostAsJsonAsync($"/v1/jobs/{id}/reading-order/remediations", new { })).StatusCode);
    }

    // ---- POST (PDF): an approved sibling order is applied to a copy -----------------------------------------

    [Fact]
    public async Task An_approved_sibling_order_creates_an_order_pdf_job_whose_reordered_artifact_is_validated_and_snapshotted()
    {
        var reorder = OrderStubs.PdfReorderApplied(Fixtures.OtherPdf());
        using var f = PdfFactory(reorder);
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var sha = Sha(Fixtures.SmallPdf());

        var response = await Post(f, sourceId, Body(sha, [p[2], p[0], p[1]]));
        Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
        Assert.StartsWith("/v1/jobs/", response.Headers.Location!.OriginalString);
        var created = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("order_pdf", created.GetProperty("kind").GetString());
        Assert.Equal("reorder", created.GetProperty("mode").GetString());
        Assert.Equal("queued", created.GetProperty("state").GetString());
        var id = created.GetProperty("id").GetGuid();

        var done = await f.WaitForJob(id);
        Assert.Equal("completed", done.GetProperty("state").GetString());
        Assert.Equal("applied", done.GetProperty("remediationStatus").GetString());
        Assert.True(done.GetProperty("manualReviewRequired").GetBoolean());
        var stored = f.Store.GetJob(id)!;
        Assert.Equal(sourceId, stored.SourceJobId);
        Assert.Equal("pdf_tags", stored.RemediationTarget);
        Assert.Equal("server", stored.ReviewerIdentity);

        // The worker got the resolved kid path and indexes, the hash of the base and no limits (none configured).
        var request = JsonDocument.Parse(reorder.Calls.Single().RequestJson).RootElement;
        Assert.Equal(sha, request.GetProperty("baseSha256").GetString());
        Assert.Equal([0], request.GetProperty("parentPath").EnumerateArray().Select(e => e.GetInt32()).ToArray());
        Assert.Equal([2, 0, 1], request.GetProperty("orderedKidIndexes").EnumerateArray().Select(e => e.GetInt32()).ToArray());
        Assert.False(request.TryGetProperty("limits", out _));

        var artifact = Assert.Single(stored.Artifacts, a => a.Kind == "reordered");
        Assert.Equal(Sha(Fixtures.OtherPdf()), artifact.Sha256);
        var listed = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{id}/artifacts");
        var item = Assert.Single(listed.GetProperty("items").EnumerateArray(), a => a.GetProperty("kind").GetString() == "reordered");
        Assert.Equal(Fixtures.OtherPdf(), await f.CreateClient().GetByteArrayAsync(item.GetProperty("downloadUrl").GetString()));
        var applied = Assert.Single(stored.Findings, x => x.RuleId == "ORDER_FIX_APPLIED");
        Assert.True(applied.NeedsHumanReview);
        Assert.Contains("rendering verified unchanged", applied.Message);

        // The copy's own snapshot is bound to the copy and reads in the approved order; the source is untouched.
        var after = await Get(f, id);
        Assert.Equal(Sha(Fixtures.OtherPdf()), after.GetProperty("sourceArtifactSha256").GetString());
        Assert.Equal(["Gamma paragraph.", "Alpha paragraph.", "Beta paragraph."], Previews(after, "P"));
        Assert.Equal(Sha(Fixtures.SmallPdf()), (await Get(f, sourceId)).GetProperty("sourceArtifactSha256").GetString());
    }

    [Fact]
    public async Task The_reordered_copy_is_validated_by_the_engine_in_analyze_mode_and_its_artifacts_are_kept()
    {
        var pdf = OrderStubs.PdfEngine();
        using var f = PdfFactory(pdf: pdf);
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[1], p[0], p[2]])));
        await f.WaitForJob(id);
        Assert.Equal(2, pdf.Calls.Count); // the source job's run, then the reordered copy's
        var request = JsonDocument.Parse(pdf.Calls.Last().RequestJson).RootElement;
        Assert.Equal("analyze", request.GetProperty("mode").GetString());
        Assert.Equal("upload", request.GetProperty("origin").GetString());
        Assert.Contains(f.Store.GetJob(id)!.Findings, x => x.Source == "pdf" && x.RuleId == "7.1-3");
    }

    [Fact]
    public async Task The_correction_of_a_tagged_copy_starts_from_that_copy_and_is_measured_against_its_final_validation()
    {
        var reorder = OrderStubs.PdfReorderApplied(Fixtures.OtherPdf());
        // The tagged copy still fails 7.1-3 twice, as its own validation says: the reordered copy is no worse.
        using var f = PdfFactory(reorder, OrderStubs.PdfEngine(Fixtures.ThirdPdf(), [("7.1-3", 2)]));
        var (sourceId, snapshot) = await CompletedPdfJob(f, "fix");
        Assert.Equal(Sha(Fixtures.ThirdPdf()), snapshot.GetProperty("sourceArtifactSha256").GetString());
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.ThirdPdf()), [p[2], p[1], p[0]])));
        var done = await f.WaitForJob(id);
        Assert.True(done.GetProperty("state").GetString() == "completed", done.ToString());
        Assert.Equal(Sha(Fixtures.ThirdPdf()), JsonDocument.Parse(reorder.Calls.Single().RequestJson).RootElement.GetProperty("baseSha256").GetString());
    }

    [Fact]
    public async Task A_correction_of_a_tagged_copy_that_fails_a_rule_the_copy_passed_is_rolled_back()
    {
        // The tagged copy passes cleanly (final validation without failures); the reordered copy fails 7.1-3: a regression.
        using var f = PdfFactory(OrderStubs.PdfReorderApplied(Fixtures.OtherPdf()), OrderStubs.PdfEngine(Fixtures.ThirdPdf(), []));
        var (sourceId, snapshot) = await CompletedPdfJob(f, "fix");
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.ThirdPdf()), [p[2], p[1], p[0]])));
        var done = await f.WaitForJob(id);
        Assert.Equal("failed", done.GetProperty("state").GetString());
        Assert.Equal("ORDER_FIX_REGRESSION", done.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_copy_that_validates_worse_than_the_source_is_rolled_back_and_leaves_nothing_behind()
    {
        // The engine adds a failure for the reordered copy only (identified by the hash of its input).
        var pdf = OrderStubs.PdfEngine(mutate: (result, input) =>
        {
            if (input == Sha(Fixtures.OtherPdf())) result["baseline"]!["failedRules"]!.AsArray().Add(new System.Text.Json.Nodes.JsonObject { ["ruleId"] = "SYNTHETIC_REGRESSION", ["count"] = 1 });
        });
        using var f = PdfFactory(pdf: pdf);
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[1], p[0], p[2]])));
        var done = await f.WaitForJob(id);
        Assert.Equal("failed", done.GetProperty("state").GetString());
        Assert.Equal("ORDER_FIX_REGRESSION", done.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains("SYNTHETIC_REGRESSION", done.GetProperty("error").GetProperty("message").GetString());
        Assert.Equal("rolled_back", done.GetProperty("remediationStatus").GetString());
        var stored = f.Store.GetJob(id)!;
        Assert.Empty(stored.Artifacts);
        Assert.Empty(stored.Snapshots);
        Assert.Empty(Directory.GetFiles(Path.Combine(f.Store.JobDir(id), "out")));
        // The source job and its evidence are untouched.
        Assert.Single(f.Store.GetJob(sourceId)!.Snapshots);
    }

    [Theory]
    [InlineData("unsupported", "ORDER_FIX_UNSUPPORTED", "not_requested")]
    [InlineData("failed", "INTEGRITY_REGRESSION", "rolled_back")]
    [InlineData("failed", "LIMIT_EXCEEDED", "not_requested")]
    public async Task A_refusal_by_the_pdf_reorder_verb_fails_the_job_with_its_code_and_publishes_nothing(string status, string code, string remediation)
    {
        using var f = PdfFactory(OrderStubs.PdfReorderRefused(status, code, "the engine's reason"));
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]])));
        var done = await f.WaitForJob(id);
        Assert.Equal("failed", done.GetProperty("state").GetString());
        Assert.Equal(code, done.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal("the engine's reason", done.GetProperty("error").GetProperty("message").GetString());
        Assert.Equal(remediation, done.GetProperty("remediationStatus").GetString());
        Assert.Empty(f.Store.GetJob(id)!.Artifacts);
    }

    [Fact]
    public async Task A_reordered_file_that_is_not_what_the_worker_declared_fails_as_a_contract_error_and_is_not_kept()
    {
        using var f = PdfFactory(OrderStubs.PdfReorderLying(Fixtures.OtherPdf(), Fixtures.ThirdPdf()));
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]])));
        var done = await f.WaitForJob(id);
        Assert.Equal("WORKER_CONTRACT", done.GetProperty("error").GetProperty("code").GetString());
        Assert.Empty(f.Store.GetJob(id)!.Artifacts);
    }

    [Fact]
    public async Task The_configured_limits_are_sent_to_the_reorder_verb_and_the_time_limit_is_passed_unchanged()
    {
        var reorder = OrderStubs.PdfReorderApplied(Fixtures.OtherPdf());
        using var f = PdfFactory(reorder, env: new() { ["KOLDERA_PDF_MAX_BYTES"] = "1000000", ["KOLDERA_PDF_MAX_PAGES"] = "40", ["KOLDERA_JOB_TIMEOUT_SECONDS"] = "90" });
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]])));
        await f.WaitForJob(id);
        var call = reorder.Calls.Single();
        Assert.Equal(TimeSpan.FromSeconds(90), call.Timeout);
        var limits = JsonDocument.Parse(call.RequestJson).RootElement.GetProperty("limits");
        Assert.Equal((1000000L, 40, 90), (limits.GetProperty("maxBytes").GetInt64(), limits.GetProperty("maxPages").GetInt32(), limits.GetProperty("timeoutSeconds").GetInt32()));
    }

    [Fact]
    public async Task A_timed_out_reorder_verb_fails_the_job_as_a_timeout()
    {
        using var f = PdfFactory(new StubWorker(_ => "", timedOut: true));
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]])));
        Assert.Equal("WORKER_TIMEOUT", (await f.WaitForJob(id)).GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_full_queue_answers_503_and_leaves_no_job_behind()
    {
        var blocking = new BlockingReorder(OrderStubs.PdfReorderApplied(Fixtures.OtherPdf()));
        using var f = PdfFactory(blocking, env: new() { ["KOLDERA_CONCURRENCY"] = "1", ["KOLDERA_QUEUE_CAPACITY"] = "3" });
        try
        {
            var (sourceId, snapshot) = await CompletedPdfJob(f);
            var p = Ids(snapshot, "P");
            var body = Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]]);
            await Accepted(await Post(f, sourceId, body));
            await blocking.Started.Task.WaitAsync(TimeSpan.FromSeconds(30));
            for (var i = 0; i < 3; i++) await Accepted(await Post(f, sourceId, body)); // fills the queue
            var before = f.Store.AllJobs().Count();
            var refused = await Post(f, sourceId, body);
            Assert.Equal(HttpStatusCode.ServiceUnavailable, refused.StatusCode);
            Assert.Equal("30", refused.Headers.RetryAfter!.Delta!.Value.TotalSeconds.ToString());
            Assert.Equal("QUEUE_FULL", (await Problem(refused)).Code);
            Assert.Equal(before, f.Store.AllJobs().Count());
        }
        finally
        {
            blocking.Release.TrySetResult();
        }
    }

    // ---- POST: validation and refusals, with the monorepo's codes ----------------------------------------------

    [Fact]
    public async Task Requests_that_are_not_an_approved_complete_sibling_permutation_are_refused_with_the_monorepos_codes()
    {
        var reorder = OrderStubs.PdfReorderApplied(Fixtures.OtherPdf());
        using var f = PdfFactory(reorder);
        var (id, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var document = Ids(snapshot, "Document").Single();
        var sha = Sha(Fixtures.SmallPdf());
        var jobsBefore = f.Store.AllJobs().Count();

        async Task<(HttpStatusCode Status, string Code, string Detail)> Refused(object body)
        {
            var r = await Post(f, id, body);
            var (code, detail) = await Problem(r);
            return (r.StatusCode, code, detail);
        }

        // Stale artifact hash or snapshot version.
        Assert.Equal((HttpStatusCode.Conflict, "STALE_READING_ORDER_SNAPSHOT"), Take(await Refused(Body(new string('0', 64), [p[2], p[1], p[0]]))));
        Assert.Equal((HttpStatusCode.Conflict, "STALE_READING_ORDER_SNAPSHOT"), Take(await Refused(Body(sha, [p[2], p[1], p[0]], version: 2))));
        // A subset of the sibling group, nodes of different parents, the current order, unknown ids, the wrong target for the job kind.
        var subset = await Refused(Body(sha, [p[1], p[0]]));
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), Take(subset));
        Assert.Contains("complete sibling group", subset.Detail);
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), Take(await Refused(Body(sha, [document, p[0], p[1]]))));
        var identity = await Refused(Body(sha, p));
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), Take(identity));
        Assert.Contains("nothing to correct", identity.Detail);
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), Take(await Refused(Body(sha, ["nffffffffffffffff", p[0], p[1]]))));
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), Take(await Refused(Body(sha, [p[2], p[1], p[0]], target: "source_html"))));
        // Field-level validation.
        foreach (var body in new[]
        {
            Body(sha, [p[2], p[1], p[0]], acknowledged: false),
            Body(sha, [p[2], p[1], p[0]], target: "pdf_bytes"),
            Body(sha, [p[0]]),
            Body(sha, [p[0], p[0], p[1]]),
            Body(sha, [p[0], ""]),
            Body("ABC", [p[2], p[1], p[0]]),
            Body(sha, [p[2], p[1], p[0]], reviewer: new string('r', 201)),
            Body(sha, [p[2], p[1], p[0]], reviewer: "bad\nname"),
            Body(sha, [p[2], p[1], p[0]], reviewer: "   "),
        })
        {
            Assert.Equal((HttpStatusCode.UnprocessableEntity, "VALIDATION_ERROR"), Take(await Refused(body)));
        }

        // Nothing above queued a job or reached a worker.
        Assert.Equal(jobsBefore, f.Store.AllJobs().Count());
        Assert.Empty(reorder.Calls);

        static (HttpStatusCode, string) Take((HttpStatusCode Status, string Code, string Detail) r) => (r.Status, r.Code);
    }

    [Fact]
    public async Task A_source_job_that_is_not_completed_or_not_a_pdf_or_html_job_or_has_no_snapshot_is_refused()
    {
        using var f = PdfFactory();
        var now = f.Clock.GetUtcNow();
        var sha = new string('a', 64);
        var queued = Guid.NewGuid();
        var web = Guid.NewGuid();
        var bare = Guid.NewGuid();
        await f.Store.SaveJobAsync(new CoreJob { Id = queued, Kind = CoreJobKind.Pdf, State = CoreJobState.Queued, Owner = "server", CreatedAt = now });
        await f.Store.SaveJobAsync(new CoreJob { Id = web, Kind = CoreJobKind.Web, State = CoreJobState.Completed, Owner = "server", CreatedAt = now });
        await f.Store.SaveJobAsync(new CoreJob { Id = bare, Kind = CoreJobKind.Pdf, State = CoreJobState.Completed, Owner = "server", CreatedAt = now });

        var notDone = await Post(f, queued, Body(sha, ["a", "b"]));
        Assert.Equal((HttpStatusCode.Conflict, "STALE_READING_ORDER_SNAPSHOT"), (notDone.StatusCode, (await Problem(notDone)).Code));
        var wrongKind = await Post(f, web, Body(sha, ["a", "b"]));
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), (wrongKind.StatusCode, (await Problem(wrongKind)).Code));
        var noSnapshot = await Post(f, bare, Body(sha, ["a", "b"]));
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), (noSnapshot.StatusCode, (await Problem(noSnapshot)).Code));
        Assert.Equal(HttpStatusCode.NotFound, (await Post(f, Guid.NewGuid(), Body(sha, ["a", "b"]))).StatusCode);
    }

    [Fact]
    public async Task A_correction_job_is_not_itself_a_source_for_another_correction()
    {
        using var f = PdfFactory();
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]])));
        await f.WaitForJob(id);
        var again = await Post(f, id, Body(Sha(Fixtures.OtherPdf()), [p[0], p[1], p[2]]));
        Assert.Equal((HttpStatusCode.UnprocessableEntity, "ORDER_FIX_UNSUPPORTED"), (again.StatusCode, (await Problem(again)).Code));
    }

    [Fact]
    public async Task A_base_that_is_no_longer_stored_is_refused_with_409()
    {
        using var f = PdfFactory();
        var (id, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        // The uploaded original the snapshot describes was removed (retention): nothing stored matches its hash any more.
        f.Store.DeleteUpload(f.Store.GetJob(id)!.InputUploadId!.Value);
        var gone = await Post(f, id, Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]]));
        Assert.Equal((HttpStatusCode.Conflict, "STALE_READING_ORDER_SNAPSHOT"), (gone.StatusCode, (await Problem(gone)).Code));
    }

    [Fact]
    public async Task A_tagged_copy_past_its_expiry_is_refused_as_an_expired_artifact()
    {
        using var f = PdfFactory(pdf: OrderStubs.PdfEngine(Fixtures.ThirdPdf(), [("7.1-3", 2)]));
        var (id, snapshot) = await CompletedPdfJob(f, "fix");
        var job = f.Store.GetJob(id)!;
        var copy = job.Artifacts.Single(a => a.Kind == "remediated");
        job.Artifacts = [copy with { ExpiresAt = f.Clock.GetUtcNow().AddMinutes(-1) }];
        await f.Store.SaveJobAsync(job);
        var p = Ids(snapshot, "P");
        var r = await Post(f, id, Body(Sha(Fixtures.ThirdPdf()), [p[2], p[1], p[0]]));
        Assert.Equal((HttpStatusCode.Conflict, "ARTIFACT_EXPIRED"), (r.StatusCode, (await Problem(r)).Code));
    }

    [Fact]
    public async Task The_reviewer_is_the_callers_label_when_given_and_the_principal_otherwise()
    {
        using var f = PdfFactory();
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var sha = Sha(Fixtures.SmallPdf());
        var named = await Accepted(await Post(f, sourceId, Body(sha, [p[2], p[1], p[0]], reviewer: "  a.lovelace@example.org  ")));
        var anonymous = await Accepted(await Post(f, sourceId, Body(sha, [p[1], p[0], p[2]])));
        await f.WaitForJob(named);
        await f.WaitForJob(anonymous);
        Assert.Equal("a.lovelace@example.org", f.Store.GetJob(named)!.ReviewerIdentity);
        Assert.Equal("server", f.Store.GetJob(anonymous)!.ReviewerIdentity);
        // The audit trail travels with the evidence too.
        var applied = f.Store.GetJob(named)!.Findings.Single(x => x.RuleId == "ORDER_FIX_APPLIED");
        Assert.Contains("a.lovelace@example.org", applied.EvidenceJson);
        Assert.Contains(p[2], applied.EvidenceJson);
    }

    [Fact]
    public async Task The_approved_plan_and_a_private_copy_of_the_base_stay_out_of_the_published_artifacts()
    {
        using var f = PdfFactory();
        var (sourceId, snapshot) = await CompletedPdfJob(f);
        var p = Ids(snapshot, "P");
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallPdf()), [p[2], p[1], p[0]])));
        await f.WaitForJob(id);
        var kinds = f.Store.GetJob(id)!.Artifacts.Select(a => a.Kind).ToArray();
        Assert.DoesNotContain("remediation_plan", kinds);
        Assert.DoesNotContain("base_pdf", kinds);
        Assert.DoesNotContain(Directory.GetFiles(Path.Combine(f.Store.JobDir(id), "out")), x => x.EndsWith("plan.json") || x.Contains("base"));
    }

    // ---- HTML: DOM + structure snapshots, approved source order, re-render, re-validation -------------------

    [Fact]
    public async Task An_html_job_carries_dom_and_structure_snapshots_each_bound_to_its_artifact()
    {
        using var f = HtmlFactory();
        var id = await f.CreateHtmlJob();
        var finished = await f.WaitForJob(id);
        Assert.True(finished.GetProperty("state").GetString() == "completed", finished.ToString());
        var snapshot = await Get(f, id);
        var nodes = snapshot.GetProperty("nodes").EnumerateArray().ToList();
        Assert.Contains(nodes, n => n.GetProperty("origin").GetString() == "pdf_structure");
        Assert.Contains(nodes, n => n.GetProperty("origin").GetString() == "html_dom");
        Assert.Equal(Sha(Fixtures.SmallHtml()), snapshot.GetProperty("artifactHashes").GetProperty("html_dom").GetString());
        Assert.Equal(snapshot.GetProperty("sourceArtifactSha256").GetString(), snapshot.GetProperty("artifactHashes").GetProperty("pdf_structure").GetString());
        // The PDF structure order comes first; each node names its origin.
        Assert.Equal("pdf_structure", nodes[0].GetProperty("origin").GetString());
        Assert.Equal(["Alpha", "Beta", "Gamma"], Previews(snapshot, "p", "html_dom"));
        Assert.Contains("c3", snapshot.GetProperty("visualOrderCandidateNodeIds").EnumerateArray().Select(e => e.GetString()));
    }

    [Fact]
    public async Task An_approved_source_order_is_applied_re_rendered_re_validated_and_snapshotted()
    {
        var reorder = OrderStubs.HtmlReorderApplied(CorrectedHtml, Fixtures.OtherPdf());
        var semantic = OrderStubs.SemanticTree(HtmlTexts);
        using var f = CoreFactory.WithStubWorkers(OrderStubs.PdfEngine(), OrderStubs.StructureTree(PdfTexts), null,
            htmlRender: StubResults.HtmlRenderCompleted(), inspect: OrderStubs.InspectEchoing(), semantic: semantic, htmlReorder: reorder);
        var sourceId = await f.CreateHtmlJob();
        await f.WaitForJob(sourceId);
        var snapshot = await Get(f, sourceId);
        var c = Ids(snapshot, "h1", "html_dom").Concat(Ids(snapshot, "p", "html_dom")).ToArray();
        Assert.Equal(["c0", "c1", "c2", "c3"], c);

        var response = await Post(f, sourceId, Body(Sha(Fixtures.SmallHtml()), [c[0], c[3], c[2], c[1]], target: "source_html"));
        Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
        var created = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("order_html", created.GetProperty("kind").GetString());
        var id = created.GetProperty("id").GetGuid();
        var done = await f.WaitForJob(id);
        Assert.True(done.GetProperty("state").GetString() == "completed", done.ToString());
        Assert.Equal("applied", done.GetProperty("remediationStatus").GetString());

        // The worker got the resolved DOM path and indexes, the base hash, the correctness gate and the page.
        var request = JsonDocument.Parse(reorder.Calls.Single().RequestJson).RootElement;
        Assert.Equal(Sha(Fixtures.SmallHtml()), request.GetProperty("baseSha256").GetString());
        Assert.Equal("1/0", request.GetProperty("parentPath").GetString());
        Assert.Equal([0, 3, 2, 1], request.GetProperty("orderedChildIndexes").EnumerateArray().Select(e => e.GetInt32()).ToArray());
        Assert.Equal(0.02, request.GetProperty("limits").GetProperty("maxVisualDiffRatio").GetDouble());
        Assert.Equal("A4", request.GetProperty("page").GetProperty("format").GetString());
        Assert.False(request.TryGetProperty("content", out _));

        var stored = f.Store.GetJob(id)!;
        Assert.Equal("source_html", stored.RemediationTarget);
        Assert.Equal(Sha(CorrectedHtml), Assert.Single(stored.Artifacts, a => a.Kind == "source_html").Sha256);
        Assert.Equal(Sha(Fixtures.OtherPdf()), Assert.Single(stored.Artifacts, a => a.Kind == "generated_pdf").Sha256);
        Assert.Contains(stored.Findings, x => x.RuleId == "SOURCE_ORDER_FIX_APPLIED" && x.NeedsHumanReview);
        Assert.Contains(stored.Findings, x => x.RuleId == "PDF_STRUCTURE_TAGS_PRESENT");

        // Evidence of the corrected copy: DOM order bound to the corrected source, PDF order bound to the re-rendered PDF.
        var after = await Get(f, id);
        Assert.Equal(["Gamma", "Beta", "Alpha"], Previews(after, "p", "html_dom"));
        Assert.Equal(Sha(CorrectedHtml), after.GetProperty("artifactHashes").GetProperty("html_dom").GetString());
        Assert.Equal(Sha(Fixtures.OtherPdf()), after.GetProperty("artifactHashes").GetProperty("pdf_structure").GetString());
        // The semantic collector saw the corrected bytes, with the unchanged (infinite) time limit.
        Assert.Equal(Timeout.InfiniteTimeSpan, semantic.Calls.Last().Timeout);
        Assert.Equal(Sha(Fixtures.SmallHtml()), (await Get(f, sourceId)).GetProperty("artifactHashes").GetProperty("html_dom").GetString());
    }

    [Fact]
    public async Task The_content_selector_of_the_source_job_is_sent_to_the_html_reorder_verb()
    {
        var reorder = OrderStubs.HtmlReorderApplied(CorrectedHtml, Fixtures.OtherPdf());
        using var f = HtmlFactory(reorder);
        var sourceId = await f.CreateHtmlJob(contentSelector: "main");
        await f.WaitForJob(sourceId);
        var snapshot = await Get(f, sourceId);
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallHtml()), ["c0", "c3", "c2", "c1"], target: "source_html")));
        await f.WaitForJob(id);
        Assert.Equal("main", JsonDocument.Parse(reorder.Calls.Single().RequestJson).RootElement.GetProperty("content").GetProperty("selector").GetString());
        Assert.Equal("main", f.Store.GetJob(id)!.ContentSelector);
    }

    [Theory]
    [InlineData("ORDER_FIX_UNSUPPORTED", "the approved order would change the visual order of the page", "not_requested")]
    [InlineData("ORDER_FIX_REGRESSION", "the corrected source renders differently", "rolled_back")]
    public async Task A_refusal_by_the_html_reorder_verb_fails_the_job_with_its_code_and_publishes_nothing(string code, string message, string remediation)
    {
        using var f = HtmlFactory(OrderStubs.HtmlReorderRefused(code, message));
        var sourceId = await f.CreateHtmlJob();
        await f.WaitForJob(sourceId);
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallHtml()), ["c0", "c2", "c1", "c3"], target: "source_html")));
        var done = await f.WaitForJob(id);
        Assert.Equal("failed", done.GetProperty("state").GetString());
        Assert.Equal(code, done.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(message, done.GetProperty("error").GetProperty("message").GetString());
        Assert.Equal(remediation, done.GetProperty("remediationStatus").GetString());
        Assert.Empty(f.Store.GetJob(id)!.Artifacts);
        // The source upload is still there, byte for byte.
        Assert.Equal(Fixtures.SmallHtml(), await File.ReadAllBytesAsync(f.Store.UploadPath(f.Store.GetJob(sourceId)!.InputUploadId!.Value)));
    }

    [Fact]
    public async Task A_corrected_pdf_that_validates_worse_than_the_source_is_rolled_back_with_its_files()
    {
        var pdf = OrderStubs.PdfEngine(mutate: (result, input) =>
        {
            if (input == Sha(Fixtures.OtherPdf())) result["baseline"]!["failedRules"]!.AsArray().Add(new System.Text.Json.Nodes.JsonObject { ["ruleId"] = "SYNTHETIC_REGRESSION", ["count"] = 1 });
        });
        using var f = HtmlFactory(pdf: pdf);
        var sourceId = await f.CreateHtmlJob();
        await f.WaitForJob(sourceId);
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallHtml()), ["c0", "c3", "c2", "c1"], target: "source_html")));
        var done = await f.WaitForJob(id);
        Assert.Equal("failed", done.GetProperty("state").GetString());
        Assert.Equal("ORDER_FIX_REGRESSION", done.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal("rolled_back", done.GetProperty("remediationStatus").GetString());
        var stored = f.Store.GetJob(id)!;
        Assert.Empty(stored.Artifacts);
        Assert.Empty(stored.Snapshots);
        Assert.Empty(Directory.GetFiles(Path.Combine(f.Store.JobDir(id), "out")));
    }

    [Fact]
    public async Task A_re_rendered_pdf_without_usable_tags_fails_and_is_not_treated_as_accessible()
    {
        using var f = HtmlFactory(inspect: OrderStubs.InspectEchoing(bytes => !bytes.AsSpan().SequenceEqual(Fixtures.OtherPdf())));
        var sourceId = await f.CreateHtmlJob();
        await f.WaitForJob(sourceId);
        var id = await Accepted(await Post(f, sourceId, Body(Sha(Fixtures.SmallHtml()), ["c0", "c3", "c2", "c1"], target: "source_html")));
        var done = await f.WaitForJob(id);
        Assert.Equal("failed", done.GetProperty("state").GetString());
        Assert.Equal("TAGGED_OUTPUT_UNAVAILABLE", done.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains(f.Store.GetJob(id)!.Findings, x => x.RuleId == "TAGGED_OUTPUT_UNAVAILABLE" && x.Severity == "blocking");
    }

    [Fact]
    public async Task A_pdf_tags_correction_of_an_html_job_starts_from_its_generated_pdf()
    {
        var reorder = OrderStubs.PdfReorderApplied(Fixtures.ThirdPdf());
        using var f = CoreFactory.WithStubWorkers(OrderStubs.PdfEngine(), OrderStubs.StructureTree(PdfTexts), null,
            htmlRender: StubResults.HtmlRenderCompleted(), inspect: OrderStubs.InspectEchoing(), semantic: OrderStubs.SemanticTree(HtmlTexts), pdfReorder: reorder);
        var sourceId = await f.CreateHtmlJob();
        await f.WaitForJob(sourceId);
        var snapshot = await Get(f, sourceId);
        var p = Ids(snapshot, "P");
        var generated = Sha(Fixtures.SmallPdf());
        var id = await Accepted(await Post(f, sourceId, Body(generated, [p[2], p[1], p[0]])));
        var done = await f.WaitForJob(id);
        Assert.Equal("completed", done.GetProperty("state").GetString());
        Assert.Equal("order_pdf", f.Store.GetJob(id)!.Kind);
        Assert.Equal(generated, JsonDocument.Parse(reorder.Calls.Single().RequestJson).RootElement.GetProperty("baseSha256").GetString());
        // source_html is refused for a PDF source job; pdf_tags needs the structure hash, not the DOM's.
        var wrong = await Post(f, sourceId, Body(Sha(Fixtures.SmallHtml()), [p[2], p[1], p[0]]));
        Assert.Equal(HttpStatusCode.Conflict, wrong.StatusCode);
    }
}
