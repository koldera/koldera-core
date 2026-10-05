using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>Skipped unless KOLDERA_TEST_ENGINE_JAR and VERAPDF_CMD are set.</summary>
public sealed class EngineFactAttribute : FactAttribute
{
    public EngineFactAttribute()
    {
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("KOLDERA_TEST_ENGINE_JAR")) ||
            string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("VERAPDF_CMD")))
            Skip = "set KOLDERA_TEST_ENGINE_JAR and VERAPDF_CMD to run against the real engine";
    }
}

public sealed class EngineIntegrationTests
{
    private static string SamplePath()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "engine", "src", "test", "resources", "generated", "generated-untagged-text.pdf");
            if (File.Exists(candidate)) return candidate;
        }
        throw new FileNotFoundException("synthetic engine sample not found");
    }

    [EngineFact]
    public async Task The_real_engine_analyzes_a_synthetic_pdf_end_to_end()
    {
        var jar = Environment.GetEnvironmentVariable("KOLDERA_TEST_ENGINE_JAR")!;
        var pdf = SubprocessWorker.ForPdfProcess(["java", "-jar", jar, "process"], Environment.GetEnvironmentVariable("VERAPDF_CMD"));
        var probe = SubprocessWorker.ForProbe(["java", "-jar", jar, "structure"]);
        using var f = CoreFactory.WithStubWorkers(pdf, probe);

        var bytes = await File.ReadAllBytesAsync(SamplePath());
        var upload = await f.CompletedUpload(bytes);
        var created = await f.AuthorizedClient().PostAsJsonAsync("/v1/pdf/jobs", new { uploadId = upload, mode = "analyze", profile = "ua1" });
        var id = (await created.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
        var job = await f.WaitForJob(id);

        Assert.Equal("completed", job.GetProperty("state").GetString());
        Assert.False(string.IsNullOrEmpty(job.GetProperty("engineVersion").GetString()));
        Assert.Equal(JsonValueKind.Object, job.GetProperty("baselineValidation").ValueKind);
        Assert.True(job.GetProperty("manualReviewRequired").GetBoolean());
    }
}
