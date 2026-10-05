import { createServer as createHttpServer, request as httpRequest, type Server } from "node:http";
import { connect, createServer as createTcpServer, type Server as TcpServer } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { scan } from "../src/scan/scanner.js";
import { validateResult, type WebWorkerRequest } from "../src/contracts/types.js";
import { startFixtureServer } from "./fixtureServer.js";

let fixtures: Awaited<ReturnType<typeof startFixtureServer>>;
let fixturePort: number;

beforeAll(async () => {
  fixtures = await startFixtureServer();
  fixturePort = Number(new URL(fixtures.url).port);
});

afterAll(async () => {
  await fixtures.close();
});

function listen(server: Server | TcpServer): Promise<number> {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => {
    const address = server.address();
    resolve(typeof address === "object" && address ? address.port : 0);
  }));
}

/**
 * Stand-in for the egress proxy: whatever host a client asks for, CONNECT tunnels and absolute-URI requests both end
 * at the fixture server. `asked` records the destinations exactly as the client named them (names, not addresses).
 */
async function startForwardProxy(): Promise<{ url: string; asked: string[]; close: () => Promise<void> }> {
  const asked: string[] = [];
  const server = createHttpServer((req, res) => {
    asked.push(new URL(req.url ?? "/").host);
    const target = new URL(req.url ?? "/");
    const upstream = httpRequest({ host: "127.0.0.1", port: fixturePort, path: target.pathname + target.search, method: req.method, headers: req.headers }, (r) => {
      res.writeHead(r.statusCode ?? 502, r.headers);
      r.pipe(res);
    });
    upstream.on("error", () => res.destroy());
    req.pipe(upstream);
  });
  server.on("connect", (req, socket, head) => {
    asked.push(req.url ?? "");
    if ((req.url ?? "").startsWith("private-answer.example:")) {
      // What Squid answers when the name resolves to a private address: an error page on the CONNECT itself.
      const body = "<html><body>Access denied</body></html>";
      socket.end(`HTTP/1.1 403 Forbidden\r\nX-Squid-Error: ERR_ACCESS_DENIED 0\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n\r\n${body}`);
      return;
    }
    if ((req.url ?? "").startsWith("down.example:")) {
      // What Squid answers when the destination does not accept connections.
      const body = "<html><body>Connection failed</body></html>";
      socket.end(`HTTP/1.1 503 Service Unavailable\r\nX-Squid-Error: ERR_CONNECT_FAIL 111\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n\r\n${body}`);
      return;
    }
    const upstream = connect(fixturePort, "127.0.0.1", () => {
      socket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      if (head.length) upstream.write(head);
      upstream.pipe(socket);
      socket.pipe(upstream);
    });
    upstream.on("error", () => socket.destroy());
    socket.on("error", () => upstream.destroy());
  });
  const port = await listen(server);
  return { url: `http://127.0.0.1:${port}`, asked, close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(() => resolve()); }) };
}

function request(url: string, policy: WebWorkerRequest["policy"]): WebWorkerRequest {
  return {
    specVersion: "1.0",
    jobId: "123e4567-e89b-42d3-a456-426614174000",
    url,
    outputDirectory: "/work/out",
    limits: { timeoutSeconds: 30, maxResponseBytes: 5 * 1024 * 1024, maxRequests: 100 },
    policy,
  };
}

const failingResolver = { lookup: async (): Promise<string[]> => { throw new Error("no DNS on this network"); } };

async function run(req: WebWorkerRequest, opts: { allowLoopbackForTests?: boolean; resolver?: { lookup(h: string): Promise<string[]> } }) {
  const outDir = await mkdtemp(path.join(tmpdir(), "koldera-egress-"));
  try {
    const result = await scan(req, { outputDirectory: outDir, ...opts });
    expect(validateResult(result), JSON.stringify(result.error)).toEqual([]);
    return result;
  } finally {
    await rm(outDir, { recursive: true, force: true });
  }
}

describe("scans through an egress proxy (no local DNS)", () => {
  it("does not need the scanner's own resolver: the proxy is asked for the host name and the scan completes", async () => {
    const proxy = await startForwardProxy();
    try {
      const result = await run(request("http://public.example/missing-label.html", { allowHttp: true, proxyServer: proxy.url }), { resolver: failingResolver });

      expect(result.status, JSON.stringify(result.error)).toBe("completed");
      expect(result.findings.some((f) => f.ruleId === "label" && f.kind === "violation")).toBe(true);
      expect(proxy.asked).toContain("public.example:80");
    } finally {
      await proxy.close();
    }
  });

  it.each([
    "http://10.0.0.1/",
    "http://169.254.169.254/latest/meta-data/",
    "http://[::1]/",
    "http://localhost/",
    "http://metadata.google.internal/",
    "http://intranet/",
  ])("still refuses %s locally before anything reaches the proxy", async (target) => {
    const proxy = await startForwardProxy();
    try {
      const result = await run(request(target, { allowHttp: true, proxyServer: proxy.url }), { resolver: failingResolver });

      expect(result.status).toBe("unsupported");
      expect(result.error?.code).toBe("BLOCKED_DESTINATION");
      expect(result.engineVersion).toContain("not-launched");
      expect(proxy.asked).toEqual([]);
    } finally {
      await proxy.close();
    }
  });

  it("still refuses a redirect to a reserved name or a private literal before asking the proxy for it", async () => {
    const proxy = await startForwardProxy();
    try {
      const result = await run(
        request(`http://public.example/redirect?to=${encodeURIComponent("http://10.0.0.1/")}`, { allowHttp: true, proxyServer: proxy.url }),
        { resolver: failingResolver },
      );

      expect(result.status).toBe("unsupported");
      expect(result.error?.code).toBe("BLOCKED_DESTINATION");
      expect(proxy.asked.every((a) => a === "public.example:80")).toBe(true);
    } finally {
      await proxy.close();
    }
  });

  it("reports a destination the proxy refused as blocked instead of scanning the proxy's error page", async () => {
    const proxy = await startForwardProxy();
    try {
      const result = await run(request("http://private-answer.example/", { allowHttp: true, proxyServer: proxy.url }), { resolver: failingResolver });

      expect(result.status).toBe("unsupported");
      expect(result.error?.code).toBe("BLOCKED_DESTINATION");
      expect(result.error?.message).toContain("egress proxy refused");
      expect(result.artifacts).toEqual([]);
    } finally {
      await proxy.close();
    }
  });

  it("reports a destination the proxy could not reach as a failed navigation, not as a scanned error page", async () => {
    const proxy = await startForwardProxy();
    try {
      const result = await run(request("http://down.example/", { allowHttp: true, proxyServer: proxy.url }), { resolver: failingResolver });

      expect(result.status).toBe("failed");
      expect(result.error).toEqual({ code: "NAVIGATION_FAILED", message: "the egress proxy could not reach the destination" });
      expect(result.artifacts).toEqual([]);
    } finally {
      await proxy.close();
    }
  });

  it("without a proxy the local resolver is still required and a failing lookup refuses the scan", async () => {
    const result = await run(request("https://public.example/", { allowHttp: false }), { resolver: failingResolver });

    expect(result.status).toBe("unsupported");
    expect(result.error?.message).toContain("does not resolve");
  });
});

function listenOn(server: TcpServer, host: string): Promise<number> {
  return new Promise((resolve) => server.listen(0, host, () => {
    const address = server.address();
    resolve(typeof address === "object" && address ? address.port : 0);
  }));
}

describe("real-time channels the request route cannot see", () => {
  it("neither the page nor a worker can open a WebSocket, and RTCPeerConnection and WebTransport do not exist", async () => {
    // Page socket: loopback 127.0.0.1, which test mode admits, so only the WebSocket route can stop it.
    // Worker socket: 127.0.0.2 on a non-default port, outside the route, so only the egress gate can stop it.
    const hits = { page: 0, worker: 0 };
    const pageSink = createTcpServer((socket) => { hits.page += 1; socket.destroy(); });
    const workerSink = createTcpServer((socket) => { hits.worker += 1; socket.destroy(); });
    const port = await listenOn(pageSink, "127.0.0.1");
    const wport = await listenOn(workerSink, "127.0.0.2");
    try {
      const result = await run(request(`${fixtures.url}/ws-probe.html?port=${port}&wport=${wport}`, { allowHttp: true }), { allowLoopbackForTests: true });

      expect(result.status, JSON.stringify(result.error)).toBe("completed");
      // Give a socket that slipped through time to arrive before asserting it never did.
      await new Promise((resolve) => setTimeout(resolve, 1000));
      expect(hits).toEqual({ page: 0, worker: 0 });
      expect(result.summary.blockedRequests).toBeGreaterThanOrEqual(2);
      const report = fixtures.seen.find((s) => s.startsWith("/report?"));
      expect(report, fixtures.seen.join("\n")).toBeDefined();
      const q = new URLSearchParams(report!.slice("/report?".length));
      expect(q.get("rtc")).toBe("undefined");
      expect(q.get("wrtc")).toBe("undefined");
      expect(q.get("wt")).toBe("undefined");
    } finally {
      await Promise.all([pageSink, workerSink].map((s) => new Promise((resolve) => s.close(() => resolve(undefined)))));
    }
  });

  it("launches Chromium without QUIC and with WebRTC restricted to proxied connections", async () => {
    const { scannerLaunchArgs } = await import("../src/scan/scanner.js");
    const args = scannerLaunchArgs();

    expect(args).toContain("--disable-quic");
    expect(args).toContain("--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
    expect(args).toContain("--webrtc-ip-handling-policy=disable_non_proxied_udp");
  });
});

describe("egress gate (no proxy configured)", () => {
  it("refuses CONNECT and absolute-URI requests to private destinations and admits a vetted one", async () => {
    const { startEgressGate } = await import("../src/network/egressGate.js");
    let refused = 0;
    const gate = await startEgressGate({ allowHttp: true, allowLoopbackForTests: true }, { lookup: async () => ["10.0.0.1"] }, () => { refused += 1; });
    const gatePort = Number(new URL(gate.url).port);
    const tunnel = (authority: string) => new Promise<string>((resolve) => {
      const socket = connect(gatePort, "127.0.0.1", () => socket.write(`CONNECT ${authority} HTTP/1.1\r\nHost: ${authority}\r\n\r\n`));
      socket.once("data", (d) => { resolve(d.toString("latin1").split("\r\n")[0] ?? ""); socket.destroy(); });
      socket.on("error", () => resolve("error"));
    });
    try {
      expect(await tunnel("127.0.0.2:8080")).toContain("403");
      expect(await tunnel("169.254.169.254:80")).toContain("403");
      expect(await tunnel("rebind.example:443")).toContain("403"); // resolves to 10.0.0.1
      expect(await tunnel(`127.0.0.1:${fixturePort}`)).toContain("200");
      const plain = await fetchViaGate(gatePort, "http://10.0.0.1/");
      expect(plain).toBe(403);
      expect(refused).toBe(4);
    } finally {
      await gate.close();
    }
  });
});

function fetchViaGate(gatePort: number, absoluteUrl: string): Promise<number> {
  return new Promise((resolve) => {
    const req = httpRequest({ host: "127.0.0.1", port: gatePort, path: absoluteUrl, headers: { host: new URL(absoluteUrl).host } }, (res) => { res.resume(); resolve(res.statusCode ?? 0); });
    req.on("error", () => resolve(0));
    req.end();
  });
}
