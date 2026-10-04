using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Koldera.Core.Tests.Support;

namespace Koldera.Core.Tests;

public class UploadFixTests
{
    private static async Task<(JsonElement ticket, byte[] bytes, Guid id)> Ticket(CoreFactory f, byte[]? bytes = null)
    {
        bytes ??= Fixtures.SmallPdf();
        var r = await f.AuthorizedClient().PostAsJsonAsync("/v1/uploads", new { fileName = "a.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        var t = await r.Content.ReadFromJsonAsync<JsonElement>();
        return (t, bytes, Guid.Parse(t.GetProperty("uploadId").GetString()!));
    }

    [Fact]
    public async Task Signed_url_lasts_ten_minutes_not_the_record_lifetime()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (t, bytes, id) = await Ticket(f);
        Assert.Equal(f.Clock.GetUtcNow().AddMinutes(10).ToUnixTimeSeconds(), t.GetProperty("expiresAt").GetDateTimeOffset().ToUnixTimeSeconds());
        Assert.True(f.Store.GetUpload(id)!.ExpiresAt > f.Clock.GetUtcNow().AddMinutes(30));
        f.Clock.Advance(TimeSpan.FromMinutes(11));
        var r = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent(bytes));
        Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
        Assert.Equal("SIGNATURE_INVALID", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Retry_after_a_crash_between_move_and_record_is_idempotent()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (t, bytes, id) = await Ticket(f);
        await File.WriteAllBytesAsync(f.Store.UploadPath(id), bytes); // final file exists, record still Completed=false
        Assert.False(f.Store.GetUpload(id)!.Completed);
        var r = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent(bytes));
        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        Assert.True(f.Store.GetUpload(id)!.Completed);
        Assert.Equal(bytes, await File.ReadAllBytesAsync(f.Store.UploadPath(id)));
    }

    [Fact]
    public async Task Different_bytes_after_a_crash_leave_the_existing_file_untouched()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (t, bytes, id) = await Ticket(f);
        await File.WriteAllBytesAsync(f.Store.UploadPath(id), bytes);
        var r = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent(Fixtures.OtherPdf()));
        Assert.True(r.StatusCode is HttpStatusCode.Conflict or HttpStatusCode.UnprocessableEntity);
        Assert.Equal(bytes, await File.ReadAllBytesAsync(f.Store.UploadPath(id)));
    }

    [Fact]
    public async Task A_corrupt_final_file_is_never_overwritten()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (t, bytes, id) = await Ticket(f);
        var corrupt = Fixtures.OtherPdf();
        await File.WriteAllBytesAsync(f.Store.UploadPath(id), corrupt);
        var r = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent(bytes));
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal(corrupt, await File.ReadAllBytesAsync(f.Store.UploadPath(id)));
        Assert.False(f.Store.GetUpload(id)!.Completed);
    }

    [Fact]
    public async Task Concurrent_puts_complete_exactly_once()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        var (t, bytes, _) = await Ticket(f);
        var url = t.GetProperty("uploadUrl").GetString();
        var codes = await Task.WhenAll(Enumerable.Range(0, 4).Select(async _ =>
            (await f.CreateClient().PutAsync(url, new ByteArrayContent(bytes))).StatusCode));
        Assert.Equal(1, codes.Count(c => c == HttpStatusCode.NoContent));
        Assert.Equal(3, codes.Count(c => c == HttpStatusCode.Conflict));
    }

    [Fact]
    public async Task Upload_larger_than_the_kestrel_default_limit_succeeds_through_real_kestrel()
    {
        using var f = CoreFactory.Create(new() { ["KOLDERA_TOKEN"] = "t" });
        f.UseKestrel(0);
        var bytes = new byte[31 * 1024 * 1024];
        RandomNumberGenerator.Fill(bytes);
        var r = await f.CreateClient().SendAsync(new HttpRequestMessage(HttpMethod.Post, "/v1/uploads")
        {
            Headers = { Authorization = new("Bearer", "t") },
            Content = JsonContent.Create(new { fileName = "big.pdf", sizeBytes = bytes.Length, sha256 = Fixtures.Sha256(bytes), contentType = "application/pdf" }),
        });
        Assert.Equal(HttpStatusCode.Created, r.StatusCode);
        var t = await r.Content.ReadFromJsonAsync<JsonElement>();
        var put = await f.CreateClient().PutAsync(t.GetProperty("uploadUrl").GetString(), new ByteArrayContent(bytes));
        Assert.Equal(HttpStatusCode.NoContent, put.StatusCode);
    }
}
