using System.Text.Json;
using Koldera.Core.Documents;

namespace Koldera.Core.Tests;

/// <summary>
/// The input format of structured-document jobs: the schema catches the shape, the validator adds the
/// rules a schema cannot express (accessibility basics, language tags, bundled fonts). Every error
/// carries a JSON Pointer so a caller can find the offending block.
/// </summary>
public class DocumentValidatorTests
{
    static JsonElement Doc(string body) => JsonDocument.Parse($$"""{"title":"T","language":"uk","body":{{body}}}""").RootElement;

    [Theory]
    [InlineData("""[{"type":"image","uploadId":"00000000-0000-4000-8000-000000000001"}]""", "IMAGE_ALT_MISSING", "/body/0")]
    [InlineData("""[{"type":"image","uploadId":"00000000-0000-4000-8000-000000000001","alt":"  "}]""", "IMAGE_ALT_MISSING", "/body/0")]
    [InlineData("""[{"type":"heading","level":1,"text":"A"},{"type":"heading","level":3,"text":"B"}]""", "HEADING_LEVEL_SKIPPED", "/body/1")]
    [InlineData("""[{"type":"heading","level":3,"text":"A"}]""", "HEADING_LEVEL_SKIPPED", "/body/0")]
    [InlineData("""[{"type":"table","caption":"C","header":[[]],"rows":[["1"]]}]""", "TABLE_HEADER_EMPTY", "/body/0/header")]
    [InlineData("""[{"type":"table","caption":"C","header":[],"rows":[["1"]]}]""", "TABLE_HEADER_EMPTY", "/body/0/header")]
    [InlineData("""[{"type":"table","caption":"C","header":[["", " "]],"rows":[["1","2"]]}]""", "TABLE_HEADER_EMPTY", "/body/0/header")]
    [InlineData("""[{"type":"link","text":"  ","url":"https://example.com"}]""", "LINK_TEXT_EMPTY", "/body/0/text")]
    [InlineData("""[{"type":"paragraph","content":[{"link":{"text":"","url":"https://example.com"}}]}]""", "LINK_TEXT_EMPTY", "/body/0/content/0/link/text")]
    [InlineData("""[{"type":"paragraph","content":["x"],"lang":"not a tag"}]""", "LANGUAGE_INVALID", "/body/0/lang")]
    [InlineData("""[{"type":"paragraph","content":["x"],"font":"Comic Sans"}]""", "FONT_UNKNOWN", "/body/0/font")]
    [InlineData("""[{"type":"list","items":[["a",{"list":{"items":[[{"link":{"text":" ","url":"mailto:a@example.com"}}]]}}]]}]""", "LINK_TEXT_EMPTY", "/body/0/items/0/1/list/items/0/0/link/text")]
    public void Reports_the_rule_and_path(string body, string code, string path)
    {
        var errors = DocumentValidator.Validate(Doc(body));
        Assert.Contains(errors, e => e.Code == code && e.Path == path);
    }

    [Fact]
    public void Unknown_font_and_bad_language_are_reported()
    {
        var doc = JsonDocument.Parse("""{"title":"T","language":"not a tag","theme":{"font":"Comic Sans"},"body":[]}""").RootElement;
        var errors = DocumentValidator.Validate(doc);
        Assert.Contains(errors, e => e.Code == "FONT_UNKNOWN" && e.Path == "/theme/font");
        Assert.Contains(errors, e => e.Code == "LANGUAGE_INVALID" && e.Path == "/language");
    }

    [Theory]
    [InlineData("invoice")]
    [InlineData("report")]
    [InlineData("multilingual")]
    [InlineData("statement")]
    public void Golden_fixtures_are_valid(string name) =>
        Assert.Empty(DocumentValidator.Validate(JsonDocument.Parse(File.ReadAllText($"Fixtures/documents/{name}.json")).RootElement));

    [Fact]
    public void Decorative_image_needs_no_alt() =>
        Assert.Empty(DocumentValidator.Validate(Doc("""[{"type":"image","uploadId":"00000000-0000-4000-8000-000000000001","decorative":true}]""")));

    [Fact]
    public void Heading_may_start_at_two_and_go_up_any_amount()
    {
        // Going back up (3 -> 1) is fine, only descending by more than one level skips.
        var body = """[{"type":"heading","level":2,"text":"A"},{"type":"heading","level":3,"text":"B"},{"type":"heading","level":1,"text":"C"},{"type":"heading","level":2,"text":"D"}]""";
        Assert.Empty(DocumentValidator.Validate(Doc(body)));
    }

    [Fact]
    public void Headings_in_header_and_footer_do_not_count()
    {
        var doc = JsonDocument.Parse("""
            {"title":"T","language":"en","header":[{"type":"heading","level":5,"text":"H"}],"footer":[{"type":"heading","level":6,"text":"F"},{"type":"pageNumber"}],
             "body":[{"type":"heading","level":1,"text":"A"},{"type":"heading","level":2,"text":"B"}]}
            """).RootElement;
        Assert.Empty(DocumentValidator.Validate(doc));
    }

    [Fact]
    public void Rules_also_apply_inside_header_and_footer()
    {
        var doc = JsonDocument.Parse("""
            {"title":"T","language":"en","header":[{"type":"link","text":"","url":"https://example.com"}],"footer":[{"type":"image","file":"a.png"}],"body":[]}
            """).RootElement;
        var errors = DocumentValidator.Validate(doc);
        Assert.Contains(errors, e => e.Code == "LINK_TEXT_EMPTY" && e.Path == "/header/0/text");
        Assert.Contains(errors, e => e.Code == "IMAGE_ALT_MISSING" && e.Path == "/footer/0");
    }

    [Theory]
    [InlineData("""{"title":"","language":"en","body":[]}""", "/title")]
    [InlineData("""{"title":"T","body":[]}""", "")]
    [InlineData("""{"title":"T","language":"en","body":[],"extra":1}""", "")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"heading","level":7,"text":"A"}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"nope"}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"link","text":"a","url":"javascript:alert(1)"}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","theme":{"fontSize":30},"body":[]}""", "/theme/fontSize")]
    [InlineData("""{"title":"T","language":"en","theme":{"accent":"red"},"body":[]}""", "/theme/accent")]
    [InlineData("""{"title":"T","language":"en","page":{"size":"B5"},"body":[]}""", "/page/size")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"spacer","height":"tall"}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"table","caption":"C","header":[["A"]],"rows":[["1"]],"columnAlign":["middle"]}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"keyValue","rows":[["only one"]]}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"image","file":"../secret.png","alt":"x"}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"image","uploadId":"nope","alt":"x"}]}""", "/body/0")]
    [InlineData("""{"title":"T","language":"en","body":[{"type":"pageNumber"}]}""", "/body/0")]
    public void Structural_violations_are_reported_as_SCHEMA_with_a_JSON_Pointer(string json, string path)
    {
        var errors = DocumentValidator.Validate(JsonDocument.Parse(json).RootElement);
        Assert.Contains(errors, e => e.Code == "SCHEMA" && e.Path == path);
        Assert.All(errors, e => Assert.Equal("SCHEMA", e.Code)); // semantic rules wait until the shape is right
    }

    [Theory]
    [InlineData("""{"type":"image","alt":"x"}""")]
    [InlineData("""{"type":"image","uploadId":"00000000-0000-4000-8000-000000000001","file":"a.png","alt":"x"}""")]
    [InlineData("""{"type":"image","file":"a.png","alt":"x","decorative":true}""")]
    public void Image_needs_exactly_one_source_and_not_both_alt_and_decorative(string block)
    {
        var errors = DocumentValidator.Validate(Doc($"[{block}]"));
        Assert.Contains(errors, e => e.Code == "SCHEMA" && e.Path == "/body/0");
    }

    [Fact]
    public void Footer_accepts_a_page_number_but_the_header_does_not()
    {
        var ok = JsonDocument.Parse("""{"title":"T","language":"en","footer":[{"type":"pageNumber"}],"body":[]}""").RootElement;
        Assert.Empty(DocumentValidator.Validate(ok));
        var bad = JsonDocument.Parse("""{"title":"T","language":"en","header":[{"type":"pageNumber"}],"body":[]}""").RootElement;
        Assert.Contains(DocumentValidator.Validate(bad), e => e.Code == "SCHEMA" && e.Path == "/header/0");
    }

    [Theory]
    [InlineData("uk")]
    [InlineData("en-US")]
    [InlineData("ar")]
    [InlineData("zh-Hans-CN")]
    [InlineData("fil")]
    public void Accepts_language_tags(string tag) =>
        Assert.Empty(DocumentValidator.Validate(JsonDocument.Parse($$"""{"title":"T","language":"{{tag}}","body":[]}""").RootElement));

    [Theory]
    [InlineData("e")]
    [InlineData("en_US")]
    [InlineData("en-")]
    [InlineData("en US")]
    public void Rejects_language_tags(string tag) =>
        Assert.Contains(DocumentValidator.Validate(JsonDocument.Parse($$"""{"title":"T","language":"{{tag}}","body":[]}""").RootElement), e => e.Code == "LANGUAGE_INVALID" && e.Path == "/language");

    [Fact]
    public void Every_bundled_font_is_accepted()
    {
        foreach (var font in new[] { "Noto Sans", "Noto Serif", "Noto Sans Arabic", "Noto Sans Hebrew" })
        {
            var doc = JsonDocument.Parse($$"""{"title":"T","language":"en","theme":{"font":"{{font}}"},"body":[]}""").RootElement;
            Assert.Empty(DocumentValidator.Validate(doc));
        }
    }
}
