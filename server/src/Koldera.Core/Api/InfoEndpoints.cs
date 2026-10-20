using System.IO.Compression;
using System.Reflection;
using Koldera.Core.Workers;

namespace Koldera.Core;

public static class InfoEndpoints
{
    public static void MapInfoEndpoints(this RouteGroupBuilder v1)
    {
        v1.MapGet("/info", (CoreOptions options, WorkerOptions workers) => Results.Json(new
        {
            version = typeof(InfoEndpoints).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0],
            engineVersion = EngineVersion(StartupReport.ToolPath(workers.PdfCommand)),
            features = options.Features.Order().ToArray(),
        }));
    }

    /// <summary>
    /// The engine's own version record (<c>engine-version.properties</c> inside its jar), in the form job results carry
    /// (<c>koldera-pdf-engine 0.1.0; pdfbox 3.0.8</c>); null when the jar or the record cannot be read.
    /// </summary>
    internal static string? EngineVersion(string jar)
    {
        try
        {
            if (!File.Exists(jar)) return null;
            using var zip = ZipFile.OpenRead(jar);
            using var reader = new StreamReader(zip.GetEntry("engine-version.properties")?.Open() ?? Stream.Null);
            var properties = new Dictionary<string, string>();
            while (reader.ReadLine() is { } line)
                if (line.Split('=', 2) is [var key, var value]) properties[key.Trim()] = value.Trim();
            return properties.TryGetValue("engine.version", out var engine) && engine.Length > 0
                ? "koldera-pdf-engine " + engine + (properties.TryGetValue("pdfbox.version", out var pdfbox) ? "; pdfbox " + pdfbox : "")
                : null;
        }
        catch (Exception e) when (e is IOException or InvalidDataException or UnauthorizedAccessException)
        {
            return null;
        }
    }
}
