using System.Security.Cryptography;

namespace Koldera.Core.Workers;

/// <summary>Thrown by executors when the worker's output is malformed or lies; terminal, never retried.</summary>
public sealed class WorkerContractException(string message) : Exception(message);

/// <summary>Shared verify-then-keep step: a worker artifact is kept only if its bytes match its declaration.</summary>
public static class ArtifactFiles
{
    public static string RequireOutputName(string declaredPath, string canonicalOutputDirectory)
    {
        if (!declaredPath.StartsWith(canonicalOutputDirectory + "/", StringComparison.Ordinal))
            throw new WorkerContractException("worker artifact path outside the output directory");
        var name = declaredPath[(canonicalOutputDirectory.Length + 1)..];
        if (name.Length == 0 || name.Contains('/') || name.Contains('\\') || name is "." or "..")
            throw new WorkerContractException("worker artifact name is not a plain file name");
        return name;
    }

    /// <summary>Checks size and SHA-256 of <paramref name="localPath"/>, then moves it into <paramref name="jobOutDir"/>.</summary>
    public static async Task<CoreArtifact> VerifyAndKeepAsync(string localPath, string kind, string declaredSha256, long declaredBytes, string jobOutDir, DateTimeOffset now, DateTimeOffset expiresAt, CancellationToken ct)
    {
        if (!File.Exists(localPath)) throw new WorkerContractException($"worker artifact {kind} is missing on disk");
        var length = new FileInfo(localPath).Length;
        string actual;
        await using (var stream = File.OpenRead(localPath))
        {
            actual = Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, ct));
        }
        if (length != declaredBytes || actual != declaredSha256.ToLowerInvariant())
            throw new WorkerContractException($"worker artifact {kind} does not match its declared size/hash");
        Directory.CreateDirectory(jobOutDir);
        var name = Path.GetFileName(localPath);
        var target = Path.Combine(jobOutDir, name);
        try
        {
            File.Move(localPath, target, overwrite: false);
        }
        catch (IOException) when (File.Exists(target))
        {
            throw new WorkerContractException("artifact already exists");
        }
        return new CoreArtifact(Guid.NewGuid(), kind, name, declaredSha256.ToLowerInvariant(), declaredBytes, now, expiresAt);
    }
}
