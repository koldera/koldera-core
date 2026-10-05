using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class ArtifactDownloadTests
{
    private static async Task<string> DispositionFor(string kind)
    {
        var r = await Download(kind, "stored.pdf", Fixtures.SmallPdf());
        return r.Content.Headers.NonValidated["Content-Disposition"].ToString();
    }

    private static async Task<HttpResponseMessage> Download(string kind, string fileName, byte[] bytes)
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var jobId = Guid.NewGuid();
        var now = f.Clock.GetUtcNow();
        var artifact = new CoreArtifact(Guid.NewGuid(), kind, fileName, Fixtures.Sha256(bytes), bytes.Length, now, now.AddHours(1));
        await f.Store.SaveJobAsync(new CoreJob { Id = jobId, Kind = CoreJobKind.Pdf, State = CoreJobState.Completed, Owner = "server", CreatedAt = now, Artifacts = [artifact] });
        Directory.CreateDirectory(Path.Combine(f.Store.JobDir(jobId), "out"));
        await File.WriteAllBytesAsync(Path.Combine(f.Store.JobDir(jobId), "out", fileName), bytes);

        var listing = await f.AuthorizedClient().GetFromJsonAsync<JsonElement>($"/v1/jobs/{jobId}/artifacts");
        var url = listing.GetProperty("items")[0].GetProperty("downloadUrl").GetString()!;
        var r = await f.CreateClient().GetAsync(url);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return r;
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

    [Theory]
    [InlineData("source_html", "stored.html", "text/html")]
    [InlineData("remediated", "stored.pdf", "application/pdf")]
    [InlineData("web_report", "stored.json", "application/json")]
    public async Task Every_download_is_a_sandboxed_attachment_that_is_never_sniffed(string kind, string fileName, string type)
    {
        // An HTML artifact is the caller's own markup served from the API origin; even opened directly it must not run
        // script or load anything with that origin.
        var r = await Download(kind, fileName, "<script>alert(1)</script>"u8.ToArray());

        Assert.Equal(type, r.Content.Headers.ContentType!.MediaType);
        Assert.StartsWith("attachment;", r.Content.Headers.NonValidated["Content-Disposition"].ToString());
        Assert.Equal("nosniff", string.Join(",", r.Headers.GetValues("X-Content-Type-Options")));
        Assert.Equal("sandbox; default-src 'none'", string.Join(",", r.Headers.GetValues("Content-Security-Policy")));
    }
}
