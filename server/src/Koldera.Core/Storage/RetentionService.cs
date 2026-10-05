using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Koldera.Core;

/// <summary>Deletes finished jobs and uploads whose creation is older than the retention window.</summary>
public sealed class RetentionService(DataStore store, int retentionHours, ILogger? log = null)
{
    public Task SweepAsync(DateTimeOffset now)
    {
        var cutoff = now - TimeSpan.FromHours(retentionHours);
        void Corrupt(string file, Exception e) =>
            log?.LogWarning("retention skipped an unreadable record {File}: {ExceptionType}", file, e.GetType().Name);

        var jobs = store.AllJobs(Corrupt).ToList();
        // A queued or running job still needs its input upload and itself, whatever their age.
        var inUse = jobs.Where(j => j.State is CoreJobState.Queued or CoreJobState.Running && j.InputUploadId is not null)
            .Select(j => j.InputUploadId!.Value).ToHashSet();
        foreach (var job in jobs)
            if (job.CreatedAt < cutoff && job.State is CoreJobState.Completed or CoreJobState.Failed)
                store.DeleteJob(job.Id);
        // Not yet visible as a job, so only a stale one is an orphan; a corrupt record is not (it is skipped above).
        foreach (var (id, lastWrite) in store.OrphanJobDirs().ToList())
            if (lastWrite < cutoff)
                store.DeleteJob(id);
        foreach (var upload in store.AllUploads(Corrupt).ToList())
            if (upload.CreatedAt < cutoff && !inUse.Contains(upload.Id))
                store.DeleteUpload(upload.Id);
        return Task.CompletedTask;
    }
}

/// <summary>Runs the retention sweep at start and then every 15 minutes on the injected clock.</summary>
public sealed class RetentionWorker(RetentionService retention, TimeProvider clock, ILogger? log = null) : BackgroundService
{
    public static readonly TimeSpan Period = TimeSpan.FromMinutes(15);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await Sweep();
        using var timer = new PeriodicTimer(Period, clock);
        while (await timer.WaitForNextTickAsync(stoppingToken))
            await Sweep();
    }

    private async Task Sweep()
    {
        try
        {
            await retention.SweepAsync(clock.GetUtcNow());
        }
        catch (Exception e)
        {
            // An unhandled exception would stop the host; the next period tries again.
            log?.LogError("retention sweep failed: {ExceptionType}", e.GetType().Name);
        }
    }
}
