namespace Koldera.Core.Workers;

/// <summary>Creates the worker processes of each kind; the server and the command-line tool share the real one, tests inject stubs.</summary>
public interface IWorkerFactory
{
    PdfWorkers Pdf();
    HtmlWorkers Html();
    WebWorkers Web();
    DocumentWorkers Document();
}

/// <summary>Workers that run the configured commands as subprocesses.</summary>
public sealed class ProcessWorkerFactory(WorkerOptions w) : IWorkerFactory
{
    public PdfWorkers Pdf() => new(
        SubprocessWorker.ForPdfProcess(w.PdfCommand, w.VeraPdfCommand),
        w.PdfStructureCommand.Length > 0 ? SubprocessWorker.ForProbe(w.PdfStructureCommand) : null);

    public HtmlWorkers Html() => new(
        SubprocessWorker.ForNodeVerb(w.HtmlRenderCommand, w.ContractsDir),
        SubprocessWorker.ForProbe(w.PdfInspectCommand),
        w.HtmlSemanticCommand.Length > 0 ? SubprocessWorker.ForNodeVerb(w.HtmlSemanticCommand, w.ContractsDir) : null);

    public WebWorkers Web() => new(SubprocessWorker.ForWeb(w.WebCommand, w.ContractsDir, w.AllowLoopbackForTests));

    public DocumentWorkers Document() => new(SubprocessWorker.ForNodeVerb(w.DocumentRenderCommand, w.ContractsDir));
}
