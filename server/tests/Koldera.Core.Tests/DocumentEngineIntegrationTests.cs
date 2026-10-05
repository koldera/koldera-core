using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>Skipped unless the engine jar, veraPDF, Typst and a built renderer are all given.</summary>
public sealed class DocumentToolsFactAttribute : FactAttribute
{
    public DocumentToolsFactAttribute()
    {
        if (new[] { "KOLDERA_TEST_ENGINE_JAR", "VERAPDF_CMD", "TYPST_CMD", "KOLDERA_TEST_RENDERER_CLI" }.Any(n => string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(n))))
            Skip = "set KOLDERA_TEST_ENGINE_JAR, VERAPDF_CMD, TYPST_CMD and KOLDERA_TEST_RENDERER_CLI (renderer/dist/cli.js) to run documents through the real tools";
    }
}

public sealed class DocumentEngineIntegrationTests
{
    private static CoreFactory RealFactory()
    {
        var jar = Environment.GetEnvironmentVariable("KOLDERA_TEST_ENGINE_JAR")!;
        var pdf = SubprocessWorker.ForPdfProcess(["java", "-jar", jar, "process"], Environment.GetEnvironmentVariable("VERAPDF_CMD"));
        var render = SubprocessWorker.ForNodeVerb(["node", Environment.GetEnvironmentVariable("KOLDERA_TEST_RENDERER_CLI")!, "document"], SchemaResources.ContractsDirectory);
        return CoreFactory.WithStubWorkers(pdf, documentRender: render, env: new() { ["KOLDERA_TYPST_CMD"] = Environment.GetEnvironmentVariable("TYPST_CMD") });
    }

    private static async Task<JsonElement> Run(CoreFactory f, string document)
    {
        var r = await f.AuthorizedClient().PostAsync("/v1/documents/jobs", new StringContent(document, Encoding.UTF8, "application/json"));
        Assert.Equal(System.Net.HttpStatusCode.Accepted, r.StatusCode);
        var id = (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
        var job = await f.WaitForJob(id);
        Assert.True(job.GetProperty("state").GetString() == "completed", job.GetRawText());
        Assert.Empty(job.GetProperty("validation").GetProperty("failedRules").EnumerateArray());
        return job;
    }

    [DocumentToolsFact]
    public async Task The_invoice_renders_to_a_pdf_with_no_failed_veraPDF_rule()
    {
        using var f = RealFactory();
        var job = await Run(f, File.ReadAllText("Fixtures/documents/invoice.json"));
        Assert.Equal("passed", job.GetProperty("validation").GetProperty("status").GetString());
    }

    [DocumentToolsFact]
    public async Task A_document_with_an_uploaded_image_renders_too()
    {
        using var f = RealFactory();
        var id = await f.CompletedUpload(File.ReadAllBytes("Fixtures/documents/chart.png"), "chart.png", "image/png");
        var document = $$"""{"title":"Chart","language":"en","body":[{"type":"heading","level":1,"text":"Chart"},{"type":"image","uploadId":"{{id}}","alt":"A bar chart."}]}""";
        await Run(f, document);
    }
}
