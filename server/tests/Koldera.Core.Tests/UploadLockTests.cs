using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public sealed class UploadLockTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "koldera-lock-" + Guid.NewGuid().ToString("N"));
    private readonly DataStore _store;

    public UploadLockTests() => _store = new DataStore(_root);

    public void Dispose() => Directory.Delete(_root, true);

    private Guid Declare(byte[] declared, string? sha = null)
    {
        var id = Guid.NewGuid();
        var now = DateTimeOffset.UtcNow;
        _store.CreateUpload(new UploadRecord(id, "a.pdf", declared.Length, sha ?? Fixtures.Sha256(declared), "application/pdf", "server", now, now.AddHours(1), false));
        return id;
    }

    [Fact]
    public async Task A_failed_put_leaves_no_lock_entry_behind()
    {
        var bytes = Fixtures.SmallPdf();
        var wrongHash = Declare(bytes, new string('0', 64));
        await Assert.ThrowsAsync<UploadException>(() => _store.CompleteUploadAsync(wrongHash, new MemoryStream(bytes), default));
        var tooLong = Declare(bytes);
        await Assert.ThrowsAsync<UploadException>(() => _store.CompleteUploadAsync(tooLong, new MemoryStream([.. bytes, 1]), default));
        var cancelled = Declare(bytes);
        using var cts = new CancellationTokenSource();
        await cts.CancelAsync();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => _store.CompleteUploadAsync(cancelled, new MemoryStream(bytes), cts.Token));

        Assert.Equal(0, _store.LockCount);
    }

    [Fact]
    public async Task A_successful_put_and_an_unknown_upload_leave_no_lock_entry_either()
    {
        var bytes = Fixtures.SmallPdf();
        await _store.CompleteUploadAsync(Declare(bytes), new MemoryStream(bytes), default);
        await Assert.ThrowsAsync<UploadException>(() => _store.CompleteUploadAsync(Guid.NewGuid(), new MemoryStream(bytes), default));
        Assert.Equal(0, _store.LockCount);
    }

    [Fact]
    public async Task A_retry_after_a_failed_put_still_completes()
    {
        var bytes = Fixtures.SmallPdf();
        var id = Declare(bytes);
        await Assert.ThrowsAsync<UploadException>(() => _store.CompleteUploadAsync(id, new MemoryStream([.. bytes, 1]), default));
        await _store.CompleteUploadAsync(id, new MemoryStream(bytes), default);
        Assert.True(_store.GetUpload(id)!.Completed);
        Assert.Equal(0, _store.LockCount);
    }
}
