using Koldera.Core.Tests.Support;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Koldera.Core.Tests;

/// <summary>A store whose job saves fail on demand, like a Windows file replace that is briefly refused.</summary>
public sealed class FlakyStore(string root, TimeProvider clock) : DataStore(root, clock)
{
    private int _failures;

    /// <summary>The next <paramref name="count"/> saves throw; <see cref="int.MaxValue"/> means every save.</summary>
    public void FailNextSaves(int count) => Volatile.Write(ref _failures, count);

    public override Task SaveJobAsync(CoreJob job)
    {
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
    private static async Task<Guid> QueueJob(CoreFactory f, int failingSaves)
    {
        var pdf = Fixtures.SmallPdf();
        var uploadId = Guid.NewGuid();
        var now = f.Clock.GetUtcNow();
        f.Store.CreateUpload(new UploadRecord(uploadId, "report.pdf", pdf.Length, Fixtures.Sha256(pdf), "application/pdf", "server", now, now.AddHours(1), true));
        await File.WriteAllBytesAsync(f.Store.UploadPath(uploadId), pdf);
        var id = Guid.NewGuid();
        await f.Store.SaveJobAsync(new CoreJob { Id = id, Kind = CoreJobKind.Pdf, Mode = "analyze", Profile = "ua1", InputUploadId = uploadId, Owner = "server", CreatedAt = now });
        ((FlakyStore)f.Store).FailNextSaves(failingSaves);
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
    public async Task When_nothing_can_be_saved_the_waiter_is_still_released()
    {
        using var f = Factory();
        var id = await QueueJob(f, failingSaves: int.MaxValue);
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        // Released by the runner's signal, not by the 10 s guard.
        Assert.True(await f.Services.GetRequiredService<JobRunner>().WaitForCompletionAsync(id, TimeSpan.FromSeconds(10), limit.Token));
        ((FlakyStore)f.Store).FailNextSaves(0);
        Assert.NotEqual(CoreJobState.Running, f.Store.GetJob(id)!.State);
    }
}
