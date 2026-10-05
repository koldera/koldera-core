namespace Koldera.Core;

/// <summary>"explicitTitle" as stored: trimmed, 1-500 Unicode code points (the limit matches the SDKs), no control characters; null when absent.</summary>
public static class TitleInput
{
    public const string Error = "explicitTitle must be 1-500 printable characters";

    public static string? Normalize(string? raw, out string? error)
    {
        error = null;
        if (raw is null) return null;
        var t = raw.Trim();
        if (t.Length == 0 || t.EnumerateRunes().Count() > 500 || t.Any(char.IsControl))
        {
            error = Error;
            return null;
        }
        return t;
    }
}
