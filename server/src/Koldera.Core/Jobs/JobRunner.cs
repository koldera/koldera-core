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

    /// <summary>
    /// The configured job timeout, else no timeout at all. It is one deadline for the whole job: the token handed to the
    /// executor is cancelled when it passes, so every step (worker process, probe, file work) only gets what is left.
    /// </summary>
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

    /// <summary>
    /// Waits until the job is completed or failed; false when it is unknown, still unfinished after <paramref name="max"/>,
    /// or its run ended without a stored completed or failed record.
    /// </summary>
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
        }
        catch (TimeoutException)
        {
            return false;
        }
        // The runner wakes waiters on every exit of a run, also when the outcome could not be stored.
        try
        {
            return IsTerminal(_store.GetJob(id));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
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
            // Whatever happened to the record (even an unreadable one), a waiter must never be left hanging.
            Signal(id);
        }
    }

    private async Task RunClaimedAsync(Guid id, CancellationToken stop)
    {
        var job = _store.GetJob(id);
        if (job is null || job.State != CoreJobState.Queued) return;

        job.State = CoreJobState.Running;
        job.StartedAt = _clock.GetUtcNow();
        if (!await TryMarkRunningAsync(job, stop)) return;

        var scratch = Path.Combine(ScratchRoot, id.ToString("N"));
        var timeout = JobTimeout;
        using var deadline = new CancellationTokenSource(timeout, _clock);
        using var run = CancellationTokenSource.CreateLinkedTokenSource(stop, deadline.Token);
        try
        {
            Directory.CreateDirectory(Path.Combine(scratch, "in"));
            Directory.CreateDirectory(Path.Combine(scratch, "out"));
            if (!_executors.TryGetValue(job.Kind, out var executor))
                throw new InvalidOperationException("no executor for job kind");
            Apply(job, await executor.ExecuteAsync(job, scratch, timeout, run.Token));
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e) when (e is TimeoutException || e is OperationCanceledException && deadline.IsCancellationRequested)
        {
            Fail(job, "WORKER_TIMEOUT", "the worker exceeded the job time limit");
        }
        catch (WorkerContractException e)
        {
            // Clients get the redacted message; the log gets the located detail (pointers and property names, never values).
            _logger.LogWarning("job {JobId} violated the worker contract: {Detail}", id, e.LogDetail);
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
        await _store.SaveJobAsync(job);
    }

    /// <summary>
    /// Persists the running state, retrying once like the store's reads (a replace can be refused for a moment).
    /// If it still cannot be saved the job is ended as failed when that can be recorded; false means the job was ended and must not run.
    /// </summary>
    private async Task<bool> TryMarkRunningAsync(CoreJob job, CancellationToken stop)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                await _store.SaveJobAsync(job);
                return true;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException && attempt == 0)
            {
                await Task.Delay(20, stop);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                _logger.LogError("job {JobId} could not be marked running: {ExceptionType}", job.Id, e.GetType().Name);
                Fail(job, "WORKER_ERROR", GenericFailureMessage);
                job.FinishedAt = _clock.GetUtcNow();
                try
                {
                    await _store.SaveJobAsync(job);
                }
                catch (Exception inner) when (inner is not OperationCanceledException)
                {
                    _logger.LogError("job {JobId} could not be recorded as failed: {ExceptionType}", job.Id, inner.GetType().Name);
                }
                return false;
            }
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
