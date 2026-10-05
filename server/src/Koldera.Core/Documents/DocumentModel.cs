namespace Koldera.Core.Documents;

/// <summary>
/// One problem found in a structured document. <paramref name="Path"/> is a JSON Pointer (RFC 6901)
/// to the offending value, the empty string for the document itself.
/// </summary>
public sealed record ValidationError(string Path, string Code, string Message);

/// <summary>Error codes of <see cref="DocumentValidator"/>.</summary>
public static class DocumentErrorCodes
{
    public const string Schema = "SCHEMA";
    public const string ImageAltMissing = "IMAGE_ALT_MISSING";
    public const string HeadingLevelSkipped = "HEADING_LEVEL_SKIPPED";
    public const string TableHeaderEmpty = "TABLE_HEADER_EMPTY";
    public const string LinkTextEmpty = "LINK_TEXT_EMPTY";
    public const string FontUnknown = "FONT_UNKNOWN";
    public const string LanguageInvalid = "LANGUAGE_INVALID";
}

/// <summary>The fonts shipped with the renderer; a document may name no other.</summary>
public static class DocumentFonts
{
    public static readonly IReadOnlyList<string> Bundled = ["Noto Sans", "Noto Serif", "Noto Sans Arabic", "Noto Sans Hebrew"];
}
