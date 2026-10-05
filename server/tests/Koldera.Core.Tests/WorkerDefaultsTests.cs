using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

public class WorkerDefaultsTests
{
    private static Func<string, string?> Env(params (string, string)[] vars) =>
        name => vars.Where(v => v.Item1 == name).Select(v => v.Item2).FirstOrDefault();

    [Fact]
    public void Without_any_setting_the_commands_follow_the_container_layout()
    {
        var w = new WorkerOptions().ApplyDefaults(Env());
        Assert.Equal(["java", "-jar", "/app/engine/koldera-pdf-engine.jar", "process"], w.PdfCommand);
        Assert.Equal(["java", "-jar", "/app/engine/koldera-pdf-engine.jar", "structure"], w.PdfStructureCommand);
        Assert.Equal(["java", "-jar", "/app/engine/koldera-pdf-engine.jar", "inspect"], w.PdfInspectCommand);
        Assert.Equal(["java", "-jar", "/app/engine/koldera-pdf-engine.jar", "reorder"], w.PdfReorderCommand);
        Assert.Equal(["node", "/app/renderer/dist/cli.js", "render"], w.HtmlRenderCommand);
        Assert.Equal(["node", "/app/renderer/dist/cli.js", "semantic"], w.HtmlSemanticCommand);
        Assert.Equal(["node", "/app/renderer/dist/cli.js", "reorder"], w.HtmlReorderCommand);
        Assert.Equal(["node", "/app/renderer/dist/cli.js", "document"], w.DocumentRenderCommand);
        Assert.Equal(["node", "/app/scanner/dist/cli.js", "scan"], w.WebCommand);
        Assert.Equal("/opt/verapdf/verapdf", w.VeraPdfCommand);
        Assert.Equal("/opt/typst/typst", CoreOptions.From(_ => null).TypstCommand);
    }

    [Fact]
    public void Simple_environment_variables_move_the_paths_and_launchers()
    {
        var w = new WorkerOptions().ApplyDefaults(Env(
            ("KOLDERA_ENGINE_JAR", "/x/engine.jar"), ("KOLDERA_RENDERER_CLI", "/x/r.js"), ("KOLDERA_SCANNER_CLI", "/x/s.js"),
            ("VERAPDF_CMD", "/x/verapdf"), ("KOLDERA_JAVA", "/jdk/bin/java"), ("KOLDERA_NODE", "/n/node")));
        Assert.Equal(["/jdk/bin/java", "-jar", "/x/engine.jar", "process"], w.PdfCommand);
        Assert.Equal(["/n/node", "/x/r.js", "document"], w.DocumentRenderCommand);
        Assert.Equal(["/n/node", "/x/s.js", "scan"], w.WebCommand);
        Assert.Equal("/x/verapdf", w.VeraPdfCommand);
    }

    [Fact]
    public void Workers_configuration_wins_over_the_environment_and_the_defaults()
    {
        var w = new WorkerOptions { PdfCommand = ["custom", "process"], VeraPdfCommand = "/cfg/verapdf" }
            .ApplyDefaults(Env(("KOLDERA_ENGINE_JAR", "/x/engine.jar"), ("VERAPDF_CMD", "/x/verapdf")));
        Assert.Equal(["custom", "process"], w.PdfCommand);
        Assert.Equal("/cfg/verapdf", w.VeraPdfCommand);
        Assert.Equal(["/x/engine.jar"], w.PdfStructureCommand.Skip(2).Take(1)); // the other verbs still use the environment
    }

    [Fact]
    public void The_startup_report_names_enabled_features_and_whether_each_tool_exists_and_no_secrets()
    {
        var jar = Path.GetTempFileName();
        try
        {
            var env = Env(("KOLDERA_ENGINE_JAR", jar));
            var workers = new WorkerOptions().ApplyDefaults(env);
            var options = CoreOptions.From(name => name switch { "KOLDERA_FEATURES" => "pdf,document", "KOLDERA_TOKEN" => "s3cret-token", "KOLDERA_WEB_PROXY" => "http://u:p@proxy:1", _ => null });
            var report = StartupReport.Describe(options, workers);
            Assert.Contains("features: document, pdf", report);
            Assert.Contains($"engine {jar} found", report);
            Assert.Contains("renderer /app/renderer/dist/cli.js missing", report);
            Assert.Contains("verapdf /opt/verapdf/verapdf missing", report);
            Assert.Contains("typst /opt/typst/typst missing", report);
            Assert.DoesNotContain("scanner", report); // web is not enabled
            Assert.DoesNotContain("s3cret", report);
            Assert.DoesNotContain("proxy", report);
        }
        finally
        {
            File.Delete(jar);
        }
    }

    [Fact]
    public async Task The_server_logs_that_report_once_at_startup()
    {
        using var f = Support.CoreFactory.WithStubWorkers(Support.StubResults.PdfAnalyzeCompleted());
        _ = f.CreateClient(); // starts the host
        await Task.Yield();
        Assert.Contains(f.Logs, l => l.Contains("koldera ready") && l.Contains("features:") && l.Contains("verapdf"));
        Assert.DoesNotContain(f.Logs, l => l.Contains("koldera ready") && l.Contains("Bearer"));
    }
}
