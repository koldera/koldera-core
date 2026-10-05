using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>
/// The in-house validator behind every worker contract check (no database needed). It implements
/// exactly the JSON Schema 2020-12 subset the contracts use and refuses anything else at load time,
/// so a schema that grows a new keyword fails the build's tests instead of silently passing data.
/// </summary>
public sealed class ContractSchemaTests
{
    private const string Schema = """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "https://example.test/t.schema.json",
          "title": "t", "description": "d",
          "type": "object",
          "required": ["id", "kind", "count", "tags", "ratio", "when", "path", "choice", "maybe"],
          "additionalProperties": false,
          "properties": {
            "id": { "type": "string", "format": "uuid" },
            "kind": { "type": "string", "enum": ["pdf", "web"] },
            "count": { "type": "integer", "minimum": 0, "maximum": 10 },
            "ratio": { "type": ["number", "null"], "minimum": 0, "maximum": 1 },
            "tags": { "type": "array", "items": { "type": "string", "minLength": 1, "maxLength": 5, "pattern": "^[a-z]+$" }, "minItems": 1, "maxItems": 3, "uniqueItems": true },
            "when": { "type": "string", "format": "date-time" },
            "path": { "type": "string", "format": "uri-reference" },
            "site": { "type": "string", "format": "uri" },
            "choice": { "oneOf": [ { "$ref": "#/$defs/a" }, { "$ref": "#/$defs/b" } ] },
            "maybe": { "type": ["boolean", "null"], "default": true },
            "specVersion": { "const": "1.0" },
            "open": { "type": "object", "additionalProperties": true },
            "list": { "type": "array", "items": { "$ref": "#/$defs/a" } }
          },
          "$defs": {
            "a": { "type": "object", "required": ["a"], "additionalProperties": false, "properties": { "a": { "type": "integer" } } },
            "b": { "type": "object", "required": ["b"], "additionalProperties": false, "properties": { "b": { "type": "string" } } }
          }
        }
        """;

    private static readonly ContractSchema Compiled = ContractSchema.FromText(Schema);

    private const string Valid = """
        {"id":"0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a","kind":"pdf","count":3,"ratio":0.5,"tags":["ab","cd"],"when":"2026-09-18T07:00:00Z","path":"/work/out/x.pdf","site":"https://example.test/","choice":{"a":1},"maybe":null,"specVersion":"1.0","open":{"anything":[1,2]},"list":[{"a":2}]}
        """;

    [Fact]
    public void AValidDocumentHasNoErrors() => Assert.Empty(Compiled.Validate(Valid));

    public static TheoryData<string, string, string> Invalid => new()
    {
        { "not json", "{", "not valid JSON" },
        { "wrong root type", "[]", "$: expected object" },
        { "missing required", Mutate(Valid, "\"count\":3,", ""), "$: missing required property 'count'" },
        { "unknown property", Mutate(Valid, "\"count\":3,", "\"count\":3,\"extra\":1,"), "$: unexpected property 'extra'" },
        { "bad uuid", Mutate(Valid, "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", "not-a-uuid"), "$.id: not a valid uuid" },
        { "enum", Mutate(Valid, "\"kind\":\"pdf\"", "\"kind\":\"docx\""), "$.kind: not one of the allowed values" },
        { "integer with fraction", Mutate(Valid, "\"count\":3", "\"count\":3.5"), "$.count: expected integer" },
        { "maximum", Mutate(Valid, "\"count\":3", "\"count\":11"), "$.count: 11 is above the maximum 10" },
        { "minimum", Mutate(Valid, "\"count\":3", "\"count\":-1"), "$.count: -1 is below the minimum 0" },
        { "null where not allowed", Mutate(Valid, "\"count\":3", "\"count\":null"), "$.count: expected integer" },
        { "nullable number out of range", Mutate(Valid, "\"ratio\":0.5", "\"ratio\":1.5"), "$.ratio: 1.5 is above the maximum 1" },
        { "pattern", Mutate(Valid, "[\"ab\",\"cd\"]", "[\"ab\",\"C1\"]"), "$.tags[1]: does not match pattern" },
        { "maxLength", Mutate(Valid, "[\"ab\",\"cd\"]", "[\"abcdef\"]"), "$.tags[0]: longer than 5" },
        { "minLength", Mutate(Valid, "[\"ab\",\"cd\"]", "[\"\"]"), "$.tags[0]: shorter than 1" },
        { "minItems", Mutate(Valid, "[\"ab\",\"cd\"]", "[]"), "$.tags: fewer than 1 items" },
        { "maxItems", Mutate(Valid, "[\"ab\",\"cd\"]", "[\"a\",\"b\",\"c\",\"d\"]"), "$.tags: more than 3 items" },
        { "uniqueItems", Mutate(Valid, "[\"ab\",\"cd\"]", "[\"ab\",\"ab\"]"), "$.tags: items are not unique" },
        { "date-time", Mutate(Valid, "2026-09-18T07:00:00Z", "yesterday"), "$.when: not a valid date-time" },
        { "uri", Mutate(Valid, "https://example.test/", "not a uri"), "$.site: not a valid uri" },
        { "uri-reference", Mutate(Valid, "/work/out/x.pdf", "http://exa mple/x"), "$.path: not a valid uri-reference" },
        { "oneOf none", Mutate(Valid, "\"choice\":{\"a\":1}", "\"choice\":{\"c\":1}"), "$.choice: matches none of the alternatives" },
        { "oneOf empty object", Mutate(Valid, "\"choice\":{\"a\":1}", "\"choice\":{}"), "$.choice: matches none of the alternatives" },
        { "const", Mutate(Valid, "\"specVersion\":\"1.0\"", "\"specVersion\":\"2.0\""), "$.specVersion: must equal \"1.0\"" },
        { "nested $ref in items", Mutate(Valid, "\"list\":[{\"a\":2}]", "\"list\":[{\"a\":\"two\"}]"), "$.list[0].a: expected integer" },
        { "boolean-or-null", Mutate(Valid, "\"maybe\":null", "\"maybe\":\"yes\""), "$.maybe: expected boolean or null" },
    };

    [Theory]
    [MemberData(nameof(Invalid))]
    public void EveryKeywordRejectsWithALocatedMessage(string label, string json, string expectedError)
    {
        var errors = Compiled.Validate(json);
        Assert.True(errors.Any(e => e.StartsWith(expectedError, StringComparison.Ordinal)), $"{label}: expected '{expectedError}' in [{string.Join(" | ", errors)}]");
    }

    [Fact]
    public void OneOfRequiresExactlyOneMatch()
    {
        // {"a":1,"b":"x"} fails both alternatives (additionalProperties:false), so a looser pair is needed to show "more than one".
        var schema = ContractSchema.FromText("""{"oneOf":[{"type":"integer"},{"type":"number"}]}""");
        Assert.Contains("$: matches more than one alternative", schema.Validate("1"));
        Assert.Empty(schema.Validate("1.5"));
    }

    [Fact]
    public void UnsupportedKeywordsAreRefusedWhenTheSchemaLoads()
    {
        var e = Assert.Throws<InvalidOperationException>(() => ContractSchema.FromText("""{"type":"object","patternProperties":{"^x":{}}}"""));
        Assert.Contains("patternProperties", e.Message);
        Assert.Throws<InvalidOperationException>(() => ContractSchema.FromText("""{"$ref":"other.json#/x"}"""));
        Assert.Throws<InvalidOperationException>(() => ContractSchema.FromText("""{"type":"string","format":"email"}"""));
        Assert.Throws<InvalidOperationException>(() => ContractSchema.FromText("""{"type":"object","additionalProperties":{"type":"string"}}"""));
    }

    [Fact]
    public void EveryContractSchemaLoads()
    {
        var files = Directory.GetFiles(SchemaResources.ContractsDirectory, "*.schema.json").Select(Path.GetFileName).Order().ToList();
        // A known list instead of a count: a new schema must not break this test, a deleted required one must.
        var required = new[]
        {
            "document.schema.json", "document-render-request.schema.json", "document-render-result.schema.json",
            "html-renderer-request.schema.json", "html-renderer-result.schema.json", "html-reorder-request.schema.json",
            "html-reorder-result.schema.json", "html-semantic-request.schema.json", "html-semantic-result.schema.json",
            "pdf-inspect-result.schema.json", "pdf-reorder-request.schema.json", "pdf-reorder-result.schema.json",
            "pdf-structure-result.schema.json", "pdf-worker-request.schema.json", "pdf-worker-result.schema.json",
            "web-worker-request.schema.json", "web-worker-result.schema.json",
        };
        foreach (var name in required)
        {
            Assert.Contains(name, files);
        }
        foreach (var file in files)
        {
            Assert.NotNull(SchemaResources.Load(file!));
        }
    }

    [Fact]
    public async Task TheCommittedPdfWorkerRequestExampleIsValid()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "contracts", "examples", "pdf-worker-request.fix.json")))
        {
            dir = dir.Parent;
        }
        Assert.NotNull(dir);
        var example = Path.Combine(dir.FullName, "contracts", "examples", "pdf-worker-request.fix.json");
        var schema = SchemaResources.Load("pdf-worker-request.schema.json");
        Assert.Empty(schema.Validate(await File.ReadAllTextAsync(example)));
        Assert.NotEmpty(schema.Validate("{}"));
    }

    private static string Mutate(string json, string find, string replace)
    {
        Assert.Contains(find, json);
        return json.Replace(find, replace, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("^a$", "a\n", false)] // ECMA: $ is the end of the text only, not before a final newline
    [InlineData("^a$", "a", true)]
    [InlineData("^[a$]+$", "a$", true)] // inside a class $ is a literal
    [InlineData("^a\\$$", "a$", true)] // an escaped $ stays a literal
    [InlineData("^a\\$$", "a\n", false)]
    [InlineData("^(?!.*(^|/)x(/|$))[a-z/]+$", "ax/\n", false)]
    public void PatternDollarMeansEndOfText(string pattern, string value, bool valid)
    {
        var schema = ContractSchema.FromText(System.Text.Json.JsonSerializer.Serialize(new { type = "string", pattern }));
        Assert.Equal(valid, schema.Validate(System.Text.Json.JsonSerializer.Serialize(value)).Count == 0);
    }
}
