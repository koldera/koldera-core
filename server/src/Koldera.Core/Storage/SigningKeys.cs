using System.Security.Cryptography;

namespace Koldera.Core;

public static class SigningKeys
{
    public const int MinBytes = 32;

    /// <summary>Decodes KOLDERA_SIGNING_KEY, or reads/creates the key persisted at $DATA_DIR/.signing-key.</summary>
    public static byte[] Resolve(CoreOptions options)
    {
        if (options.SigningKey is { } configured) return Decode(configured);

        var path = Path.Combine(options.DataDir, ".signing-key");
        if (File.Exists(path))
        {
            var existing = File.ReadAllBytes(path);
            if (existing.Length < MinBytes)
                throw new InvalidOperationException($"{path} is shorter than {MinBytes} bytes; delete it to generate a new signing key");
            return existing;
        }

        Directory.CreateDirectory(options.DataDir);
        var key = RandomNumberGenerator.GetBytes(MinBytes);
        var tmp = path + ".tmp";
        var fso = new FileStreamOptions { Mode = FileMode.Create, Access = FileAccess.Write, Share = FileShare.None };
        if (!OperatingSystem.IsWindows()) fso.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
        using (var fs = new FileStream(tmp, fso)) fs.Write(key);
        File.Move(tmp, path, overwrite: true);
        return key;
    }

    /// <summary>Validates and decodes a configured key; throws naming KOLDERA_SIGNING_KEY.</summary>
    public static byte[] Decode(string value)
    {
        byte[] bytes;
        try { bytes = Convert.FromBase64String(value); }
        catch (FormatException) { throw Bad(); }
        if (bytes.Length < MinBytes) throw Bad();
        return bytes;
    }

    private static InvalidOperationException Bad() =>
        new($"KOLDERA_SIGNING_KEY: expected base64 of at least {MinBytes} bytes");
}
