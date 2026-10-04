using System.Reflection;

namespace Koldera.Core;

public static class InfoEndpoints
{
    public static void MapInfoEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/healthz", () => Results.Json(new { status = "ok" }));
        app.MapGet("/v1/info", (CoreOptions options) => Results.Json(new
        {
            version = typeof(InfoEndpoints).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0],
            engineVersion = ReadEngineVersion(),
            features = options.Features.Order().ToArray(),
        }));
    }

    private static string? ReadEngineVersion()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "engine-version.properties");
        if (!File.Exists(path)) return null;
        foreach (var line in File.ReadLines(path))
            if (line.StartsWith("version=", StringComparison.Ordinal)) return line["version=".Length..].Trim();
        return null;
    }
}
