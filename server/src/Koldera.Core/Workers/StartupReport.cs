namespace Koldera.Core.Workers;

/// <summary>One line for the startup log: the enabled features and whether each worker tool is on disk. Never contains secrets.</summary>
public static class StartupReport
{
    public static string Describe(CoreOptions options, WorkerOptions workers)
    {
        var f = options.Features;
        var parts = new List<(string Name, string Path)>();
        if (f.Contains("pdf") || f.Contains("html") || f.Contains("document"))
        {
            parts.Add(("engine", ToolPath(workers.PdfCommand)));
            parts.Add(("verapdf", workers.VeraPdfCommand ?? WorkerOptions.DefaultVeraPdf));
        }
        if (f.Contains("html") || f.Contains("document")) parts.Add(("renderer", ToolPath(f.Contains("html") ? workers.HtmlRenderCommand : workers.DocumentRenderCommand)));
        if (f.Contains("web")) parts.Add(("scanner", ToolPath(workers.WebCommand)));
        if (f.Contains("document")) parts.Add(("typst", options.TypstCommand));
        var tools = string.Join(", ", parts.Select(p => $"{p.Name} {p.Path} {(Exists(p.Path) ? "found" : "missing")}"));
        return $"features: {string.Join(", ", f.Order())}; workers: {tools}";
    }

    /// <summary>The script or jar a launcher argv runs: the first argument after the launcher that is not an option.</summary>
    internal static string ToolPath(string[] command) =>
        command.Length == 0 ? "" : command.Length == 1 ? command[0] : command.Skip(1).FirstOrDefault(a => !a.StartsWith('-')) ?? command[0];

    private static bool Exists(string path)
    {
        if (path.Length == 0) return false;
        if (File.Exists(path)) return true;
        if (path.Contains('/') || path.Contains('\\')) return false;
        // A bare command name is looked up on PATH, as the OS would.
        var extensions = OperatingSystem.IsWindows() ? (Environment.GetEnvironmentVariable("PATHEXT") ?? ".EXE").Split(';').Prepend("") : [""];
        return (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Any(dir => extensions.Any(e => File.Exists(Path.Combine(dir, path + e))));
    }
}
