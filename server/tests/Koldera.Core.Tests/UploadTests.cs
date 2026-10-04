using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Koldera.Core.Tests.Support;
using Microsoft.Extensions.Time.Testing;

namespace Koldera.Core.Tests;

public class UploadTests
{
    static async Task<(HttpClient c, JsonElement ticket, byte[] bytes)> Ticket(CoreFactory f)
    {
        var c = f.AuthorizedClient();
        var bytes = Fixtures.SmallPdf();
        var r = await c.PostAsJsonAsync("/v1/uploads", new { fileName = "a.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        return (c, await r.Content.ReadFromJsonAsync<JsonElement>(), bytes);
    }

    [Fact]
    public async Task Put_with_matching_bytes_completes_without_a_bearer_token()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (_, t, bytes) = await Ticket(f);
        var r = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent(bytes));
        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        var rec = f.Store.GetUpload(Guid.Parse(t.GetProperty("uploadId").GetString()!))!;
        Assert.True(rec.Completed);
        Assert.Equal("server", rec.Owner);
    }

    [Fact]
    public async Task Second_put_is_rejected_and_the_original_is_unchanged()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (_, t, bytes) = await Ticket(f);
        var url = t.GetProperty("uploadUrl").GetString();
        await f.CreateClient().PutAsync(url, new ByteArrayContent(bytes));
        var again = await f.CreateClient().PutAsync(url, new ByteArrayContent(Fixtures.OtherPdf()));
        Assert.Equal(HttpStatusCode.Conflict, again.StatusCode);
        Assert.Equal(bytes, await File.ReadAllBytesAsync(f.Store.UploadPath(Guid.Parse(t.GetProperty("uploadId").GetString()!))));
    }

    [Fact]
    public async Task Wrong_bytes_are_rejected_with_checksum_mismatch()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (_, t, bytes) = await Ticket(f);
        var tampered = (byte[])bytes.Clone(); tampered[^1] ^= 1;
        var r = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent(tampered));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("CHECKSUM_MISMATCH", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
        Assert.False(f.Store.GetUpload(Guid.Parse(t.GetProperty("uploadId").GetString()!))!.Completed);
    }

    [Fact]
    public async Task More_bytes_than_declared_are_rejected_with_size_mismatch()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (_, t, bytes) = await Ticket(f);
        var r = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent([.. bytes, .. new byte[1000]]));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("SIZE_MISMATCH", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Tampered_or_missing_signature_is_forbidden()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (_, t, bytes) = await Ticket(f);
        var url = t.GetProperty("uploadUrl").GetString()!;
        var bare = url[..url.IndexOf('?')];
        Assert.Equal(HttpStatusCode.Forbidden, (await f.CreateClient().PutAsync(bare, new ByteArrayContent(bytes))).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await f.CreateClient().PutAsync(url[..^2] + "AA", new ByteArrayContent(bytes))).StatusCode);
    }

    [Fact]
    public async Task No_size_cap_by_default_and_a_cap_only_when_configured()
    {
        using var open = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var big = await open.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "a.pdf", sizeBytes = 5L * 1024 * 1024 * 1024, sha256 = new string('a', 64), contentType = "application/pdf" });
        Assert.Equal(HttpStatusCode.Created, big.StatusCode);
        Assert.Equal(JsonValueKind.Null, (await big.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("maxBytes").ValueKind);

        using var capped = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t", ["KOLDERA_PDF_MAX_BYTES"] = "100" });
        var r = await capped.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "a.pdf", sizeBytes = 101, sha256 = new string('a', 64), contentType = "application/pdf" });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        var ok = await capped.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "a.pdf", sizeBytes = 100, sha256 = new string('a', 64), contentType = "application/pdf" });
        Assert.Equal(100, (await ok.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("maxBytes").GetInt64());
    }

    [Theory]
    [InlineData("application/zip", "a.pdf", 5, "0000000000000000000000000000000000000000000000000000000000000000")]
    [InlineData("application/pdf", "a.pdf", 5, "ABC")]
    [InlineData("application/pdf", "  ", 5, "0000000000000000000000000000000000000000000000000000000000000000")]
    [InlineData("application/pdf", "a.pdf", 0, "0000000000000000000000000000000000000000000000000000000000000000")]
    public async Task Invalid_declarations_are_validation_errors(string type, string name, long size, string sha)
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = name, sizeBytes = size, sha256 = sha, contentType = type });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, r.StatusCode);
        Assert.Equal("VALIDATION_ERROR", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Ticket_requires_a_bearer_token_and_html_is_accepted()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var body = new { fileName = "a.html", sizeBytes = 5, sha256 = new string('b', 64), contentType = "text/html" };
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.CreateClient().PostAsJsonAsync("/v1/uploads", body)).StatusCode);
        Assert.Equal(HttpStatusCode.Created, (await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", body)).StatusCode);
    }

    [Fact]
    public async Task Public_url_prefixes_the_upload_url()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t", ["KOLDERA_PUBLIC_URL"] = "https://core.example.com/" });
        var (_, t, _) = await Ticket(f);
        Assert.StartsWith("https://core.example.com/v1/uploads/", t.GetProperty("uploadUrl").GetString());
    }

    [Fact]
    public async Task Nothing_about_the_upload_is_logged()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var c = f.AuthorizedClient();
        var bytes = Fixtures.SmallPdf();
        var r = await c.PostAsJsonAsync("/v1/uploads", new { fileName = "secret-name.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" });
        var url = (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("uploadUrl").GetString()!;
        await f.CreateClient().PutAsync(url, new ByteArrayContent(bytes));
        var logs = string.Join('\n', f.Logs);
        Assert.DoesNotContain("sig=", logs);
        Assert.DoesNotContain("secret-name", logs);
    }

    [Fact]
    public async Task Expired_upload_is_refused_by_the_store()
    {
        var clock = new FakeTimeProvider(DateTimeOffset.Parse("2026-10-05T00:00:00Z"));
        var root = Path.Combine(Path.GetTempPath(), "koldera-store-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new DataStore(root, clock);
            var bytes = Fixtures.SmallPdf();
            var id = Guid.NewGuid();
            store.CreateUpload(new UploadRecord(id, "a.pdf", bytes.Length, Fixtures.Sha256(bytes), "application/pdf", "server", clock.GetUtcNow(), clock.GetUtcNow().AddHours(1), false));
            clock.Advance(TimeSpan.FromHours(2));
            var ex = await Assert.ThrowsAsync<UploadException>(() => store.CompleteUploadAsync(id, new MemoryStream(bytes), default));
            Assert.Equal("UPLOAD_EXPIRED", ex.Code);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task Jobs_round_trip_atomically_and_are_listed()
    {
        var root = Path.Combine(Path.GetTempPath(), "koldera-store-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new DataStore(root);
            var job = new CoreJob { Id = Guid.NewGuid(), Owner = "server", CreatedAt = DateTimeOffset.Parse("2026-10-05T00:00:00Z") };
            await store.SaveJobAsync(job);
            job.State = CoreJobState.Completed;
            await store.SaveJobAsync(job);
            Assert.Equal(CoreJobState.Completed, store.GetJob(job.Id)!.State);
            Assert.Single(store.AllJobs());
            Assert.True(Directory.Exists(store.JobDir(job.Id)));
            Assert.Null(store.GetJob(Guid.NewGuid()));
            Assert.Empty(Directory.GetFiles(root, "*.tmp", SearchOption.AllDirectories));
        }
        finally { Directory.Delete(root, true); }
    }
}
