import { createServer, request as httpRequest, type IncomingMessage, type ServerResponse } from "node:http";
import { connect, type Socket } from "node:net";
import { PROXY_REFUSED_HEADER } from "./requestGuard.js";
import { BlockedDestinationError, checkUrlSyntax, resolvePublic, type DestinationPolicy, type Resolver } from "./urlPolicy.js";

export interface EgressGate {
  /** Proxy URL for Chromium and for the guard's own fetches. */
  url: string;
  close(): Promise<void>;
}

/**
 * A loopback HTTP proxy that applies the destination policy at connection time, used when no egress proxy is
 * configured. Chromium opens some connections itself, outside the request route: a redirect's follow-up request, a
 * worker's WebSocket, WebRTC over TCP. Pointing the browser at this gate puts those under the same rules as every
 * routed request, and each connection goes to the address that was vetted, so a second DNS answer cannot rebind it.
 */
export async function startEgressGate(policy: DestinationPolicy, resolver: Resolver, onRefused: () => void): Promise<EgressGate> {
  const vet = async (scheme: "http" | "https", host: string): Promise<{ address: string; port: number }> => {
    const checked = checkUrlSyntax(`${scheme}://${host}/`, policy);
    const [address] = await resolvePublic(checked, resolver, { ...policy, resolveViaProxy: false });
    if (!address) throw new BlockedDestinationError("hostname does not resolve");
    return { address, port: Number(checked.url.port || (scheme === "https" ? 443 : 80)) };
  };

  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    void (async () => {
      let target: URL;
      try {
        target = new URL(req.url ?? "");
        if (target.protocol !== "http:") throw new BlockedDestinationError("only absolute http URLs are proxied");
        const { address, port } = await vet("http", target.host);
        const upstream = httpRequest({ host: address, port, method: req.method, path: target.pathname + target.search, headers: { ...req.headers, host: target.host } }, (r) => {
          res.writeHead(r.statusCode ?? 502, r.headers);
          r.pipe(res);
        });
        upstream.on("error", () => res.destroy());
        req.pipe(upstream);
      } catch {
        onRefused();
        res.writeHead(403, { "content-type": "text/plain", [PROXY_REFUSED_HEADER]: "1" }).end("refused by the scanner");
      }
    })();
  });

  server.on("connect", (req: IncomingMessage, client: Socket, head: Buffer) => {
    client.on("error", () => client.destroy());
    void (async () => {
      try {
        // CONNECT carries host:port only; 443 is checked as https, anything else as http (WebSockets use port 80).
        const authority = req.url ?? "";
        const port = new URL(`http://${authority}`).port;
        const { address, port: vettedPort } = await vet(port === "" || port === "443" ? "https" : "http", authority);
        const upstream = connect(vettedPort, address, () => {
          client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
          if (head.length) upstream.write(head);
          upstream.pipe(client);
          client.pipe(upstream);
        });
        upstream.on("error", () => client.destroy());
        client.on("close", () => upstream.destroy());
      } catch {
        onRefused();
        client.end(`HTTP/1.1 403 Forbidden\r\n${PROXY_REFUSED_HEADER}: 1\r\nContent-Length: 0\r\n\r\n`);
      }
    })();
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  return {
    url: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve) => {
      server.closeAllConnections();
      server.close(() => resolve());
    }),
  };
}
