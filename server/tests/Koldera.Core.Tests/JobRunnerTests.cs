using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;
using Microsoft.Extensions.Logging;

namespace Koldera.Core.Tests;

public sealed class JobRunnerTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "koldera-runner-" + Guid.NewGuid().ToString("N"));
    private readonly DataStore _store;
    private readonly ListLogger _log = new();
    private readonly List<JobRunner> _started = [];

    public JobRunnerTests()
    {
        Directory.CreateDirectory(_root);
        _store = new DataStore(_root);
    }

    public void Dispose()
    {
        foreach (var r in _started) r.StopAsync(CancellationToken.None).GetAwaiter().GetResult();
        try { Directory.Delete(_root, true); } catch (IOException) { }
    }

    private sealed class LoggerOf(ListLogger inner) : ILogger<JobRunner>
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => true;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) =>
            inner.Log(logLevel, eventId, state, exception, formatter);
    }

    private sealed class LambdaExecutor(string kind, Func<CoreJob, string, TimeSpan, CancellationToken, Task<JobOutcome>> run) : IJobExecutor
    {
        public string Kind { get; } = kind;
        public Task<JobOutcome> ExecuteAsync(CoreJob job, string scratch, TimeSpan timeout, CancellationToken ct) => run(job, scratch, timeout, ct);
    }

    private static IJobExecutor Throwing(Exception e) => new LambdaExecutor("pdf", (_, _, _, _) => throw e);

    private static JobOutcome Done() =>
        new(CoreJobState.Completed, "ok", null, null, "none", "engine-1", "{\"b\":1}", "{\"f\":1}",
            [new CoreFinding { Id = Guid.NewGuid(), RuleId = "R1", Message = "m" }], [], [], null, 3);

    private (JobRunner Runner, JobQueue Queue) Runner(IJobExecutor executor, int? timeoutSeconds = null, int capacity = 10, int concurrency = 1, TimeProvider? clock = null)
    {
        var env = new Dictionary<string, string?>
        {
            ["KOLDERA_DATA_DIR"] = _root,
            ["KOLDERA_CONCURRENCY"] = concurrency.ToString(),
            ["KOLDERA_QUEUE_CAPACITY"] = capacity.ToString(),
        };
        if (timeoutSeconds is { } t) env["KOLDERA_JOB_TIMEOUT_SECONDS"] = t.ToString();
        var options = CoreOptions.From(n => env.GetValueOrDefault(n));
        var queue = new JobQueue(capacity);
        var runner = new JobRunner(_store, queue, [executor], options, clock ?? TimeProvider.System, new LoggerOf(_log));
        return (runner, queue);
    }

    private async Task<Guid> QueueJob(JobQueue queue, string state = CoreJobState.Queued)
    {
        var job = new CoreJob { Id = Guid.NewGuid(), Kind = "pdf", Mode = "analyze", State = state, Owner = "server", CreatedAt = DateTimeOffset.UtcNow };
        await _store.SaveJobAsync(job);
        if (state == CoreJobState.Queued) Assert.True(queue.TryEnqueue(job.Id));
        return job.Id;
    }

    private async Task<(JobRunner Runner, Guid Id)> RunToEnd(IJobExecutor executor, int? timeoutSeconds = null)
    {
        var (runner, queue) = Runner(executor, timeoutSeconds);
        var id = await QueueJob(queue);
        await runner.StartAsync(CancellationToken.None);
        _started.Add(runner);
        await runner.WaitIdleAsync();
        return (runner, id);
    }

    [Fact]
    public async Task Running_jobs_left_from_a_previous_process_become_interrupted()
    {
        var (runner, queue) = Runner(Throwing(new InvalidOperationException()));
        var id = await QueueJob(queue, CoreJobState.Running);

        await runner.RecoverAsync();

        var job = _store.GetJob(id)!;
        Assert.Equal("failed", job.State);
        Assert.Equal("WORKER_INTERRUPTED", job.ErrorCode);
        Assert.NotNull(job.FinishedAt);
    }

    [Fact]
    public async Task Queued_jobs_left_from_a_previous_process_are_run_after_recovery()
    {
        var (runner, _) = Runner(new LambdaExecutor("pdf", (_, _, _, _) => Task.FromResult(Done())));
        var job = new CoreJob { Id = Guid.NewGuid(), Kind = "pdf", State = CoreJobState.Queued, Owner = "server", CreatedAt = DateTimeOffset.UtcNow };
        await _store.SaveJobAsync(job);

        await runner.StartAsync(CancellationToken.None);
        _started.Add(runner);
        Assert.True(await runner.WaitForCompletionAsync(job.Id, TimeSpan.FromSeconds(10), CancellationToken.None));

        Assert.Equal("completed", _store.GetJob(job.Id)!.State);
    }

    [Fact]
    public async Task Orphaned_scratch_directories_are_removed_on_recovery()
    {
        var (runner, _) = Runner(Throwing(new InvalidOperationException()));
        var orphan = Path.Combine(_root, "scratch", Guid.NewGuid().ToString("N"), "in");
        Directory.CreateDirectory(orphan);
        File.WriteAllText(Path.Combine(orphan, "x.pdf"), "x");

        await runner.RecoverAsync();

        Assert.Empty(Directory.EnumerateFileSystemEntries(Path.Combine(_root, "scratch")));
    }

    [Fact]
    public void Full_queue_refuses_new_work()
    {
        var q = new JobQueue(capacity: 1);
        Assert.True(q.TryEnqueue(Guid.NewGuid()));
        Assert.False(q.TryEnqueue(Guid.NewGuid()));
        Assert.Equal(1, q.Pending);
    }

    [Fact]
    public async Task Executor_exception_fails_the_job_without_leaking_the_message()
    {
        var (_, id) = await RunToEnd(Throwing(new IOException("/secret/path missing")));

        var job = _store.GetJob(id)!;
        Assert.Equal("failed", job.State);
        Assert.Equal("WORKER_ERROR", job.ErrorCode);
        Assert.Equal("the worker failed; see the server log", job.ErrorMessage);
        Assert.DoesNotContain("/secret", job.ErrorMessage);
        Assert.DoesNotContain(_log.Lines, l => l.Contains("/secret"));
        Assert.Contains(_log.Lines, l => l.Contains("IOException") && l.Contains(id.ToString()));
    }

    [Fact]
    public async Task Contract_violations_keep_their_message_and_timeouts_get_their_own_code()
    {
        var (_, contract) = await RunToEnd(Throwing(new WorkerContractException("artifact already exists")));
        Assert.Equal("WORKER_CONTRACT", _store.GetJob(contract)!.ErrorCode);
        Assert.Equal("artifact already exists", _store.GetJob(contract)!.ErrorMessage);

        var (_, timeout) = await RunToEnd(Throwing(new TimeoutException("took 99s in /secret")));
        Assert.Equal("WORKER_TIMEOUT", _store.GetJob(timeout)!.ErrorCode);
        Assert.DoesNotContain("/secret", _store.GetJob(timeout)!.ErrorMessage);
    }

    [Fact]
    public async Task A_missing_executor_for_the_kind_fails_the_job()
    {
        var (runner, queue) = Runner(new LambdaExecutor("web", (_, _, _, _) => Task.FromResult(Done())));
        var id = await QueueJob(queue);
        await runner.StartAsync(CancellationToken.None);
        _started.Add(runner);
        await runner.WaitIdleAsync();

        Assert.Equal("WORKER_ERROR", _store.GetJob(id)!.ErrorCode);
    }

    [Fact]
    public async Task The_outcome_is_applied_to_the_job_and_scratch_is_removed()
    {
        string? seenScratch = null;
        var executor = new LambdaExecutor("pdf", (_, scratch, _, _) =>
        {
            seenScratch = scratch;
            Assert.True(Directory.Exists(Path.Combine(scratch, "in")));
            Assert.True(Directory.Exists(Path.Combine(scratch, "out")));
            Assert.StartsWith(Path.Combine(_root, "scratch"), scratch);
            return Task.FromResult(Done());
        });

        var (_, id) = await RunToEnd(executor);

        var job = _store.GetJob(id)!;
        Assert.Equal("completed", job.State);
        Assert.Equal("ok", job.ResultStatus);
        Assert.Equal("none", job.RemediationStatus);
        Assert.Equal("engine-1", job.EngineVersion);
        Assert.Equal("{\"b\":1}", job.BaselineValidationJson);
        Assert.Equal("{\"f\":1}", job.FinalValidationJson);
        Assert.Equal("R1", Assert.Single(job.Findings).RuleId);
        Assert.Equal(3, job.Pages);
        Assert.NotNull(job.StartedAt);
        Assert.NotNull(job.FinishedAt);
        Assert.False(Directory.Exists(seenScratch));
    }

    [Fact]
    public async Task The_configured_job_timeout_is_passed_to_the_executor_else_infinite()
    {
        TimeSpan? seen = null;
        IJobExecutor Capture() => new LambdaExecutor("pdf", (_, _, t, _) => { seen = t; return Task.FromResult(Done()); });

        await RunToEnd(Capture(), timeoutSeconds: 90);
        Assert.Equal(TimeSpan.FromSeconds(90), seen);

        seen = null;
        await RunToEnd(Capture());
        Assert.Equal(Timeout.InfiniteTimeSpan, seen);
    }

    [Fact]
    public async Task Waiting_for_completion_returns_true_when_the_job_finishes_and_false_when_it_does_not()
    {
        var gate = new TaskCompletionSource();
        var (runner, queue) = Runner(new LambdaExecutor("pdf", async (_, _, _, _) => { await gate.Task; return Done(); }));
        var id = await QueueJob(queue);
        await runner.StartAsync(CancellationToken.None);
        _started.Add(runner);

        Assert.False(await runner.WaitForCompletionAsync(id, TimeSpan.FromMilliseconds(150), CancellationToken.None));

        var waiting = runner.WaitForCompletionAsync(id, TimeSpan.FromSeconds(30), CancellationToken.None);
        gate.SetResult();
        Assert.True(await waiting);
        // An already finished job and an unknown job answer immediately.
        Assert.True(await runner.WaitForCompletionAsync(id, TimeSpan.Zero, CancellationToken.None));
        Assert.False(await runner.WaitForCompletionAsync(Guid.NewGuid(), TimeSpan.FromSeconds(5), CancellationToken.None));
    }
}
