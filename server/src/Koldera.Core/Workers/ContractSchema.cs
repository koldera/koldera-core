using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Koldera.Core.Workers;

/// <summary>
/// Validator for <c>contracts/*.schema.json</c>: the JSON Schema 2020-12 subset those schemas use.
/// Any other keyword, format or reference form is refused at load time, so a schema that outgrows
/// the validator fails a test instead of silently passing data.
/// </summary>
public sealed class ContractSchema
{
    private static readonly HashSet<string> Ignored = ["$schema", "$id", "title", "description", "default"];
    private static readonly HashSet<string> Known = ["type", "properties", "required", "additionalProperties", "items", "enum", "const", "oneOf", "$ref", "$defs", "minimum", "maximum", "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems", "format"];
    private static readonly HashSet<string> Types = ["object", "array", "string", "integer", "number", "boolean", "null"];
    private static readonly HashSet<string> Formats = ["uuid", "date-time", "uri", "uri-reference"];

    private readonly Node _root;

    private ContractSchema(Node root) => _root = root;

    public static ContractSchema FromText(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
        {
            throw new InvalidOperationException("schema root must be an object");
        }
        var defs = new Dictionary<string, Node>(StringComparer.Ordinal);
        var compiler = new Compiler(defs);
        if (root.TryGetProperty("$defs", out var defsElement))
        {
            // Two passes so a definition may reference a later one.
            foreach (var def in defsElement.EnumerateObject())
            {
                defs[def.Name] = new Node();
            }
            foreach (var def in defsElement.EnumerateObject())
            {
                compiler.CompileInto(defs[def.Name], def.Value, "$defs/" + def.Name);
            }
        }
        var rootNode = new Node();
        compiler.CompileInto(rootNode, root, "#");
        return new ContractSchema(rootNode);
    }

    /// <summary>Error messages as "&lt;json path&gt;: &lt;problem&gt;", at most 20, empty when the document is valid. Never quotes a value or a property name of the document: the messages reach clients.</summary>
    public IReadOnlyList<string> Validate(string json)
    {
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json);
        }
        catch (JsonException)
        {
            return ["not valid JSON"]; // the parser's message quotes input
        }
        using (document)
        {
            var errors = new List<string>();
            _root.Check(document.RootElement, "$", errors);
            return errors.Distinct().Take(20).ToList();
        }
    }

    /// <summary>
    /// Schemas use ECMA-262 pattern syntax, where an unescaped "$" matches only at the very end of the
    /// text; .NET also matches it before a final newline, which would let a newline-terminated value pass "^...$".
    /// Rewrites each "$" outside a character class to "\z" (end of text).
    /// </summary>
    internal static string EcmaEndAnchor(string pattern)
    {
        var result = new System.Text.StringBuilder(pattern.Length + 4);
        var inClass = false;
        for (var i = 0; i < pattern.Length; i++)
        {
            var c = pattern[i];
            if (c == '\\' && i + 1 < pattern.Length)
            {
                result.Append(c).Append(pattern[++i]);
                continue;
            }
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            if (c == '$' && !inClass) result.Append("\\z");
            else result.Append(c);
        }
        return result.ToString();
    }

    private sealed class Compiler(Dictionary<string, Node> defs)
    {
        public void CompileInto(Node node, JsonElement schema, string where)
        {
            if (schema.ValueKind != JsonValueKind.Object)
            {
                throw new InvalidOperationException($"{where}: a schema must be an object (boolean schemas are not supported)");
            }
            foreach (var keyword in schema.EnumerateObject())
            {
                if (Ignored.Contains(keyword.Name) || (keyword.Name == "$defs" && where == "#"))
                {
                    continue;
                }
                if (!Known.Contains(keyword.Name))
                {
                    throw new InvalidOperationException($"{where}: unsupported JSON Schema keyword '{keyword.Name}'");
                }
                var value = keyword.Value;
                switch (keyword.Name)
                {
                    case "type":
                        node.Types = value.ValueKind == JsonValueKind.Array ? value.EnumerateArray().Select(t => t.GetString()!).ToList() : [value.GetString()!];
                        if (node.Types.Any(t => !Types.Contains(t)))
                        {
                            throw new InvalidOperationException($"{where}: unsupported type in {value}");
                        }
                        break;
                    case "properties":
                        node.Properties = value.EnumerateObject().ToDictionary(p => p.Name, p => Compile(p.Value, where + "/properties/" + p.Name), StringComparer.Ordinal);
                        break;
                    case "required":
                        node.Required = value.EnumerateArray().Select(r => r.GetString()!).ToList();
                        break;
                    case "additionalProperties":
                        if (value.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
                        {
                            throw new InvalidOperationException($"{where}: additionalProperties must be true or false");
                        }
                        node.AdditionalProperties = value.ValueKind == JsonValueKind.True;
                        break;
                    case "items":
                        node.Items = Compile(value, where + "/items");
                        break;
                    case "enum":
                        node.Enum = value.EnumerateArray().Select(e => e.Clone()).ToList();
                        break;
                    case "const":
                        node.Const = value.Clone();
                        break;
                    case "oneOf":
                        node.OneOf = value.EnumerateArray().Select((s, i) => Compile(s, $"{where}/oneOf/{i}")).ToList();
                        break;
                    case "$ref":
                        var reference = value.GetString() ?? "";
                        const string prefix = "#/$defs/";
                        if (!reference.StartsWith(prefix, StringComparison.Ordinal) || !defs.TryGetValue(reference[prefix.Length..], out var target))
                        {
                            throw new InvalidOperationException($"{where}: only local '#/$defs/<name>' references are supported, got '{reference}'");
                        }
                        node.Ref = target;
                        break;
                    case "minimum":
                        node.Minimum = value.GetDecimal();
                        break;
                    case "maximum":
                        node.Maximum = value.GetDecimal();
                        break;
                    case "minLength":
                        node.MinLength = value.GetInt32();
                        break;
                    case "maxLength":
                        node.MaxLength = value.GetInt32();
                        break;
                    case "pattern":
                        node.Pattern = new Regex(EcmaEndAnchor(value.GetString()!), RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
                        break;
                    case "minItems":
                        node.MinItems = value.GetInt32();
                        break;
                    case "maxItems":
                        node.MaxItems = value.GetInt32();
                        break;
                    case "uniqueItems":
                        node.UniqueItems = value.ValueKind == JsonValueKind.True;
                        break;
                    case "format":
                        node.Format = value.GetString()!;
                        if (!Formats.Contains(node.Format))
                        {
                            throw new InvalidOperationException($"{where}: unsupported format '{node.Format}'");
                        }
                        break;
                }
            }
        }

        private Node Compile(JsonElement schema, string where)
        {
            var node = new Node();
            CompileInto(node, schema, where);
            return node;
        }
    }

    private sealed class Node
    {
        public List<string>? Types;
        public Dictionary<string, Node>? Properties;
        public List<string>? Required;
        public bool? AdditionalProperties;
        public Node? Items;
        public List<JsonElement>? Enum;
        public JsonElement? Const;
        public List<Node>? OneOf;
        public Node? Ref;
        public decimal? Minimum, Maximum;
        public int? MinLength, MaxLength, MinItems, MaxItems;
        public Regex? Pattern;
        public bool UniqueItems;
        public string? Format;

        public void Check(JsonElement value, string path, List<string> errors)
        {
            Ref?.Check(value, path, errors);
            if (Types is not null && !Types.Any(t => Matches(t, value)))
            {
                errors.Add($"{path}: expected {string.Join(" or ", Types)}");
                return;
            }
            if (Const is { } constant && !DeepEquals(constant, value))
            {
                errors.Add($"{path}: must equal {constant.GetRawText()}");
            }
            if (Enum is not null && !Enum.Any(e => DeepEquals(e, value)))
            {
                errors.Add($"{path}: not one of the allowed values");
            }
            if (OneOf is not null)
            {
                var matching = OneOf.Count(alternative =>
                {
                    var scratch = new List<string>();
                    alternative.Check(value, path, scratch);
                    return scratch.Count == 0;
                });
                if (matching == 0)
                {
                    errors.Add($"{path}: matches none of the alternatives");
                }
                else if (matching > 1)
                {
                    errors.Add($"{path}: matches more than one alternative");
                }
            }
            switch (value.ValueKind)
            {
                case JsonValueKind.Object:
                    CheckObject(value, path, errors);
                    break;
                case JsonValueKind.Array:
                    CheckArray(value, path, errors);
                    break;
                case JsonValueKind.String:
                    CheckString(value.GetString()!, path, errors);
                    break;
                case JsonValueKind.Number:
                    CheckNumber(value, path, errors);
                    break;
            }
        }

        private void CheckObject(JsonElement value, string path, List<string> errors)
        {
            if (Required is not null)
            {
                foreach (var name in Required)
                {
                    if (!value.TryGetProperty(name, out _))
                    {
                        errors.Add($"{path}: missing required property '{name}'");
                    }
                }
            }
            foreach (var property in value.EnumerateObject())
            {
                if (Properties is not null && Properties.TryGetValue(property.Name, out var schema))
                {
                    schema.Check(property.Value, path + "." + property.Name, errors);
                }
                else if (AdditionalProperties == false)
                {
                    errors.Add($"{path}: unexpected property"); // the name is worker output, not echoed
                }
            }
        }

        private void CheckArray(JsonElement value, string path, List<string> errors)
        {
            var count = value.GetArrayLength();
            if (MinItems is { } min && count < min)
            {
                errors.Add($"{path}: fewer than {min} items");
            }
            if (MaxItems is { } max && count > max)
            {
                errors.Add($"{path}: more than {max} items");
            }
            var index = 0;
            var seen = new List<JsonElement>();
            foreach (var item in value.EnumerateArray())
            {
                Items?.Check(item, $"{path}[{index}]", errors);
                if (UniqueItems)
                {
                    if (seen.Any(s => DeepEquals(s, item)))
                    {
                        errors.Add($"{path}: items are not unique");
                    }
                    seen.Add(item);
                }
                index++;
            }
        }

        private void CheckString(string text, string path, List<string> errors)
        {
            var length = new StringInfo(text).LengthInTextElements;
            if (MinLength is { } min && length < min)
            {
                errors.Add($"{path}: shorter than {min} characters");
            }
            if (MaxLength is { } max && length > max)
            {
                errors.Add($"{path}: longer than {max} characters");
            }
            if (Pattern is not null && !Pattern.IsMatch(text))
            {
                errors.Add($"{path}: does not match pattern {Pattern}");
            }
            if (Format is not null && !FormatValid(Format, text))
            {
                errors.Add($"{path}: not a valid {Format}");
            }
        }

        private void CheckNumber(JsonElement value, string path, List<string> errors)
        {
            if (!value.TryGetDecimal(out var number))
            {
                return; // out of decimal range: only the type check applies
            }
            if (Minimum is { } min && number < min)
            {
                errors.Add($"{path}: below the minimum {min.ToString(CultureInfo.InvariantCulture)}");
            }
            if (Maximum is { } max && number > max)
            {
                errors.Add($"{path}: above the maximum {max.ToString(CultureInfo.InvariantCulture)}");
            }
        }

        private static bool Matches(string type, JsonElement value) => type switch
        {
            "object" => value.ValueKind == JsonValueKind.Object,
            "array" => value.ValueKind == JsonValueKind.Array,
            "string" => value.ValueKind == JsonValueKind.String,
            "boolean" => value.ValueKind is JsonValueKind.True or JsonValueKind.False,
            "null" => value.ValueKind == JsonValueKind.Null,
            "number" => value.ValueKind == JsonValueKind.Number,
            "integer" => value.ValueKind == JsonValueKind.Number && IsIntegral(value),
            _ => false,
        };

        /// <summary>2020-12: a number with a zero fractional part is an integer (1.0 counts, 1.5 does not).</summary>
        private static bool IsIntegral(JsonElement value) => value.TryGetDecimal(out var d) ? decimal.Truncate(d) == d : value.TryGetDouble(out var f) && Math.Floor(f) == f;

        private static bool FormatValid(string format, string text) => format switch
        {
            "uuid" => Guid.TryParseExact(text, "D", out _),
            "date-time" => DateTimeOffset.TryParseExact(text, ["yyyy-MM-dd'T'HH:mm:ssK", "yyyy-MM-dd'T'HH:mm:ss.FFFFFFFK"], CultureInfo.InvariantCulture, DateTimeStyles.None, out _),
            "uri" => Uri.TryCreate(text, UriKind.Absolute, out var absolute) && absolute.Scheme.Length > 0 && !text.Any(char.IsWhiteSpace),
            "uri-reference" => Uri.TryCreate(text, UriKind.RelativeOrAbsolute, out _) && !text.Any(char.IsWhiteSpace),
            _ => false,
        };

        private static bool DeepEquals(JsonElement a, JsonElement b)
        {
            if (a.ValueKind != b.ValueKind)
            {
                return (a.ValueKind is JsonValueKind.True or JsonValueKind.False) && (b.ValueKind is JsonValueKind.True or JsonValueKind.False) && a.ValueKind == b.ValueKind;
            }
            switch (a.ValueKind)
            {
                case JsonValueKind.Object:
                    var left = a.EnumerateObject().ToDictionary(p => p.Name, p => p.Value, StringComparer.Ordinal);
                    var right = b.EnumerateObject().ToDictionary(p => p.Name, p => p.Value, StringComparer.Ordinal);
                    return left.Count == right.Count && left.All(kv => right.TryGetValue(kv.Key, out var other) && DeepEquals(kv.Value, other));
                case JsonValueKind.Array:
                    return a.GetArrayLength() == b.GetArrayLength() && a.EnumerateArray().Zip(b.EnumerateArray()).All(pair => DeepEquals(pair.First, pair.Second));
                case JsonValueKind.String:
                    return string.Equals(a.GetString(), b.GetString(), StringComparison.Ordinal);
                case JsonValueKind.Number:
                    return a.TryGetDecimal(out var x) && b.TryGetDecimal(out var y) ? x == y : a.GetRawText() == b.GetRawText();
                default:
                    return true; // true/true, false/false, null/null
            }
        }
    }
}
