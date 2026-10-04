using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace Koldera.Core;

/// <summary>Signs and verifies expiring URL paths with HMAC-SHA256 over "path|exp".</summary>
public sealed class UrlSigner(byte[] key, TimeProvider clock)
{
    public string Sign(string path, TimeSpan ttl)
    {
        var exp = clock.GetUtcNow().Add(ttl).ToUnixTimeSeconds();
        return $"{path}?exp={exp.ToString(CultureInfo.InvariantCulture)}&sig={Base64Url(Mac(path, exp))}";
    }

    public bool Verify(string path, string? exp, string? sig)
    {
        if (string.IsNullOrEmpty(exp) || string.IsNullOrEmpty(sig)) return false;
        if (!long.TryParse(exp, NumberStyles.None, CultureInfo.InvariantCulture, out var expiry)) return false;
        byte[] given;
        try { given = FromBase64Url(sig); }
        catch (FormatException) { return false; }
        var valid = CryptographicOperations.FixedTimeEquals(given, Mac(path, expiry));
        return valid && clock.GetUtcNow().ToUnixTimeSeconds() <= expiry;
    }

    private byte[] Mac(string path, long exp) =>
        HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(path + "|" + exp.ToString(CultureInfo.InvariantCulture)));

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromBase64Url(string s)
    {
        var t = s.Replace('-', '+').Replace('_', '/');
        t = t.PadRight(t.Length + (4 - t.Length % 4) % 4, '=');
        return Convert.FromBase64String(t);
    }
}
