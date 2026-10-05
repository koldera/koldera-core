using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Koldera.Core.Workers;

namespace Koldera.Core.Documents;

/// <summary>
/// Checks a structured document against <c>contracts/document.schema.json</c> and then against the
/// rules a schema cannot express. Used by the HTTP server and the command-line tool alike.
/// </summary>
public static class DocumentValidator
{
    private const string SchemaFile = "document.schema.json";

    // Shape check only: BCP 47 well-formedness without a registry lookup.
    private static readonly Regex LanguageTag = new("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$", RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));

    private static readonly Lazy<ContractSchema> Schema = new(() => SchemaResources.Load(SchemaFile));

    /// <summary>
    /// Structural violations come first, one <c>SCHEMA</c> error per message of <see cref="ContractSchema"/>.
    /// While any exist nothing else is checked, so the semantic rules can rely on the shape. Otherwise
    /// the semantic errors are returned in document order (header, body, footer).
    /// </summary>
    public static IReadOnlyList<ValidationError> Validate(JsonElement doc)
    {
        var structural = Schema.Value.Validate(doc.GetRawText()).Select(ToSchemaError).ToList();
        if (structural.Count > 0)
        {
            return structural;
        }
        var errors = new List<ValidationError>();
        CheckLanguage(doc, "", "language", errors);
        if (doc.TryGetProperty("theme", out var theme))
        {
            CheckFont(theme, "/theme", errors);
        }
        Section(doc, "header", errors, null);
        Section(doc, "body", errors, new HeadingState());
        Section(doc, "footer", errors, null);
        return errors;
    }

    /// <summary>
    /// ContractSchema reports "$.body[1].header: problem"; this turns the location into a JSON Pointer
    /// ("/body/1/header"). Schema property names never contain "." or "[", so the split is unambiguous.
    /// </summary>
    private static ValidationError ToSchemaError(string message)
    {
        var split = message.IndexOf(": ", StringComparison.Ordinal);
        if (split < 0 || message[0] != '$')
        {
            return new ValidationError("", DocumentErrorCodes.Schema, message);
        }
        var pointer = new StringBuilder();
        foreach (Match part in Regex.Matches(message[1..split], @"\.([^.\[]+)|\[(\d+)\]"))
        {
            pointer.Append('/').Append(part.Groups[1].Success ? Escape(part.Groups[1].Value) : part.Groups[2].Value);
        }
        return new ValidationError(pointer.ToString(), DocumentErrorCodes.Schema, message[(split + 2)..]);
    }

    private static string Escape(string name) => name.Replace("~", "~0").Replace("/", "~1");

    private sealed class HeadingState
    {
        public int Previous; // 0 until the first heading
    }

    private static void Section(JsonElement doc, string name, List<ValidationError> errors, HeadingState? headings)
    {
        if (!doc.TryGetProperty(name, out var blocks))
        {
            return;
        }
        var index = 0;
        foreach (var block in blocks.EnumerateArray())
        {
            Block(block, $"/{name}/{index++}", errors, headings);
        }
    }

    private static void Block(JsonElement block, string path, List<ValidationError> errors, HeadingState? headings)
    {
        switch (block.GetProperty("type").GetString())
        {
            case "heading":
                Heading(block, path, errors, headings);
                break;
            case "paragraph":
                CheckLanguage(block, path, "lang", errors);
                CheckFont(block, path, errors);
                Inlines(block.GetProperty("content"), path + "/content", errors);
                break;
            case "list":
                ListItems(block.GetProperty("items"), path + "/items", errors);
                break;
            case "table":
                Table(block, path, errors);
                break;
            case "image":
                Image(block, path, errors);
                break;
            case "link":
                LinkText(block, path + "/text", errors);
                break;
        }
    }

    private static void Heading(JsonElement block, string path, List<ValidationError> errors, HeadingState? state)
    {
        if (state is null)
        {
            return; // header and footer headings are decoration, outside the document outline
        }
        var level = block.GetProperty("level").GetInt32();
        if (state.Previous == 0 ? level > 2 : level > state.Previous + 1)
        {
            var expected = state.Previous == 0 ? "1 or 2" : $"at most {state.Previous + 1}";
            errors.Add(new ValidationError(path, DocumentErrorCodes.HeadingLevelSkipped, $"Heading level {level} is too deep here; expected {expected}."));
        }
        state.Previous = level;
    }

    private static void Inlines(JsonElement pieces, string path, List<ValidationError> errors)
    {
        var index = 0;
        foreach (var piece in pieces.EnumerateArray())
        {
            if (piece.ValueKind == JsonValueKind.Object && piece.TryGetProperty("link", out var link))
            {
                LinkText(link, $"{path}/{index}/link/text", errors);
            }
            index++;
        }
    }

    private static void ListItems(JsonElement items, string path, List<ValidationError> errors)
    {
        var i = 0;
        foreach (var item in items.EnumerateArray())
        {
            var j = 0;
            foreach (var element in item.EnumerateArray())
            {
                var at = $"{path}/{i}/{j}";
                if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty("list", out var nested))
                {
                    ListItems(nested.GetProperty("items"), at + "/list/items", errors);
                }
                else if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty("link", out var link))
                {
                    LinkText(link, at + "/link/text", errors);
                }
                j++;
            }
            i++;
        }
    }

    private static void Table(JsonElement table, string path, List<ValidationError> errors)
    {
        var header = table.GetProperty("header");
        var usable = header.GetArrayLength() > 0 && header.EnumerateArray().All(row => row.EnumerateArray().Any(cell => !string.IsNullOrWhiteSpace(cell.GetString())));
        if (!usable)
        {
            errors.Add(new ValidationError(path + "/header", DocumentErrorCodes.TableHeaderEmpty, "A table needs at least one header row and every header row needs a non-blank cell."));
        }
    }

    private static void Image(JsonElement image, string path, List<ValidationError> errors)
    {
        var hasUpload = image.TryGetProperty("uploadId", out _);
        var hasFile = image.TryGetProperty("file", out _);
        if (hasUpload == hasFile)
        {
            errors.Add(new ValidationError(path, DocumentErrorCodes.Schema, "An image needs exactly one of uploadId or file."));
        }
        var hasAlt = image.TryGetProperty("alt", out var alt);
        var decorative = image.TryGetProperty("decorative", out _);
        if (hasAlt && decorative)
        {
            errors.Add(new ValidationError(path, DocumentErrorCodes.Schema, "An image cannot have both alt and decorative."));
        }
        else if (!decorative && (!hasAlt || string.IsNullOrWhiteSpace(alt.GetString())))
        {
            errors.Add(new ValidationError(path, DocumentErrorCodes.ImageAltMissing, "An image needs a non-blank alt description or decorative true."));
        }
    }

    private static void LinkText(JsonElement link, string path, List<ValidationError> errors)
    {
        if (string.IsNullOrWhiteSpace(link.GetProperty("text").GetString()))
        {
            errors.Add(new ValidationError(path, DocumentErrorCodes.LinkTextEmpty, "Link text must not be blank."));
        }
    }

    private static void CheckLanguage(JsonElement owner, string ownerPath, string property, List<ValidationError> errors)
    {
        if (owner.TryGetProperty(property, out var value) && !LanguageTag.IsMatch(value.GetString()!))
        {
            errors.Add(new ValidationError($"{ownerPath}/{property}", DocumentErrorCodes.LanguageInvalid, "Not a well-formed BCP 47 language tag."));
        }
    }

    private static void CheckFont(JsonElement owner, string ownerPath, List<ValidationError> errors)
    {
        if (owner.TryGetProperty("font", out var value) && !DocumentFonts.Bundled.Contains(value.GetString()!))
        {
            errors.Add(new ValidationError($"{ownerPath}/font", DocumentErrorCodes.FontUnknown, $"Not a bundled font; use one of: {string.Join(", ", DocumentFonts.Bundled)}."));
        }
    }
}
