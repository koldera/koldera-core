using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class ArtifactDownloadTests
{
    private static async Task<string> DispositionFor(string kind)
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var jobId = Guid.NewGuid();
        var bytes = Fixtures.SmallPdf();
        var now = f.Clock.GetUtcNow();
        var artifact = new CoreArtifact(Guid.NewGuid(), kind, "stored.pdf", Fixtures.Sha256(bytes), bytes.Length, now, now.AddHours(1));
        await f.Store.SaveJobAsync(new CoreJob { Id = jobId, Kind = CoreJobKind.Pdf, State = CoreJobState.Completed, Owner = "server", CreatedAt = now, Artifacts = [artifact] });
        Directory.CreateDirectory(Path.Combine(f.Store.JobDir(jobId), "out"));
        await File.WriteAllBytesAsync(Path.Combine(f.Store.JobDir(jobId), "out", "stored.pdf"), bytes);

        var listing = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{jobId}/artifacts");
        var url = listing.GetProperty("items")[0].GetProperty("downloadUrl").GetString()!;
        var r = await f.CreateClient().GetAsync(url);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return r.Content.Headers.NonValidated["Content-Disposition"].ToString();
    }

    [Theory]
    [InlineData("remediated")]
    [InlineData("generated_pdf")]
    [InlineData("web_report")]
    [InlineData("reordered")]
    public async Task Known_artifact_kinds_name_the_download(string kind) =>
        Assert.Equal($"attachment; filename=\"{kind}.pdf\"", await DispositionFor(kind));

    [Theory]
    [InlineData("x\"; filename=\"evil.exe")]
    [InlineData("../secret")]
    [InlineData("a\r\nSet-Cookie: x=1")]
    [InlineData("")]
    public async Task An_unknown_kind_never_reaches_the_file_name(string kind) =>
        Assert.Equal("attachment; filename=\"artifact.pdf\"", await DispositionFor(kind));
}
