using System.Security.Cryptography;
using System.Web;
using Microsoft.Extensions.Time.Testing;

namespace Koldera.Core.Tests;

public class UrlSignerTests
{
    [Fact]
    public void Signed_path_verifies_until_expiry_and_not_after_tampering()
    {
        var clock = new FakeTimeProvider(DateTimeOffset.Parse("2026-10-05T00:00:00Z"));
        var s = new UrlSigner(RandomNumberGenerator.GetBytes(32), clock);
        var url = new Uri("http://x" + s.Sign("/v1/uploads/abc/content", TimeSpan.FromMinutes(10)));
        var q = HttpUtility.ParseQueryString(url.Query);
        Assert.True(s.Verify("/v1/uploads/abc/content", q["exp"], q["sig"]));
        Assert.False(s.Verify("/v1/uploads/abd/content", q["exp"], q["sig"]));
        Assert.False(s.Verify("/v1/uploads/abc/content", q["exp"], null));
        Assert.False(s.Verify("/v1/uploads/abc/content", "garbage", q["sig"]));
        clock.Advance(TimeSpan.FromMinutes(11));
        Assert.False(s.Verify("/v1/uploads/abc/content", q["exp"], q["sig"]));
    }

    [Fact]
    public void Another_key_does_not_verify()
    {
        var clock = new FakeTimeProvider(DateTimeOffset.Parse("2026-10-05T00:00:00Z"));
        var a = new UrlSigner(RandomNumberGenerator.GetBytes(32), clock);
        var b = new UrlSigner(RandomNumberGenerator.GetBytes(32), clock);
        var q = HttpUtility.ParseQueryString(new Uri("http://x" + a.Sign("/p", TimeSpan.FromMinutes(1))).Query);
        Assert.False(b.Verify("/p", q["exp"], q["sig"]));
    }
}
