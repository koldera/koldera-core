using System.Text.Json;
using System.Text.RegularExpressions;
using Koldera.Core.Documents;

namespace Koldera.Core.Tests;

/// <summary>The complete example in docs/documents.md must pass the validator, so the documentation cannot drift from it.</summary>
public sealed partial class DocumentationExampleTests
{
    [GeneratedRegex("```json\r?\n(.*?)\r?\n```", RegexOptions.Singleline)]
    private static partial Regex JsonFence();

    [Fact]
    public void The_example_in_documents_md_is_a_valid_document()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "docs", "documents.md"))) dir = dir.Parent;
        var text = File.ReadAllText(Path.Combine(dir!.FullName, "docs", "documents.md"));
        using var document = JsonDocument.Parse(JsonFence().Match(text).Groups[1].Value);

        Assert.Empty(DocumentValidator.Validate(document.RootElement));
    }
}
