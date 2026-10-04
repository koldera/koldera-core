using Microsoft.Extensions.Time.Testing;

namespace Koldera.Core.Tests;

public sealed class RetentionTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "koldera-retention-" + Guid.NewGuid().ToString("N"));
    private readonly DataStore _store;
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-05T12:00:00Z");

    public RetentionTests()
    {
        Directory.CreateDirectory(_root);
        _store = new DataStore(_root);
    }

    public void Dispose() => Directory.Delete(_root, true);

    private string JobPath(Guid id) => Path.Combine(_root, "jobs", id.ToString("N"));

    private async Task<Guid> Job(string state, TimeSpan age)
    {
        var job = new CoreJob { Id = Guid.NewGuid(), State = state, Owner = "server", CreatedAt = Now - age };
        await _store.SaveJobAsync(job);
        File.WriteAllText(Path.Combine(JobPath(job.Id), "artifact.bin"), "x");
        return job.Id;
    }

    private Guid Upload(TimeSpan age)
    {
        var id = Guid.NewGuid();
        _store.CreateUpload(new UploadRecord(id, "a.pdf", 1, "00", "application/pdf", "server", Now - age, Now - age + TimeSpan.FromMinutes(10), true));
        File.WriteAllText(_store.UploadPath(id), "x");
        return id;
    }

    [Fact]
    public async Task Retention_deletes_old_jobs_and_uploads()
    {
        var old = await Job(CoreJobState.Completed, TimeSpan.FromHours(25));
        var oldUpload = Upload(TimeSpan.FromHours(25));

        await new RetentionService(_store, retentionHours: 24).SweepAsync(Now);

        Assert.Null(_store.GetJob(old));
        Assert.False(Directory.Exists(JobPath(old)));
        Assert.Null(_store.GetUpload(oldUpload));
        Assert.False(File.Exists(_store.UploadPath(oldUpload)));
    }

    [Fact]
    public async Task Recent_jobs_and_uploads_and_unfinished_jobs_are_kept()
    {
        var recent = await Job(CoreJobState.Completed, TimeSpan.FromHours(23));
        var recentUpload = Upload(TimeSpan.FromHours(1));
        var runningOld = await Job(CoreJobState.Running, TimeSpan.FromHours(48));

        await new RetentionService(_store, retentionHours: 24).SweepAsync(Now);

        Assert.NotNull(_store.GetJob(recent));
        Assert.NotNull(_store.GetUpload(recentUpload));
        Assert.NotNull(_store.GetJob(runningOld));
    }

    [Fact]
    public async Task The_background_worker_sweeps_every_fifteen_minutes_on_the_injected_clock()
    {
        var clock = new FakeTimeProvider(Now);
        var job = await Job(CoreJobState.Failed, TimeSpan.FromHours(23) + TimeSpan.FromMinutes(50));
        var worker = new RetentionWorker(new RetentionService(_store, 24), clock);
        await worker.StartAsync(CancellationToken.None);
        try
        {
            Assert.NotNull(_store.GetJob(job));

            // The timer may not exist yet when the clock first moves; keep advancing until a tick lands.
            for (var i = 0; i < 200 && _store.GetJob(job) is not null; i++)
            {
                clock.Advance(RetentionWorker.Period);
                await Task.Delay(25);
            }

            Assert.Null(_store.GetJob(job));
        }
        finally
        {
            await worker.StopAsync(CancellationToken.None);
        }
    }
}
