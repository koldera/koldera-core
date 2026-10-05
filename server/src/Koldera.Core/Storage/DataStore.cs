using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text.Json;

namespace Koldera.Core;

public sealed record UploadRecord(
    Guid Id, string FileName, long SizeBytes, string Sha256, string ContentType, string Owner,
    DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt, bool Completed);

public sealed class UploadException(string code) : Exception(code)
{
    public string Code { get; } = code;
}

/// <summary>File-based store under the data directory. Every write goes to "file.tmp" and is moved into place.</summary>
public class DataStore
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
        var terminal = false;
        try
        {
            UploadRecord rec;
            try
            {
                rec = GetUpload(id) ?? throw new UploadException("UPLOAD_NOT_FOUND");
                if (rec.Completed) throw new UploadException("UPLOAD_ALREADY_COMPLETED");
                if (_clock.GetUtcNow() > rec.ExpiresAt) throw new UploadException("UPLOAD_EXPIRED");
            }
            catch (UploadException)
            {
                terminal = true;
                throw;
            }

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
                try
                {
                    File.Move(part, UploadPath(id), overwrite: false);
                }
                catch (IOException) when (File.Exists(UploadPath(id)))
                {
                    // A previous attempt moved the file but did not record it. Accept only if it is exactly the declared content.
                    if (!MatchesRecord(UploadPath(id), rec)) throw new UploadException("UPLOAD_CONFLICT");
                }
            }
            catch
            {
                try { File.Delete(part); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
                throw;
            }
            WriteJson(RecordPath(id), rec with { Completed = true });
            terminal = true;
        }
        finally
        {
            gate.Release();
            // Completed (or unknown/expired) is terminal; later callers read the record and never need this gate.
            if (terminal) _locks.TryRemove(id, out _);
        }
    }

    private static bool MatchesRecord(string path, UploadRecord rec)
    {
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (fs.Length != rec.SizeBytes) return false;
        return string.Equals(Convert.ToHexString(SHA256.HashData(fs)), rec.Sha256, StringComparison.OrdinalIgnoreCase);
    }

    public virtual async Task SaveJobAsync(CoreJob job)
    {
        var path = Path.Combine(JobDir(job.Id), "job.json");
        var tmp = path + ".tmp";
        await File.WriteAllBytesAsync(tmp, JsonSerializer.SerializeToUtf8Bytes(job, Json));
        for (var attempt = 1; !TryReplace(tmp, path, attempt); attempt++)
            await Task.Delay(ReplaceDelay(attempt));
    }

    // Windows refuses to replace a file while any handle is open on it: a concurrent read of the same record,
    // an indexer or a virus scanner. Such a refusal is waited out for a bounded real time (about 2.5 s in all).
    private const int ReplaceAttempts = 30;

    private static TimeSpan ReplaceDelay(int attempt) => TimeSpan.FromMilliseconds(Math.Min(10 * attempt, 100));

    /// <summary>Moves <paramref name="tmp"/> over <paramref name="path"/>; false when refused and another attempt is due.</summary>
    private static bool TryReplace(string tmp, string path, int attempt)
    {
        try
        {
            File.Move(tmp, path, overwrite: true);
            return true;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or IOException and not (FileNotFoundException or DirectoryNotFoundException) && attempt < ReplaceAttempts)
        {
            return false;
        }
    }

    public CoreJob? GetJob(Guid id) => ReadJson<CoreJob>(Path.Combine(JobsDir, id.ToString("N"), "job.json"));

    /// <param name="onUnreadable">Called with the file name and the exception for a record that cannot be read; the record is skipped.</param>
    public IEnumerable<CoreJob> AllJobs(Action<string, Exception>? onUnreadable = null)
    {
        foreach (var dir in Directory.EnumerateDirectories(JobsDir))
        {
            var path = Path.Combine(dir, "job.json");
            CoreJob? job;
            try { job = ReadJson<CoreJob>(path); }
            catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
            {
                onUnreadable?.Invoke(Path.GetRelativePath(_root, path), e);
                continue;
            }
            if (job is not null) yield return job;
        }
    }

    public IEnumerable<UploadRecord> AllUploads(Action<string, Exception>? onUnreadable = null)
    {
        foreach (var file in Directory.EnumerateFiles(UploadsDir, "*.json"))
        {
            UploadRecord? record;
            try { record = ReadJson<UploadRecord>(file); }
            catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
            {
                onUnreadable?.Invoke(Path.GetRelativePath(_root, file), e);
                continue;
            }
            if (record is not null) yield return record;
        }
    }

    /// <summary>Removes the upload's record, content and any partial file. Missing parts are fine.</summary>
    public void DeleteUpload(Guid id)
    {
        foreach (var path in new[] { UploadPath(id), UploadPath(id) + ".part", RecordPath(id), RecordPath(id) + ".tmp" })
            TryDeleteFile(path);
        _locks.TryRemove(id, out _);
    }

    /// <summary>Removes the job's directory (record and artifacts) without recreating it.</summary>
    public void DeleteJob(Guid id)
    {
        try
        {
            var dir = Path.Combine(JobsDir, id.ToString("N"));
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }

    private static void TryDeleteFile(string path)
    {
        try { File.Delete(path); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }

    private static void WriteJson<T>(string path, T value)
    {
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, JsonSerializer.SerializeToUtf8Bytes(value, Json));
        for (var attempt = 1; !TryReplace(tmp, path, attempt); attempt++)
            Thread.Sleep(ReplaceDelay(attempt));
    }

    private static T? ReadJson<T>(string path) where T : class
    {
        // Windows can briefly refuse a read while the record is being replaced; retry once.
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                if (!File.Exists(path)) return null;
                using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                return JsonSerializer.Deserialize<T>(fs, Json);
            }
            catch (IOException) when (attempt == 0)
            {
                Thread.Sleep(20);
            }
            catch (FileNotFoundException)
            {
                return null;
            }
        }
    }
}
