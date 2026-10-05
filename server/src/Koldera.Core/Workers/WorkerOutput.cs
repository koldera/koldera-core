using System.Text;

namespace Koldera.Core.Workers;

/// <summary>Reads files a worker wrote. They are untrusted, so their size is checked before anything is loaded.</summary>
public static class WorkerOutput
{
    /// <summary>Far above any real result (findings are capped per rule); a larger file means a broken or hostile worker.</summary>
    public const long MaxResultBytes = 64L * 1024 * 1024;

    public static async Task<string> ReadResultAsync(string path, CancellationToken ct, long maxBytes = MaxResultBytes)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
        // The length check covers the normal case; the bounded copy covers a file that grows while it is read.
        if (stream.Length > maxBytes) throw TooLarge(maxBytes);
        using var buffer = new MemoryStream((int)Math.Min(stream.Length, maxBytes));
        var chunk = new byte[81920];
        int read;
        while ((read = await stream.ReadAsync(chunk, ct)) > 0)
        {
            if (buffer.Length + read > maxBytes) throw TooLarge(maxBytes);
            buffer.Write(chunk, 0, read);
        }
        return new UTF8Encoding(false).GetString(buffer.GetBuffer(), 0, (int)buffer.Length).TrimStart('﻿');
    }

    private static WorkerContractException TooLarge(long maxBytes) => new($"worker result exceeds {maxBytes} bytes");
}
