using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

public class ScanTargetPolicyTests
{
    // Why: an IPv6 literal that merely embeds an IPv4 address (IPv4-compatible ::/96, SIIT ::ffff:0:0:0/96,
    // Teredo 2001::/32) reaches the embedded IPv4 host through a translating gateway or tunnel, so the
    // syntactic check must not call it public.
    [Theory]
    [InlineData("https://[::127.0.0.1]/")]
    [InlineData("https://[::7f00:1]/")]
    [InlineData("https://[::10.0.0.1]/")]
    [InlineData("https://[::8.8.8.8]/")] // even a public embedded address: the whole ::/96 block is refused
    [InlineData("https://[::ffff:0:127.0.0.1]/")]
    [InlineData("https://[::ffff:0:7f00:1]/")]
    [InlineData("https://[2001::1]/")]
    [InlineData("https://[2001:0:4136:e378:8000:63bf:3fff:fdd2]/")] // Teredo with an embedded server and client address
    public void IPv6_literals_that_embed_an_IPv4_address_are_not_public(string url)
    {
        Assert.NotNull(ScanTargetPolicy.Reject(url, allowHttp: false, out var normalized));
        Assert.Null(normalized);
    }

    // Why: pinned so a future parser change cannot turn an alternative IPv4 spelling or a trailing-dot or
    // upper-case name into an accepted target.
    [Theory]
    [InlineData("http://2130706433/")]
    [InlineData("https://2130706433/")]
    [InlineData("https://0x7f.1/")]
    [InlineData("http://0x7f.1/")]
    [InlineData("https://0177.0.0.1/")]
    [InlineData("http://0177.0.0.1/")]
    [InlineData("https://localhost./")]
    [InlineData("http://localhost./")]
    [InlineData("https://LOCALHOST/")]
    [InlineData("http://LOCALHOST/")]
    [InlineData("https://0.0.0.0/")]
    [InlineData("http://0.0.0.0/")]
    [InlineData("https://100.64.0.1/")]
    [InlineData("http://100.64.0.1/")]
    public void Alternative_spellings_of_internal_targets_stay_rejected(string url)
    {
        Assert.NotNull(ScanTargetPolicy.Reject(url, allowHttp: true, out var normalized));
        Assert.Null(normalized);
    }

    // Why: the new blocks must not swallow ordinary public IPv6 destinations.
    [Theory]
    [InlineData("https://[2606:4700:4700::1111]/")]
    [InlineData("https://[2a00:1450:4001::1]/")]
    [InlineData("https://[::ffff:93.184.216.34]/")]
    [InlineData("https://[2001:4860:4860::8888]/")]
    public void Public_IPv6_literals_are_still_accepted(string url) =>
        Assert.Null(ScanTargetPolicy.Reject(url, allowHttp: false, out _));
}
