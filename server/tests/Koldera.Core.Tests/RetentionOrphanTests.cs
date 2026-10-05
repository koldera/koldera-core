namespace Koldera.Core.Tests;

/// <summary>A crash between writing a job's files and saving its record leaves a directory without job.json.</summary>
public sealed class RetentionOrphanTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "koldera-orphan-" + Guid.NewGuid().ToString("N"));
    private readonly DataStore _store;
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-05T12:00:00Z");

    public RetentionOrphanTests() => _store = new DataStore(_root);

    public void Dispose() => Directory.Delete(_root, true);

    private string Orphan(TimeSpan age, bool withTmpRecord = false)
    {
        var dir = Path.Combine(_root, "jobs", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path.Combine(dir, "out"));
        File.WriteAllText(Path.Combine(dir, "out", "stored.pdf"), "x");
        if (withTmpRecord) File.WriteAllText(Path.Combine(dir, "job.json.tmp"), "{");
        Directory.SetLastWriteTimeUtc(dir, (Now - age).UtcDateTime);
        return dir;
    }

    [Fact]
    public async Task An_old_directory_without_a_record_is_removed_and_a_recent_one_is_kept()
    {
        var old = Orphan(TimeSpan.FromHours(25));
        var oldWithTmp = Orphan(TimeSpan.FromHours(30), withTmpRecord: true);
        var recent = Orphan(TimeSpan.FromHours(1));

        await new RetentionService(_store, 24).SweepAsync(Now);

        Assert.False(Directory.Exists(old));
        Assert.False(Directory.Exists(oldWithTmp));
        Assert.True(Directory.Exists(recent));
    }

    [Fact]
    public async Task A_directory_with_a_record_is_judged_by_the_record_not_by_the_directory_age()
    {
        var job = new CoreJob { Id = Guid.NewGuid(), State = CoreJobState.Running, Owner = "server", CreatedAt = Now - TimeSpan.FromHours(48) };
        await _store.SaveJobAsync(job);
        var dir = Path.Combine(_root, "jobs", job.Id.ToString("N"));
        Directory.SetLastWriteTimeUtc(dir, (Now - TimeSpan.FromHours(48)).UtcDateTime);

        await new RetentionService(_store, 24).SweepAsync(Now);

        Assert.NotNull(_store.GetJob(job.Id));
    }
}
