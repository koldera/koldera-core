using System.Net;
using System.Net.Sockets;

namespace Koldera.Core.Workers;

/// <summary>
/// Syntactic destination policy applied when a web job is accepted, so obviously internal targets are
/// refused at zero cost (422). The scanner repeats the checks with live DNS and its egress proxy
/// enforces public destinations at connection time; this is the first of three layers, not the last.
/// Mirrors the scanner's URL policy. Rejection reasons never repeat the URL.
/// </summary>
public static class ScanTargetPolicy
{
    private static readonly string[] BlockedSuffixes = [".localhost", ".local", ".internal", ".localdomain", ".home.arpa", ".in-addr.arpa", ".ip6.arpa"];
    private static readonly HashSet<string> BlockedHosts = new(StringComparer.OrdinalIgnoreCase) { "localhost", "metadata.google.internal", "metadata", "instance-data", "kubernetes.default" };

    /// <summary>
    /// A content-free rejection reason, or null when the URL may be queued; <paramref name="normalized"/> is then
    /// the target without query string, fragment and credentials (both may carry secrets and are never stored).
    /// </summary>
    public static string? Reject(string? raw, bool allowHttp, out Uri? normalized)
    {
        normalized = null;
        if (string.IsNullOrWhiteSpace(raw) || raw.Length > 2048 || !Uri.TryCreate(raw.Trim(), UriKind.Absolute, out var uri))
            return "url must be an absolute URL of at most 2048 characters";
        if (uri.Scheme != Uri.UriSchemeHttps && !(allowHttp && uri.Scheme == Uri.UriSchemeHttp))
            return allowHttp ? "only http and https URLs can be scanned" : "only https URLs can be scanned";
        if (!string.IsNullOrEmpty(uri.UserInfo))
            return "credentials in the URL are not allowed";
        if (!uri.IsDefaultPort && uri.Port != 443 && uri.Port != 80)
            return "only the default http/https ports are allowed";
        var host = uri.IdnHost.TrimEnd('.');
        if (host.Length == 0 || host.Length > 253)
            return "hostname is missing or too long";
        if (IPAddress.TryParse(host, out var ip))
        {
            if (!IsPublic(ip)) return "literal IP address is not publicly routable";
        }
        else if (BlockedHosts.Contains(host) || !host.Contains('.') || BlockedSuffixes.Any(s => host.EndsWith(s, StringComparison.OrdinalIgnoreCase)))
        {
            return "hostname belongs to a reserved or internal namespace";
        }
        normalized = new UriBuilder(uri) { Query = "", Fragment = "" }.Uri;
        return null;
    }

    public static bool IsPublic(IPAddress ip)
    {
        if (ip.IsIPv4MappedToIPv6) return IsPublic(ip.MapToIPv4());
        if (ip.AddressFamily == AddressFamily.InterNetwork)
        {
            var b = ip.GetAddressBytes();
            return !(b[0] == 0 || b[0] == 10 || b[0] == 127
                || (b[0] == 100 && b[1] >= 64 && b[1] <= 127)
                || (b[0] == 169 && b[1] == 254)
                || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                || (b[0] == 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2))
                || (b[0] == 192 && b[1] == 168)
                || (b[0] == 198 && (b[1] == 18 || b[1] == 19))
                || (b[0] == 198 && b[1] == 51 && b[2] == 100)
                || (b[0] == 203 && b[1] == 0 && b[2] == 113)
                || b[0] >= 224);
        }
        if (ip.AddressFamily == AddressFamily.InterNetworkV6)
        {
            if (IPAddress.IPv6Loopback.Equals(ip) || IPAddress.IPv6Any.Equals(ip) || ip.IsIPv6LinkLocal || ip.IsIPv6SiteLocal || ip.IsIPv6Multicast || ip.IsIPv6UniqueLocal)
                return false;
            var b = ip.GetAddressBytes();
            // NAT64 (64:ff9b::/96) and 6to4 (2002::/16) embed an IPv4 address: judge that one.
            if (b[0] == 0x64 && b[1] == 0xff && b[2] == 0x9b && b[3..12].All(x => x == 0))
                return IsPublic(new IPAddress(b[12..16]));
            if (b[0] == 0x20 && b[1] == 0x02)
                return IsPublic(new IPAddress(b[2..6]));
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
                return false; // documentation range
            return true;
        }
        return false;
    }
}
