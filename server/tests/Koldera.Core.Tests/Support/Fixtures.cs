using System.Security.Cryptography;
using System.Text;

namespace Koldera.Core.Tests.Support;

public static class Fixtures
{
    private static byte[] Pdf(string title) => Encoding.ASCII.GetBytes(
        "%PDF-1.4\n" +
        "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
        "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
        "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>\nendobj\n" +
        $"4 0 obj\n<< /Title ({title}) >>\nendobj\n" +
        "trailer\n<< /Root 1 0 R /Info 4 0 R /Size 5 >>\n%%EOF\n");

    public static byte[] SmallPdf() => Pdf("Small");

    public static byte[] OtherPdf() => Pdf("Other document");

    public static string Sha256(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}
