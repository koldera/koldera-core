using System.Collections.Concurrent;
using Koldera.Core.Workers;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Koldera.Core;

/// <summary>Runs queued jobs on <see cref="CoreOptions.Concurrency"/> loops and persists their outcome.</summary>
public sealed class JobRunner : BackgroundService
{
    public const string GenericFailureMessage = "the worker failed; see the server log";

    private readonly DataStore _store;
    private readonly JobQueue _queue;
    private readonly IReadOnlyDictionary<string, IJobExecutor> _executors;
    private readonly CoreOptions _options;
    private readonly TimeProvider _clock;
    private readonly ILogger<JobRunner> _logger;
    private readonly ConcurrentDictionary<Guid, TaskCompletionSource> _completions = new();
    private readonly ConcurrentDictionary<Guid, byte> _claimed = new();

    public JobRunner(DataStore store, JobQueue queue, IEnumerable<IJobExecutor> executors, CoreOptions options, TimeProvider clock, ILogger<JobRunner> logger)
    {
        _store = store;
        _queue = queue;
        _executors = executors.ToDictionary(e => e.Kind);
        _options = options;
        _clock = clock;
        _logger = logger;
    }

    private string ScratchRoot => Path.Combine(_options.DataDir, "scratch");

    /// <summary>The configured job timeout, else no timeout at all; handed to every executor.</summary>
    private TimeSpan JobTimeout =>
        _options.Limits.JobTimeoutSeconds is { } s ? TimeSpan.FromSeconds(s) : Timeout.InfiniteTimeSpan;

    /// <summary>Marks jobs a previous process left running as interrupted, re-enqueues queued ones and removes orphaned scratch directories.</summary>
    public async Task RecoverAsync()
    {
        if (Directory.Exists(ScratchRoot))
            foreach (var dir in Directory.EnumerateDirectories(ScratchRoot))
                TryDeleteDirectory(dir);

        foreach (var job in _store.AllJobs().OrderBy(j => j.CreatedAt).ToList())
        {
            if (job.State == CoreJobState.Running)
            {
                await Interrupt(job, "the server stopped while this job was running");
            }
            else if (job.State == CoreJobState.Queued && !_queue.TryEnqueue(job.Id))
            {
                await Interrupt(job, "the server restarted and its queue was full");
            }
        }
    }

    private async Task Interrupt(CoreJob job, string message)
    {
        job.State = CoreJobState.Failed;
        job.ErrorCode = "WORKER_INTERRUPTED";
        job.ErrorMessage = message;
        job.FinishedAt = _clock.GetUtcNow();
        await _store.SaveJobAsync(job);
    }

    /// <summary>Waits until the job is completed or failed; false when it is unknown or still unfinished after <paramref name="max"/>.</summary>
    public async Task<bool> WaitForCompletionAsync(Guid id, TimeSpan max, CancellationToken ct)
    {
        if (IsTerminal(_store.GetJob(id))) return true;
        var tcs = _completions.GetOrAdd(id, _ => new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously));
        // The runner stores the job before it signals; re-check so a finish between the two reads is not missed.
        var job = _store.GetJob(id);
        if (job is null)
        {
            _completions.TryRemove(id, out _);
            return false;
        }
        if (IsTerminal(job))
        {
            // Other waiters share this source: wake them before dropping it, or the runner's signal finds nothing.
            tcs.TrySetResult();
            _completions.TryRemove(id, out _);
            return true;
        }
        try
        {
            await tcs.Task.WaitAsync(max, _clock, ct);
            return true;
        }
        catch (TimeoutException)
        {
            return false;
        }
    }

    /// <summary>Waits until every enqueued job has finished. Intended for tests.</summary>
    public async Task WaitIdleAsync(CancellationToken ct = default)
    {
        while (_queue.Pending > 0) await Task.Delay(10, ct);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await RecoverAsync();
        var loops = Enumerable.Range(0, Math.Max(1, _options.Concurrency)).Select(_ => Loop(stoppingToken)).ToArray();
        await Task.WhenAll(loops);
    }

    private async Task Loop(CancellationToken stop)
    {
        try
        {
            await foreach (var id in _queue.Reader.ReadAllAsync(stop))
            {
                try
                {
                    await RunOneAsync(id, stop);
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested)
                {
                    return; // the job stays "running" on disk and is reported interrupted at the next start
                }
                catch (Exception e)
                {
                    _logger.LogError("job {JobId} could not be recorded: {ExceptionType}", id, e.GetType().Name);
                }
                finally
                {
                    _queue.MarkDone();
                }
            }
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
    }

    private async Task RunOneAsync(Guid id, CancellationToken stop)
    {
        // One run per id: a duplicate queue entry is skipped while the first is claimed or after it finished.
        if (!_claimed.TryAdd(id, 0)) return;
        try
        {
            await RunClaimedAsync(id, stop);
        }
        finally
        {
            _claimed.TryRemove(id, out _);
        }
    }

    private async Task RunClaimedAsync(Guid id, CancellationToken stop)
    {
        var job = _store.GetJob(id);
        if (job is null || job.State != CoreJobState.Queued) return;

        job.State = CoreJobState.Running;
        job.StartedAt = _clock.GetUtcNow();
        await _store.SaveJobAsync(job);

        var scratch = Path.Combine(ScratchRoot, id.ToString("N"));
        try
        {
            Directory.CreateDirectory(Path.Combine(scratch, "in"));
            Directory.CreateDirectory(Path.Combine(scratch, "out"));
            if (!_executors.TryGetValue(job.Kind, out var executor))
                throw new InvalidOperationException("no executor for job kind");
            Apply(job, await executor.ExecuteAsync(job, scratch, JobTimeout, stop));
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested)
        {
            throw;
        }
        catch (TimeoutException)
        {
            Fail(job, "WORKER_TIMEOUT", "the worker exceeded the job time limit");
        }
        catch (WorkerContractException e)
        {
            Fail(job, "WORKER_CONTRACT", e.Message);
        }
        catch (Exception e)
        {
            // Only the type is logged: the message may carry paths or document content.
            _logger.LogError("job {JobId} failed: {ExceptionType}", id, e.GetType().Name);
            Fail(job, "WORKER_ERROR", GenericFailureMessage);
        }
        finally
        {
            TryDeleteDirectory(scratch);
        }

        job.FinishedAt = _clock.GetUtcNow();
        try
        {
            await _store.SaveJobAsync(job);
        }
        finally
        {
            Signal(id);
        }
    }

    private static void Fail(CoreJob job, string code, string message)
    {
        job.State = CoreJobState.Failed;
        job.ErrorCode = code;
        job.ErrorMessage = message;
    }

    private static void Apply(CoreJob job, JobOutcome o)
    {
        job.State = o.State;
        job.ResultStatus = o.ResultStatus;
        job.ErrorCode = o.ErrorCode;
        job.ErrorMessage = o.ErrorMessage;
        job.RemediationStatus = o.RemediationStatus;
        job.EngineVersion = o.EngineVersion;
        job.BaselineValidationJson = o.BaselineValidationJson;
        job.FinalValidationJson = o.FinalValidationJson;
        job.Findings = [.. o.Findings];
        job.Actions = [.. o.Actions];
        job.Artifacts = [.. o.Artifacts];
        job.Snapshots = o.Snapshots is null ? [] : [.. o.Snapshots];
        job.Pages = o.Pages;
    }

    private void Signal(Guid id)
    {
        if (_completions.TryRemove(id, out var tcs)) tcs.TrySetResult();
    }

    private static bool IsTerminal(CoreJob? job) => job is { State: CoreJobState.Completed or CoreJobState.Failed };

    private static void TryDeleteDirectory(string dir)
    {
        try
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }
}
