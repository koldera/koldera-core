import { isIP } from "node:net";
import dns from "node:dns/promises";

/**
 * Destination policy for the scanner (SSRF controls). Everything here is app-layer
 * defence: scheme, credentials, hostname classes, literal IPs and DNS answers. It is necessary but
 * NOT sufficient — the container egress proxy/firewall must enforce public destinations at
 * connection time (a resolver can answer differently to the browser than to us).
 */

export interface DestinationPolicy {
  /** Allow plain http:// targets (authenticated projects only; anonymous scans are https-only). */
  allowHttp: boolean;
  /** Test-only: permit 127.0.0.1 (never localhost by name, never other loopback/private space). */
  allowLoopbackForTests?: boolean;
}

export interface Resolver {
  lookup(hostname: string): Promise<string[]>;
}

export const systemResolver: Resolver = {
  async lookup(hostname) {
    const answers = await dns.lookup(hostname, { all: true, verbatim: true });
    return answers.map((a) => a.address);
  },
};

export class BlockedDestinationError extends Error {
  constructor(
    public readonly reason: string,
    public readonly code: "BLOCKED_DESTINATION" | "INVALID_INPUT" = "BLOCKED_DESTINATION",
  ) {
    super(reason);
    this.name = "BlockedDestinationError";
  }
}

const BLOCKED_HOST_SUFFIXES = [".localhost", ".local", ".internal", ".localdomain", ".home.arpa", ".in-addr.arpa", ".ip6.arpa"];
const BLOCKED_HOSTS = new Set(["localhost", "metadata.google.internal", "metadata", "instance-data", "kubernetes.default"]);

function ipv4ToInt(ip: string): number {
  return ip.split(".").reduce((acc, part) => (acc << 8) + Number(part), 0) >>> 0;
}

function inCidr4(ip: string, cidr: string): boolean {
  const [base, bits] = cidr.split("/") as [string, string];
  const mask = bits === "0" ? 0 : (~0 << (32 - Number(bits))) >>> 0;
  return (ipv4ToInt(ip) & mask) === (ipv4ToInt(base) & mask);
}

const PRIVATE_V4 = [
  "0.0.0.0/8", // "this" network
  "10.0.0.0/8",
  "100.64.0.0/10", // CGNAT
  "127.0.0.0/8",
  "169.254.0.0/16", // link-local incl. cloud metadata 169.254.169.254
  "172.16.0.0/12",
  "192.0.0.0/24",
  "192.0.2.0/24",
  "192.168.0.0/16",
  "198.18.0.0/15",
  "198.51.100.0/24",
  "203.0.113.0/24",
  "224.0.0.0/4",
  "240.0.0.0/4",
  "255.255.255.255/32",
];

function expandIpv6(ip: string): number[] | null {
  // Returns 8 hextets; handles :: compression and embedded IPv4 (::ffff:a.b.c.d).
  let v = ip.toLowerCase();
  const zone = v.indexOf("%");
  if (zone >= 0) v = v.slice(0, zone);
  if (v.includes(".")) {
    const lastColon = v.lastIndexOf(":");
    const v4 = v.slice(lastColon + 1);
    if (isIP(v4) !== 4) return null;
    const n = ipv4ToInt(v4);
    v = v.slice(0, lastColon + 1) + ((n >>> 16) & 0xffff).toString(16) + ":" + (n & 0xffff).toString(16);
  }
  const halves = v.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const missing = 8 - head.length - tail.length;
  if (missing < 0 || (halves.length === 1 && missing !== 0)) return null;
  const parts = [...head, ...Array<string>(missing).fill("0"), ...tail];
  const hextets = parts.map((p) => parseInt(p || "0", 16));
  return hextets.some((h) => Number.isNaN(h) || h < 0 || h > 0xffff) ? null : hextets;
}

function embeddedIpv4(hextets: number[]): string | null {
  const [a, b, c, d, e, f, g, h] = hextets as [number, number, number, number, number, number, number, number];
  const tail = `${(g >>> 8) & 0xff}.${g & 0xff}.${(h >>> 8) & 0xff}.${h & 0xff}`;
  if (a === 0 && b === 0 && c === 0 && d === 0 && e === 0 && f === 0xffff) return tail; // ::ffff:a.b.c.d
  if (a === 0x64 && b === 0xff9b && c === 0 && d === 0 && e === 0 && f === 0) return tail; // 64:ff9b::/96 NAT64
  if (a === 0x2002) return `${(b >>> 8) & 0xff}.${b & 0xff}.${(c >>> 8) & 0xff}.${c & 0xff}`; // 6to4
  return null;
}

/** True only for globally routable unicast addresses. Anything odd is treated as private. */
export function isPublicAddress(ip: string): boolean {
  const family = isIP(ip);
  if (family === 4) {
    return !PRIVATE_V4.some((cidr) => inCidr4(ip, cidr));
  }
  if (family === 6) {
    const hextets = expandIpv6(ip);
    if (!hextets) return false;
    const embedded = embeddedIpv4(hextets);
    if (embedded) return isPublicAddress(embedded);
    const [a, b] = hextets as [number, number];
    if (hextets.every((h) => h === 0)) return false; // ::
    if (hextets.slice(0, 7).every((h) => h === 0) && hextets[7] === 1) return false; // ::1
    if ((a & 0xfe00) === 0xfc00) return false; // fc00::/7 ULA
    if ((a & 0xffc0) === 0xfe80) return false; // fe80::/10 link-local
    if ((a & 0xffc0) === 0xfec0) return false; // fec0::/10 site-local (deprecated)
    if (a === 0x2001 && b === 0x0db8) return false; // documentation
    if ((a & 0xff00) === 0xff00) return false; // multicast
    if (a === 0x0100 && hextets.slice(1, 4).every((h) => h === 0)) return false; // 100::/64 discard
    return true;
  }
  return false;
}

export function isBlockedHostname(hostname: string): boolean {
  const h = hostname.toLowerCase().replace(/\.$/, "");
  if (BLOCKED_HOSTS.has(h)) return true;
  if (!h.includes(".")) return true; // single-label names resolve inside the network, never public
  return BLOCKED_HOST_SUFFIXES.some((s) => h.endsWith(s));
}

export interface CheckedUrl {
  url: URL;
  hostname: string;
  /** Literal IP when the host is an address literal; null for names. */
  literalIp: string | null;
}

/** Syntactic policy: scheme, credentials, port, hostname class and IP literals. No network access. */
export function checkUrlSyntax(raw: string, policy: DestinationPolicy): CheckedUrl {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new BlockedDestinationError("url is not an absolute URL", "INVALID_INPUT");
  }
  if (url.protocol !== "https:" && !(policy.allowHttp && url.protocol === "http:")) {
    throw new BlockedDestinationError(`scheme ${url.protocol.replace(":", "")} is not allowed`);
  }
  if (url.username || url.password) {
    throw new BlockedDestinationError("credentials in the URL are not allowed");
  }
  if (policy.allowLoopbackForTests && url.hostname === "127.0.0.1") {
    return { url, hostname: "127.0.0.1", literalIp: "127.0.0.1" }; // fixture server on a random port
  }
  if (url.port && url.port !== "443" && url.port !== "80") {
    throw new BlockedDestinationError("only default http/https ports are allowed");
  }
  if (url.port === "80" && url.protocol === "https:") {
    throw new BlockedDestinationError("only default http/https ports are allowed");
  }
  const hostname = url.hostname.replace(/^\[|\]$/g, "");
  if (hostname.length === 0 || hostname.length > 253) {
    throw new BlockedDestinationError("hostname is missing or too long", "INVALID_INPUT");
  }
  const family = isIP(hostname);
  if (family !== 0) {
    if (!isPublicAddress(hostname)) {
      throw new BlockedDestinationError("literal IP address is not publicly routable");
    }
    return { url, hostname, literalIp: hostname };
  }
  if (isBlockedHostname(hostname)) {
    throw new BlockedDestinationError("hostname belongs to a reserved or internal namespace");
  }
  if (!/^[a-z0-9.-]+$/i.test(hostname)) {
    throw new BlockedDestinationError("hostname contains unsupported characters", "INVALID_INPUT");
  }
  return { url, hostname, literalIp: null };
}

/** Resolves a hostname and requires EVERY answer to be public (mixed answers are rejected). */
export async function resolvePublic(checked: CheckedUrl, resolver: Resolver, policy: DestinationPolicy): Promise<string[]> {
  if (checked.literalIp) {
    return [checked.literalIp];
  }
  let addresses: string[];
  try {
    addresses = await resolver.lookup(checked.hostname);
  } catch {
    throw new BlockedDestinationError("hostname does not resolve");
  }
  if (addresses.length === 0) {
    throw new BlockedDestinationError("hostname does not resolve");
  }
  const bad = addresses.find((a) => !(isPublicAddress(a) || (policy.allowLoopbackForTests && a === "127.0.0.1")));
  if (bad) {
    throw new BlockedDestinationError("hostname resolves to a non-public address");
  }
  return addresses;
}

/** Strips query and fragment so reports never carry tokens embedded in URLs. */
export function redactUrl(raw: string): string {
  try {
    const u = new URL(raw);
    u.search = "";
    u.hash = "";
    u.username = "";
    u.password = "";
    return u.toString();
  } catch {
    return "<invalid-url>";
  }
}
