namespace Koldera.Core.Workers;

/// <summary>
/// How to start the worker processes. Each command is a launcher argv including the CLI verb; the
/// request/result paths are appended by <see cref="SubprocessWorker"/>. Limits are not here: they
/// come from <see cref="CoreOptions"/> and a missing limit means unlimited.
/// </summary>
public sealed class WorkerOptions
{
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
    public string[] WebCommand { get; set; } = [];

    /// <summary>Forwarded to the Node workers; null when KOLDERA_CONTRACTS_DIR is not set.</summary>
    public string? ContractsDir { get; set; } = Environment.GetEnvironmentVariable("KOLDERA_CONTRACTS_DIR") is { Length: > 0 } d ? d : null;

    /// <summary>Test-only switch forwarded to the scanner; never set in production.</summary>
    public bool AllowLoopbackForTests { get; set; }
}
