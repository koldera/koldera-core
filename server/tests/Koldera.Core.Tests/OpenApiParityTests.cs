using System.Text.Json;
using System.Text.RegularExpressions;
using Koldera.Core.Tests.Support;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;

namespace Koldera.Core.Tests;

/// <summary>contracts/openapi.yaml describes exactly the routes the server registers, and its problem codes match problem.schema.json and the source.</summary>
public sealed partial class OpenApiParityTests
{
    private static readonly string[] Methods = ["get", "post", "put", "delete", "patch", "head", "options"];

    // Codes the source picks through a variable or a switch, so the literal scan below cannot see them.
    private static readonly string[] IndirectCodes =
    [
        "UPLOAD_ALREADY_COMPLETED", "UPLOAD_CONFLICT", "UPLOAD_EXPIRED", "UPLOAD_NOT_FOUND", "SIZE_MISMATCH", "CHECKSUM_MISMATCH",
        "ORDER_FIX_UNSUPPORTED", "STALE_READING_ORDER_SNAPSHOT", "FILE_IMAGE_NOT_ALLOWED", "IMAGE_UPLOAD_INVALID",
    ];

    [GeneratedRegex(@"^  (/[^\s:]*):\s*$")]
    private static partial Regex PathLine();

    [GeneratedRegex(@"^    (get|post|put|delete|patch|head|options):\s*$")]
    private static partial Regex MethodLine();

    [GeneratedRegex(@"Problems\.Result\(\s*\d+,\s*""([A-Z][A-Z0-9_]+)""")]
    private static partial Regex ProblemLiteral();

    private static string RepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "contracts", "openapi.yaml"))) dir = dir.Parent;
        return dir?.FullName ?? throw new FileNotFoundException("contracts/openapi.yaml not found above " + AppContext.BaseDirectory);
    }

    private static HashSet<string> DocumentedOperations(string yaml)
    {
        var found = new HashSet<string>(StringComparer.Ordinal);
        var inPaths = false;
        string? path = null;
        foreach (var line in yaml.Split('\n').Select(l => l.TrimEnd('\r')))
        {
            if (line.Length > 0 && !char.IsWhiteSpace(line[0])) { inPaths = line.StartsWith("paths:"); path = null; continue; }
            if (!inPaths) continue;
            if (PathLine().Match(line) is { Success: true } p) { path = p.Groups[1].Value; continue; }
            if (path is not null && MethodLine().Match(line) is { Success: true } m) found.Add(m.Groups[1].Value.ToUpperInvariant() + " " + path);
        }
        return found;
    }

    private static HashSet<string> RegisteredOperations()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t1" });
        f.CreateClient();
        var ops = new HashSet<string>(StringComparer.Ordinal);
        foreach (var endpoint in f.Services.GetServices<EndpointDataSource>().SelectMany(s => s.Endpoints).OfType<RouteEndpoint>())
        {
            var template = "/" + endpoint.RoutePattern.RawText!.TrimStart('/');
            template = Regex.Replace(template, @"\{(\w+)(:\w+)?\}", "{$1}").TrimEnd('/');
            if (template.Length == 0) template = "/";
            foreach (var method in endpoint.Metadata.GetMetadata<HttpMethodMetadata>()?.HttpMethods ?? [])
                ops.Add(method + " " + template);
        }
        return ops;
    }

    [Fact]
    public void Every_registered_route_is_documented_and_every_documented_route_is_registered()
    {
        var documented = DocumentedOperations(File.ReadAllText(Path.Combine(RepoRoot(), "contracts", "openapi.yaml")));
        var registered = RegisteredOperations();

        Assert.NotEmpty(documented);
        Assert.Empty(registered.Except(documented).Order());
        Assert.Empty(documented.Except(registered).Order());
    }

    private static string[] OpenApiProblemCodes(string yaml)
    {
        var codes = new List<string>();
        var inEnum = false;
        foreach (var line in yaml.Split('\n').Select(l => l.TrimEnd('\r')))
        {
            if (line.TrimStart() == "# problem-codes:begin") { inEnum = true; continue; }
            if (line.TrimStart() == "# problem-codes:end") break;
            if (inEnum && Regex.Match(line, @"^\s*- ([A-Z][A-Z0-9_]+)\s*$") is { Success: true } m) codes.Add(m.Groups[1].Value);
        }
        return [.. codes];
    }

    [Fact]
    public void Problem_codes_agree_between_openapi_the_problem_schema_and_the_source()
    {
        var root = RepoRoot();
        var fromOpenApi = OpenApiProblemCodes(File.ReadAllText(Path.Combine(root, "contracts", "openapi.yaml")));
        using var schema = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, "contracts", "problem.schema.json")));
        var fromSchema = schema.RootElement.GetProperty("properties").GetProperty("code").GetProperty("enum").EnumerateArray().Select(e => e.GetString()!).ToArray();

        Assert.NotEmpty(fromOpenApi);
        Assert.Equal(fromOpenApi.Order(), fromSchema.Order());
        Assert.Equal(fromOpenApi.Length, fromOpenApi.Distinct().Count());

        var source = Directory.EnumerateFiles(Path.Combine(root, "server", "src"), "*.cs", SearchOption.AllDirectories)
            .Where(p => !p.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}") && !p.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}"))
            .SelectMany(p => ProblemLiteral().Matches(File.ReadAllText(p)).Select(m => m.Groups[1].Value))
            .Concat(IndirectCodes)
            .Distinct()
            .Order();
        Assert.Empty(source.Except(fromOpenApi));
    }
}
