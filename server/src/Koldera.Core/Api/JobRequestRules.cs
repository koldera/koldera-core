using System.Text.RegularExpressions;

namespace Koldera.Core;

/// <summary>Input rules shared by the HTTP job endpoints and the command-line tool, so both accept and refuse the same things.</summary>
public static partial class JobRequestRules
{
    private static readonly byte[] PdfMagic = "%PDF-"u8.ToArray();

    public const string LanguageError = "explicitLanguage must be a well-formed BCP 47 tag";
    public const string SelectorError = "contentSelector must be 1-200 printable characters (a CSS selector) or omitted";

    [GeneratedRegex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")]
    private static partial Regex LanguageTag();

    public static bool IsLanguageTag(string tag) => LanguageTag().IsMatch(tag);

    /// <summary>Only Chromium can tell whether a selector is valid CSS; here it is bounded and printable. A null selector is fine; <paramref name="error"/> is set when invalid.</summary>
    public static string? NormalizeSelector(string? raw, out string? error)
    {
        error = null;
        var selector = raw?.Trim();
        if (selector is not null && (selector.Length is 0 or > 200 || selector.Any(char.IsControl)))
        {
            error = SelectorError;
            return null;
        }
        return selector;
    }

    /// <summary>The first 1024 bytes may carry the header after a short preamble (ISO 32000 permits an offset).</summary>
    public static async Task<bool> HasPdfHeaderAsync(string path, CancellationToken ct)
    {
        var buffer = new byte[1024];
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        var n = await fs.ReadAsync(buffer, ct);
        return buffer.AsSpan(0, n).IndexOf(PdfMagic) >= 0;
    }

    /// <summary>Cheap shape check only (the renderer's preflight is the real boundary): no PDF, some markup, valid UTF-8; null when acceptable.</summary>
    public static async Task<string?> SniffHtmlAsync(string path, CancellationToken ct)
    {
        var buffer = new byte[1024];
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        var n = await fs.ReadAsync(buffer, ct);
        var head = buffer.AsSpan(0, n);
        if (head.IndexOf(PdfMagic) >= 0) return "uploaded file is a PDF, not an HTML document";
        if (head.IndexOf((byte)'<') < 0) return "uploaded file does not look like an HTML document (no markup in the first kilobyte)";
        try
        {
            // The window may cut a multi-byte sequence at its end; only earlier bytes must decode.
            new System.Text.UTF8Encoding(false, true).GetCharCount(head[..Math.Max(0, head.Length - 4)]);
        }
        catch (System.Text.DecoderFallbackException)
        {
            return "uploaded file is not UTF-8 encoded";
        }
        return null;
    }
}
