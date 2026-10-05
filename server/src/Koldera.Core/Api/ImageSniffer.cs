using System.Text;
using System.Xml;

namespace Koldera.Core;

/// <summary>
/// Cheap magic-byte check that an uploaded image is what its declared content type says (the renderer's own
/// decoding is the real boundary): PNG signature, JPEG start-of-image marker, or UTF-8 XML whose root element is svg.
/// </summary>
public static class ImageSniffer
{
    private static readonly byte[] PngSignature = [0x89, (byte)'P', (byte)'N', (byte)'G', 0x0D, 0x0A, 0x1A, 0x0A];
    private static readonly byte[] JpegStart = [0xFF, 0xD8, 0xFF];

    /// <summary>Null when the content matches the declared type, otherwise a short reason.</summary>
    public static async Task<string?> MismatchAsync(string path, string contentType, CancellationToken ct)
    {
        var head = new byte[8192];
        int n;
        await using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
        {
            n = await fs.ReadAsync(head, ct);
        }
        var bytes = head.AsSpan(0, n);
        switch (contentType)
        {
            case "image/png":
                return bytes.StartsWith(PngSignature) ? null : "uploaded file is not a PNG image (signature mismatch)";
            case "image/jpeg":
                return bytes.StartsWith(JpegStart) ? null : "uploaded file is not a JPEG image (signature mismatch)";
            case "image/svg+xml":
                return SvgRootMismatch(path);
            default:
                return "unsupported image type";
        }
    }

    private static string? SvgRootMismatch(string path)
    {
        try
        {
            // Strict UTF-8, no DTD (no entity expansion); only the prolog and the root start tag are read.
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            using var text = new StreamReader(stream, new UTF8Encoding(false, true), detectEncodingFromByteOrderMarks: false);
            using var xml = XmlReader.Create(text, new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null });
            while (xml.Read())
            {
                if (xml.NodeType == XmlNodeType.Element)
                    return xml.LocalName == "svg" ? null : "uploaded file is not an SVG image (the root element is not svg)";
            }
            return "uploaded file is not an SVG image (no root element)";
        }
        catch (Exception e) when (e is XmlException or DecoderFallbackException)
        {
            return "uploaded file is not an SVG image (not well-formed UTF-8 XML)";
        }
    }
}
