import { describe, expect, it } from "vitest";
import { BlockedDestinationError, checkUrlSyntax, isPublicAddress, redactUrl, resolvePublic } from "../src/network/urlPolicy.js";

const anon = { allowHttp: false };

describe("isPublicAddress", () => {
  it.each([
    "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
    "224.0.0.1", "255.255.255.255", "198.18.0.1", "192.0.0.1",
    "::1", "::", "fc00::1", "fd12::1", "fe80::1", "::ffff:10.0.0.1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:c0a8:101::", "2001:db8::1", "ff02::1",
    // IPv4-compatible (::/96), SIIT (::ffff:0:0:0/96) and Teredo (2001::/32) embed an IPv4 address a gateway or tunnel reaches.
    "::127.0.0.1", "::7f00:1", "::8.8.8.8", "::ffff:0:127.0.0.1", "::ffff:0:7f00:1", "2001::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2",
  ])("treats %s as private/reserved", (ip) => {
    expect(isPublicAddress(ip)).toBe(false);
  });

  it.each(["93.184.216.34", "8.8.8.8", "2606:4700:4700::1111", "::ffff:93.184.216.34", "2a00:1450:4001::1", "2001:4860:4860::8888"])("treats %s as public", (ip) => {
    expect(isPublicAddress(ip)).toBe(true);
  });
});

describe("checkUrlSyntax", () => {
  it.each([
    ["http://example.com/", "scheme"],
    ["ftp://example.com/", "scheme"],
    ["file:///etc/passwd", "scheme"],
    ["https://user:pw@example.com/", "credentials"],
    ["https://example.com:8443/", "ports"],
    ["https://localhost/", "reserved"],
    ["https://app.localhost/", "reserved"],
    ["https://intranet/", "reserved"],
    ["https://metadata.google.internal/computeMetadata/v1/", "reserved"],
    ["https://svc.internal/", "reserved"],
    ["https://127.0.0.1/", "not publicly routable"],
    ["https://10.0.0.5/", "not publicly routable"],
    ["https://169.254.169.254/latest/meta-data/", "not publicly routable"],
    ["https://[::1]/", "not publicly routable"],
    ["https://[::ffff:10.0.0.1]/", "not publicly routable"],
    ["https://[fe80::1]/", "not publicly routable"],
    ["https://[::127.0.0.1]/", "not publicly routable"],
    ["https://[::7f00:1]/", "not publicly routable"],
    ["https://[2001::1]/", "not publicly routable"],
    // Odd IPv4 spellings: the URL parser turns them into 127.0.0.1 before the policy sees the host.
    ["https://2130706433/", "not publicly routable"],
    ["https://0x7f.1/", "not publicly routable"],
    ["https://0177.0.0.1/", "not publicly routable"],
    ["https://0.0.0.0/", "not publicly routable"],
    ["https://100.64.0.1/", "not publicly routable"],
    // Trailing dot and upper case do not change the name.
    ["https://localhost./", "reserved"],
    ["https://LOCALHOST/", "reserved"],
    ["https://LocalHost.:443/", "reserved"],
  ])("rejects %s (%s)", (url, reason) => {
    expect(() => checkUrlSyntax(url, anon)).toThrow(BlockedDestinationError);
    try {
      checkUrlSyntax(url, anon);
    } catch (e) {
      expect((e as BlockedDestinationError).reason).toContain(reason);
    }
  });

  it("rejects non-URLs as INVALID_INPUT", () => {
    try {
      checkUrlSyntax("not a url", anon);
      throw new Error("expected rejection");
    } catch (e) {
      expect((e as BlockedDestinationError).code).toBe("INVALID_INPUT");
    }
  });

  it("accepts a public https URL and http only when the policy allows it", () => {
    expect(checkUrlSyntax("https://example.com/path?x=1", anon).hostname).toBe("example.com");
    expect(checkUrlSyntax("http://example.com/", { allowHttp: true }).hostname).toBe("example.com");
    expect(() => checkUrlSyntax("http://example.com/", anon)).toThrow();
  });

  it("only the test switch admits 127.0.0.1, never localhost by name or other loopback", () => {
    const test = { allowHttp: true, allowLoopbackForTests: true };
    expect(checkUrlSyntax("http://127.0.0.1:41234/x", test).literalIp).toBe("127.0.0.1");
    expect(() => checkUrlSyntax("http://localhost:41234/x", test)).toThrow();
    expect(() => checkUrlSyntax("http://127.0.0.2/x", test)).toThrow();
    expect(() => checkUrlSyntax("http://[::1]/x", test)).toThrow();
  });
});

describe("resolvePublic", () => {
  it("rejects hosts whose answers include a private address (mixed answers)", async () => {
    const checked = checkUrlSyntax("https://rebind.example/", anon);
    const resolver = { lookup: async () => ["93.184.216.34", "10.0.0.1"] };
    await expect(resolvePublic(checked, resolver, anon)).rejects.toThrow("non-public");
  });

  it("rejects hosts that do not resolve and accepts all-public answers", async () => {
    const checked = checkUrlSyntax("https://example.com/", anon);
    await expect(resolvePublic(checked, { lookup: async () => { throw new Error("ENOTFOUND"); } }, anon)).rejects.toThrow("does not resolve");
    await expect(resolvePublic(checked, { lookup: async () => [] }, anon)).rejects.toThrow("does not resolve");
    await expect(resolvePublic(checked, { lookup: async () => ["93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"] }, anon)).resolves.toHaveLength(2);
  });
});

describe("redactUrl", () => {
  it("drops query, fragment and credentials", () => {
    expect(redactUrl("https://u:p@example.com/a/b?token=secret#frag")).toBe("https://example.com/a/b");
  });
});
