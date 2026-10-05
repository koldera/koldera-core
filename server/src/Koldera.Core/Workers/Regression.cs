using System.Text.Json;

namespace Koldera.Core.Workers;

/// <summary>veraPDF rule-set comparison for the order-correction gate: any rule that fails after the change but not before is a regression.</summary>
public static class Regression
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private sealed record Stored(string? Profile, string? Status, List<Rule>? FailedRules);

    private sealed record Rule(string RuleId, int Count);

    /// <summary>A content-free description of what got worse, or null when the corrected copy is no worse than the reference.</summary>
    public static string? Describe(string? baselineJson, string? afterJson)
    {
        var before = Parse(baselineJson);
        var after = Parse(afterJson);
        if (after is null) return "no validation result for the corrected copy";
        if (after.Status == "error") return "the validator did not produce a result for the corrected copy";
        var beforeRules = (before?.FailedRules ?? []).ToDictionary(r => r.RuleId, r => r.Count);
        var problems = new List<string>();
        foreach (var rule in after.FailedRules ?? [])
        {
            if (!beforeRules.TryGetValue(rule.RuleId, out var count))
                problems.Add($"new failure {rule.RuleId} x{rule.Count}");
            else if (rule.Count > count)
                problems.Add($"{rule.RuleId} {count} -> {rule.Count}");
        }
        return problems.Count == 0 ? null : string.Join("; ", problems);
    }

    private static Stored? Parse(string? json)
    {
        if (string.IsNullOrEmpty(json)) return null;
        try
        {
            return JsonSerializer.Deserialize<Stored>(json, Json);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
