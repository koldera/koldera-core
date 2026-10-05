using System.Text.Json;
using System.Text.Json.Nodes;
using Koldera.Core.Tests.Support;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

/// <summary>WORKER_CONTRACT messages reach clients as error.message, so they must not echo anything the worker wrote.</summary>
public class WorkerContractMessageTests
{
    private const string Leak = "https://internal.example/secret/doc.pdf";
    private const string LeakText = "Quarterly salaries of Jane Doe";

    private static async Task<string> MessageFor(Func<JsonObject, string> corrupt)
    {
        var worker = new StubWorker(request =>
        {
            var result = JsonNode.Parse(StubResults.PdfAnalyzeCompleted().ResultFor(request))!.AsObject();
            return corrupt(result);
        });
        using var f = CoreFactory.WithStubWorkers(worker);
        var job = await f.WaitForJob(await f.CreatePdfJob());
        Assert.Equal("WORKER_CONTRACT", job.GetProperty("error").GetProperty("code").GetString());
        return job.GetProperty("error").GetProperty("message").GetString()!;
    }

    private static void AssertNoLeak(string message)
    {
        Assert.DoesNotContain("internal.example", message);
        Assert.DoesNotContain("secret", message);
        Assert.DoesNotContain("Jane", message);
        Assert.DoesNotContain("salaries", message);
    }

    [Fact]
    public async Task An_unexpected_property_name_is_not_echoed()
    {
        var message = await MessageFor(r =>
        {
            r[Leak] = LeakText;
            return r.ToJsonString();
        });
        AssertNoLeak(message);
        Assert.Contains("unexpected property", message);
    }

    [Fact]
    public async Task The_log_names_the_unexpected_property_and_the_pointer_but_never_a_value_while_the_client_message_stays_redacted()
    {
        var worker = new StubWorker(request =>
        {
            var result = JsonNode.Parse(StubResults.PdfAnalyzeCompleted().ResultFor(request))!.AsObject();
            result["leakyField"] = Leak + " " + LeakText;
            result["findings"]![0]!["extraThing"] = LeakText;
            return result.ToJsonString();
        });
        using var f = CoreFactory.WithStubWorkers(worker);
        var job = await f.WaitForJob(await f.CreatePdfJob());

        var message = job.GetProperty("error").GetProperty("message").GetString()!;
        Assert.DoesNotContain("leakyField", message);
        Assert.DoesNotContain("extraThing", message);
        AssertNoLeak(message);

        var line = Assert.Single(f.Logs, l => l.Contains("violated the worker contract"));
        Assert.Contains("unexpected property 'leakyField'", line);
        Assert.Contains("$.findings[0]: unexpected property 'extraThing'", line);
        AssertNoLeak(line);
    }

    [Fact]
    public async Task A_value_in_an_invalid_field_is_not_echoed()
    {
        var message = await MessageFor(r =>
        {
            r["originalSha256"] = Leak + " " + LeakText;
            r["status"] = Leak;
            r["findings"]![0]!["pageNumber"] = -123456789;
            return r.ToJsonString();
        });
        AssertNoLeak(message);
        Assert.DoesNotContain("123456789", message);
        Assert.Contains("$.originalSha256", message);
    }

    [Fact]
    public async Task Text_that_is_not_json_is_not_echoed()
    {
        var message = await MessageFor(_ => "{ \"note\": \"" + LeakText + " " + Leak + "\" ??? ");
        AssertNoLeak(message);
    }
}
