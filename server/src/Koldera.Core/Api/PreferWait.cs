using System.Globalization;

namespace Koldera.Core;

/// <summary>
/// The <c>wait</c> preference of RFC 7240 on a job-creating POST: <c>Prefer: wait=N</c> asks the server to hold the
/// answer for up to N seconds and return the finished job instead of 202.
/// </summary>
public static class PreferWait
{
    public const int MaxSeconds = 900;

    /// <summary>The wait in <c>1..900</c> seconds, or null when the header has no usable <c>wait</c> (the first one decides).</summary>
    public static TimeSpan? Parse(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return null;
        foreach (var preference in header.Split(','))
        {
            // token [ "=" value ] *( ";" parameter ): only the first part names the preference.
            var main = preference.Split(';')[0];
            var eq = main.IndexOf('=');
            var name = (eq < 0 ? main : main[..eq]).Trim();
            if (!name.Equals("wait", StringComparison.OrdinalIgnoreCase)) continue;
            if (eq < 0) return null;
            var value = main[(eq + 1)..].Trim().Trim('"');
            if (value.Length is 0 or > 4 || !value.All(char.IsAsciiDigit)) return null;
            var seconds = int.Parse(value, NumberStyles.None, CultureInfo.InvariantCulture);
            return seconds is >= 1 and <= MaxSeconds ? TimeSpan.FromSeconds(seconds) : null;
        }
        return null;
    }
}
