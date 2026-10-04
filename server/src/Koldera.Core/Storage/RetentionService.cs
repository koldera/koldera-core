using Microsoft.Extensions.Hosting;

namespace Koldera.Core;

/// <summary>Deletes finished jobs and uploads whose creation is older than the retention window.</summary>
public sealed class RetentionService(DataStore store, int retentionHours)
{
    public Task SweepAsync(DateTimeOffset now)
    {
        var cutoff = now - TimeSpan.FromHours(retentionHours);
        foreach (var job in store.AllJobs().ToList())
            // A queued or running job is still needed by the runner, whatever its age.
            if (job.CreatedAt < cutoff && job.State is CoreJobState.Completed or CoreJobState.Failed)
                store.DeleteJob(job.Id);
        foreach (var upload in store.AllUploads().ToList())
            if (upload.CreatedAt < cutoff)
                store.DeleteUpload(upload.Id);
        return Task.CompletedTask;
    }
}

/// <summary>Runs the retention sweep at start and then every 15 minutes on the injected clock.</summary>
public sealed class RetentionWorker(RetentionService retention, TimeProvider clock) : BackgroundService
{
    public static readonly TimeSpan Period = TimeSpan.FromMinutes(15);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await retention.SweepAsync(clock.GetUtcNow());
        using var timer = new PeriodicTimer(Period, clock);
        while (await timer.WaitForNextTickAsync(stoppingToken))
            await retention.SweepAsync(clock.GetUtcNow());
    }
}
