namespace Koldera.Core.Workers;

/// <summary>
/// How to start the worker processes: each command is a launcher argv including the CLI verb; request/result
/// paths are appended by <see cref="SubprocessWorker"/>. Empty commands are filled by <see cref="ApplyDefaults"/>.
/// </summary>
public sealed class WorkerOptions
{
    public const string DefaultEngineJar = "/app/engine/koldera-pdf-engine.jar";
    public const string DefaultRendererCli = "/app/renderer/dist/cli.js";
    public const string DefaultScannerCli = "/app/scanner/dist/cli.js";
    public const string DefaultVeraPdf = "/opt/verapdf/verapdf";

    /// <summary>e.g. <c>["java","-jar","/app/koldera-pdf-engine.jar","process"]</c>.</summary>
    public string[] PdfCommand { get; set; } = [];

    /// <summary>Read-only <c>structure</c> verb of the PDF engine (<c>--input/--result</c>).</summary>
    public string[] PdfStructureCommand { get; set; } = [];

    /// <summary>Read-only <c>inspect</c> verb of the PDF engine (<c>--input/--result</c>).</summary>
    public string[] PdfInspectCommand { get; set; } = [];

    /// <summary><c>reorder</c> verb of the PDF engine (request/result envelope).</summary>
    public string[] PdfReorderCommand { get; set; } = [];

    /// <summary>Passed to the PDF engine as VERAPDF_CMD.</summary>
    public string? VeraPdfCommand { get; set; }

    public string[] HtmlRenderCommand { get; set; } = [];
    public string[] HtmlSemanticCommand { get; set; } = [];
    public string[] HtmlReorderCommand { get; set; } = [];
    /// <summary>e.g. <c>["node","/app/renderer/dist/cli.js","document"]</c>.</summary>
    public string[] DocumentRenderCommand { get; set; } = [];
    public string[] WebCommand { get; set; } = [];

    /// <summary>Forwarded to the Node workers; null when KOLDERA_CONTRACTS_DIR is not set.</summary>
    public string? ContractsDir { get; set; } = Environment.GetEnvironmentVariable("KOLDERA_CONTRACTS_DIR") is { Length: > 0 } d ? d : null;

    /// <summary>Test-only switch forwarded to the scanner; never set in production.</summary>
    public bool AllowLoopbackForTests { get; set; }

    /// <summary>
    /// Fills every empty command (and the veraPDF path) from the environment, else from the container layout:
    /// KOLDERA_ENGINE_JAR, KOLDERA_RENDERER_CLI, KOLDERA_SCANNER_CLI, VERAPDF_CMD, KOLDERA_JAVA (default "java"), KOLDERA_NODE (default "node").
    /// A value that is already set (the "Workers" configuration) is kept.
    /// </summary>
    public WorkerOptions ApplyDefaults(Func<string, string?> env)
    {
        string Get(string name, string fallback) => string.IsNullOrWhiteSpace(env(name)) ? fallback : env(name)!.Trim();

        var java = Get("KOLDERA_JAVA", "java");
        var node = Get("KOLDERA_NODE", "node");
        var jar = Get("KOLDERA_ENGINE_JAR", DefaultEngineJar);
        var renderer = Get("KOLDERA_RENDERER_CLI", DefaultRendererCli);
        var scanner = Get("KOLDERA_SCANNER_CLI", DefaultScannerCli);

        string[] Engine(string verb) => [java, "-jar", jar, verb];
        string[] Renderer(string verb) => [node, renderer, verb];

        if (PdfCommand.Length == 0) PdfCommand = Engine("process");
        if (PdfStructureCommand.Length == 0) PdfStructureCommand = Engine("structure");
        if (PdfInspectCommand.Length == 0) PdfInspectCommand = Engine("inspect");
        if (PdfReorderCommand.Length == 0) PdfReorderCommand = Engine("reorder");
        if (HtmlRenderCommand.Length == 0) HtmlRenderCommand = Renderer("render");
        if (HtmlSemanticCommand.Length == 0) HtmlSemanticCommand = Renderer("semantic");
        if (HtmlReorderCommand.Length == 0) HtmlReorderCommand = Renderer("reorder");
        if (DocumentRenderCommand.Length == 0) DocumentRenderCommand = Renderer("document");
        if (WebCommand.Length == 0) WebCommand = [node, scanner, "scan"];
        if (string.IsNullOrWhiteSpace(VeraPdfCommand)) VeraPdfCommand = Get("VERAPDF_CMD", DefaultVeraPdf);
        return this;
    }
}
