namespace Koldera.Core.Cli;

/// <summary>A parsed command line: the command, its single positional target and its options.</summary>
internal sealed record CliCommand(
    string Name,
    string Target,
    bool Fix,
    bool AnalyzeOnly,
    string? Language,
    string? Title,
    string? ContentSelector,
    string? OutDir,
    bool Json,
    bool Help = false);

/// <summary>Hand-written argument parsing and help text (no argument-parsing dependency).</summary>
internal static class CliOptions
{
    public static readonly string[] Commands = ["pdf", "html", "scan", "document"];

    private sealed record Flag(string Name, bool TakesValue);

    private static readonly Flag Out = new("--out", true);
    private static readonly Flag Json = new("--json", false);

    private static IReadOnlyList<Flag> FlagsOf(string command) => command switch
    {
        "pdf" => [new("--fix", false), new("--language", true), new("--title", true), Out, Json],
        "html" => [new("--analyze-only", false), new("--content-selector", true), Out, Json],
        _ => [Out, Json],
    };

    public const string GeneralUsage = """
        usage: koldera [serve]
               koldera pdf <file> [--fix] [--language <tag>] [--title <text>] [--out <dir>] [--json]
               koldera html <file> [--analyze-only] [--content-selector <css>] [--out <dir>] [--json]
               koldera scan <url> [--out <dir>] [--json]
               koldera document <file.json> [--out <dir>] [--json]
               koldera --help | <command> --help | --version
        """;

    public static string UsageOf(string command) => command switch
    {
        "pdf" => "usage: koldera pdf <file> [--fix] [--language <tag>] [--title <text>] [--out <dir>] [--json]",
        "html" => "usage: koldera html <file> [--analyze-only] [--content-selector <css>] [--out <dir>] [--json]",
        "scan" => "usage: koldera scan <url> [--out <dir>] [--json]",
        "document" => "usage: koldera document <file.json> [--out <dir>] [--json]",
        _ => GeneralUsage,
    };

    public static string HelpOf(string command) => command switch
    {
        "pdf" => UsageOf(command) + """


            Checks a PDF against the PDF/UA-1 machine rules. With --fix a repaired copy is written to --out
            as <name>.remediated.pdf; the input is never modified.
              --fix              write a repaired copy (default: analyze only)
              --language <tag>   BCP 47 language to use when the document has none
              --title <text>     document title to use (1-500 printable characters)
              --out <dir>        where artifacts are written (default: current directory)
              --json             print {job, findings, actions, artifacts} as JSON; nothing else on stdout
            """,
        "html" => UsageOf(command) + """


            Renders one static HTML file to a tagged PDF and checks it. Nothing is fetched from the network.
              --analyze-only          report only (default: also apply the safe fixes)
              --content-selector <c>  render only the element matching this CSS selector
              --out <dir>             where artifacts are written (default: current directory)
              --json                  print {job, findings, actions, artifacts} as JSON; nothing else on stdout
            """,
        "scan" => UsageOf(command) + """


            Scans a web page with automated accessibility checks (a subset of WCAG; not a conformance claim).
              --out <dir>   where the report is written (default: current directory)
              --json        print {job, findings, actions, artifacts} as JSON; nothing else on stdout
            """,
        "document" => UsageOf(command) + """


            Renders a structured document (JSON) to a tagged PDF and checks it. An image names a "file" path,
            resolved relative to the JSON file; paths outside that directory are refused.
              --out <dir>   where artifacts are written (default: current directory)
              --json        print {job, findings, actions, artifacts} as JSON; nothing else on stdout
            """,
        _ => GeneralUsage + """


            Commands:
              serve      start the HTTP server (default without arguments)
              pdf        check or repair a PDF
              html       render HTML to a tagged PDF
              scan       scan a web page
              document   render a structured document to a tagged PDF

            Exit codes: 0 machine check passed, 2 completed with failed machine rules,
            3 job failed, unsupported or no machine verdict, 64 usage error.
            """,
    };

    /// <summary>Parses <c>args</c> (the command first); <paramref name="error"/> says what is wrong when null is returned.</summary>
    public static CliCommand? Parse(string[] args, out string? error)
    {
        error = null;
        var name = args[0];
        var flags = FlagsOf(name);
        string? target = null;
        bool fix = false, analyzeOnly = false, json = false;
        string? language = null, title = null, selector = null, outDir = null;
        var optionsEnded = false;

        for (var i = 1; i < args.Length; i++)
        {
            var arg = args[i];
            // A standalone help token asks for help; one consumed as a flag value (below) or after "--" does not.
            if (!optionsEnded && arg is "--help" or "-h") return new CliCommand(name, "", false, false, null, null, null, null, false, Help: true);
            if (!optionsEnded && arg == "--") { optionsEnded = true; continue; }
            if (optionsEnded || !arg.StartsWith('-'))
            {
                if (target is not null) { error = $"unexpected extra argument: {arg}"; return null; }
                target = arg;
                continue;
            }
            var eq = arg.IndexOf('=');
            var flagName = eq > 0 ? arg[..eq] : arg;
            var flag = flags.FirstOrDefault(f => f.Name == flagName);
            if (flag is null) { error = $"unknown option for {name}: {flagName}"; return null; }

            string? value = null;
            if (flag.TakesValue)
            {
                if (eq > 0) value = arg[(eq + 1)..];
                else if (i + 1 < args.Length) value = args[++i];
                else { error = $"{flagName} needs a value"; return null; }
            }
            else if (eq > 0) { error = $"{flagName} takes no value"; return null; }

            switch (flagName)
            {
                case "--fix": fix = true; break;
                case "--analyze-only": analyzeOnly = true; break;
                case "--json": json = true; break;
                case "--language": language = value; break;
                case "--title": title = value; break;
                case "--content-selector": selector = value; break;
                case "--out":
                    if (string.IsNullOrWhiteSpace(value)) { error = "--out needs a non-empty directory"; return null; }
                    outDir = value;
                    break;
            }
        }

        if (target is null)
        {
            error = name == "scan" ? "a URL is required" : "an input file is required";
            return null;
        }
        return new CliCommand(name, target, fix, analyzeOnly, language, title, selector, outDir, json);
    }
}
