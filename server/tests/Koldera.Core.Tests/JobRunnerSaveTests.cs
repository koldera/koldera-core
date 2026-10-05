using Koldera.Core.Tests.Support;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Koldera.Core.Tests;

/// <summary>A store whose job saves fail on demand, like a Windows file replace that is briefly refused.</summary>
public sealed class FlakyStore(string root, TimeProvider clock) : DataStore(root, clock)
{
    private int _failures;
    private int _skip;

    /// <summary>The next <paramref name="count"/> saves succeed before <see cref="FailNextSaves"/> takes effect.</summary>
    public void SucceedNextSaves(int count) => Volatile.Write(ref _skip, count);

    /// <summary>The next <paramref name="count"/> saves throw; <see cref="int.MaxValue"/> means every save.</summary>
    public void FailNextSaves(int count) => Volatile.Write(ref _failures, count);

    public override Task SaveJobAsync(CoreJob job)
    {
        if (Volatile.Read(ref _skip) > 0)
        {
            Interlocked.Decrement(ref _skip);
            return base.SaveJobAsync(job);
        }
        if (Volatile.Read(ref _failures) > 0)
        {
            if (_failures != int.MaxValue) Interlocked.Decrement(ref _failures);
            throw new IOException("simulated save failure");
        }
        return base.SaveJobAsync(job);
    }
}

public class JobRunnerSaveTests
{
    private static CoreFactory Factory() =>
        CoreFactory.WithStubWorkers(StubResults.PdfAnalyzeCompleted(), configure: s =>
        {
            s.RemoveAll<DataStore>();
            s.AddSingleton<DataStore>(sp => new FlakyStore(sp.GetRequiredService<CoreOptions>().DataDir, sp.GetRequiredService<TimeProvider>()));
        });

    /// <summary>A queued job on a stored upload; saves are armed to fail only after the record exists, then the job is enqueued.</summary>
    private static async Task<Guid> QueueJob(CoreFactory f, int failingSaves, int succeedingSavesFirst = 0, Func<Guid, Task>? beforeEnqueue = null)
    {
        var pdf = Fixtures.SmallPdf();
        var uploadId = Guid.NewGuid();
        var now = f.Clock.GetUtcNow();
        f.Store.CreateUpload(new UploadRecord(uploadId, "report.pdf", pdf.Length, Fixtures.Sha256(pdf), "application/pdf", "server", now, now.AddHours(1), true));
        await File.WriteAllBytesAsync(f.Store.UploadPath(uploadId), pdf);
        var id = Guid.NewGuid();
        await f.Store.SaveJobAsync(new CoreJob { Id = id, Kind = CoreJobKind.Pdf, Mode = "analyze", Profile = "ua1", InputUploadId = uploadId, Owner = "server", CreatedAt = now });
        ((FlakyStore)f.Store).SucceedNextSaves(succeedingSavesFirst);
        ((FlakyStore)f.Store).FailNextSaves(failingSaves);
        if (beforeEnqueue is not null) await beforeEnqueue(id);
        Assert.True(f.Services.GetRequiredService<JobQueue>().TryEnqueue(id));
        return id;
    }

    [Fact]
    public async Task One_refused_save_when_the_job_starts_is_retried_and_the_job_completes()
    {
        using var f = Factory();
        var id = await QueueJob(f, failingSaves: 1);
        var job = await f.WaitForJob(id);
        Assert.Equal("completed", job.GetProperty("state").GetString());
    }

    [Fact]
    public async Task A_start_save_that_fails_twice_ends_the_job_as_failed_with_the_generic_message()
    {
        using var f = Factory();
        var id = await QueueJob(f, failingSaves: 2);
        var job = await f.WaitForJob(id);
        Assert.Equal("failed", job.GetProperty("state").GetString());
        Assert.Equal("WORKER_ERROR", job.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(JobRunner.GenericFailureMessage, job.GetProperty("error").GetProperty("message").GetString());
    }

    [Fact]
    public async Task When_nothing_can_be_saved_the_waiter_is_released_with_false()
    {
        using var f = Factory();
        var id = await QueueJob(f, failingSaves: int.MaxValue);
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        // Released by the runner's signal, not by the 10 s guard (that would throw), and not reported finished.
        Assert.False(await f.Services.GetRequiredService<JobRunner>().WaitForCompletionAsync(id, TimeSpan.FromSeconds(10), limit.Token));
        ((FlakyStore)f.Store).FailNextSaves(0);
        Assert.NotEqual(CoreJobState.Running, f.Store.GetJob(id)!.State);
    }

    [Fact]
    public async Task When_only_the_final_save_fails_the_waiter_is_still_released_and_the_record_stays_running()
    {
        using var f = Factory();
        // The start save succeeds, the worker runs, then every save (the outcome) fails.
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        // The waiter registers before the job is enqueued: the runner's signal is only delivered to a registered waiter,
        // and a fast worker could otherwise finish before the waiter exists (the clock here is fake, so max never elapses).
        Task<bool>? wait = null;
        var id = await QueueJob(f, failingSaves: int.MaxValue, succeedingSavesFirst: 1,
            beforeEnqueue: i => { wait = f.Services.GetRequiredService<JobRunner>().WaitForCompletionAsync(i, TimeSpan.FromSeconds(10), limit.Token); return Task.CompletedTask; });
        // Released by the runner's signal (a hang would end in OperationCanceledException), and not reported finished.
        Assert.False(await wait!);
        ((FlakyStore)f.Store).FailNextSaves(0);
        Assert.Equal(CoreJobState.Running, f.Store.GetJob(id)!.State);
    }
}
