using System.Text.Json;

namespace Koldera.Core.Documents;

/// <summary>An image block of a document: where it is (JSON Pointer) and which source it names.</summary>
public sealed record ImageReference(string Path, string? UploadId, string? File);

/// <summary>Finds the image blocks of a validated document, wherever they sit, so the server can check and fetch what they reference.</summary>
public static class DocumentImages
{
    public static IReadOnlyList<ImageReference> Find(JsonElement doc)
    {
        var found = new List<ImageReference>();
        Walk(doc, "", found);
        return found;
    }

    private static void Walk(JsonElement node, string path, List<ImageReference> found)
    {
        switch (node.ValueKind)
        {
            case JsonValueKind.Object:
                if (node.TryGetProperty("type", out var type) && type.ValueKind == JsonValueKind.String && type.GetString() == "image")
                {
                    found.Add(new ImageReference(path,
                        node.TryGetProperty("uploadId", out var id) && id.ValueKind == JsonValueKind.String ? id.GetString() : null,
                        node.TryGetProperty("file", out var file) && file.ValueKind == JsonValueKind.String ? file.GetString() : null));
                }
                foreach (var property in node.EnumerateObject())
                {
                    Walk(property.Value, path + "/" + property.Name.Replace("~", "~0").Replace("/", "~1"), found);
                }
                break;
            case JsonValueKind.Array:
                var i = 0;
                foreach (var item in node.EnumerateArray())
                {
                    Walk(item, path + "/" + i++, found);
                }
                break;
        }
    }
}
