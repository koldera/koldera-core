using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text.Json;

namespace Koldera.Core;

public sealed record UploadRecord(
    Guid Id, string FileName, long SizeBytes, string Sha256, string ContentType, string Owner,
    DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt, bool Completed);

/// <summary>Minimal persisted job; later tasks extend it.</summary>
public sealed record CoreJob(Guid Id, string Owner, string Status, DateTimeOffset CreatedAt);

public sealed class UploadException(string code) : Exception(code)
{
    public string Code { get; } = code;
}

/// <summary>File-based store under the data directory. Every write goes to "file.tmp" and is moved into place.</summary>
public sealed class DataStore
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private readonly string _root;
    private readonly TimeProvider _clock;
    private readonly ConcurrentDictionary<Guid, SemaphoreSlim> _locks = new();

    public DataStore(string root, TimeProvider? clock = null)
    {
        _root = root;
        _clock = clock ?? TimeProvider.System;
        Directory.CreateDirectory(UploadsDir);
        Directory.CreateDirectory(JobsDir);
    }

    private string UploadsDir => Path.Combine(_root, "uploads");
    private string JobsDir => Path.Combine(_root, "jobs");
    private string RecordPath(Guid id) => Path.Combine(UploadsDir, id.ToString("N") + ".json");

    public string UploadPath(Guid id) => Path.Combine(UploadsDir, id.ToString("N"));

    public string JobDir(Guid id)
    {
        var dir = Path.Combine(JobsDir, id.ToString("N"));
        Directory.CreateDirectory(dir);
        return dir;
    }

    public UploadRecord CreateUpload(UploadRecord r)
    {
        WriteJson(RecordPath(r.Id), r);
        return r;
    }

    public UploadRecord? GetUpload(Guid id) => ReadJson<UploadRecord>(RecordPath(id));

    public async Task CompleteUploadAsync(Guid id, Stream body, CancellationToken ct)
    {
        var gate = _locks.GetOrAdd(id, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(ct);
        try
        {
            var rec = GetUpload(id) ?? throw new UploadException("UPLOAD_NOT_FOUND");
            if (rec.Completed) throw new UploadException("UPLOAD_ALREADY_COMPLETED");
            if (_clock.GetUtcNow() > rec.ExpiresAt) throw new UploadException("UPLOAD_EXPIRED");

            var part = UploadPath(id) + ".part";
            try
            {
                long total = 0;
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                await using (var fs = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true))
                {
                    var buffer = new byte[81920];
                    int n;
                    while ((n = await body.ReadAsync(buffer, ct)) > 0)
                    {
                        total += n;
                        // The declaration bounds the stream: stop once it is exceeded.
                        if (total > rec.SizeBytes) throw new UploadException("SIZE_MISMATCH");
                        hash.AppendData(buffer, 0, n);
                        await fs.WriteAsync(buffer.AsMemory(0, n), ct);
                    }
                }
                if (total != rec.SizeBytes) throw new UploadException("SIZE_MISMATCH");
                if (!string.Equals(Convert.ToHexString(hash.GetHashAndReset()), rec.Sha256, StringComparison.OrdinalIgnoreCase))
                    throw new UploadException("CHECKSUM_MISMATCH");
                File.Move(part, UploadPath(id), overwrite: true);
            }
            catch
            {
                try { File.Delete(part); } catch (IOException) { }
                throw;
            }
            WriteJson(RecordPath(id), rec with { Completed = true });
        }
        finally { gate.Release(); }
    }

    public async Task SaveJobAsync(CoreJob job)
    {
        var path = Path.Combine(JobDir(job.Id), "job.json");
        var tmp = path + ".tmp";
        await File.WriteAllBytesAsync(tmp, JsonSerializer.SerializeToUtf8Bytes(job, Json));
        File.Move(tmp, path, overwrite: true);
    }

    public CoreJob? GetJob(Guid id) => ReadJson<CoreJob>(Path.Combine(JobsDir, id.ToString("N"), "job.json"));

    public IEnumerable<CoreJob> AllJobs()
    {
        foreach (var dir in Directory.EnumerateDirectories(JobsDir))
            if (ReadJson<CoreJob>(Path.Combine(dir, "job.json")) is { } job) yield return job;
    }

    private static void WriteJson<T>(string path, T value)
    {
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, JsonSerializer.SerializeToUtf8Bytes(value, Json));
        File.Move(tmp, path, overwrite: true);
    }

    private static T? ReadJson<T>(string path) where T : class =>
        File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllBytes(path), Json) : null;
}
