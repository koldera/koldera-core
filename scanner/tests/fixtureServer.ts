import { createServer, type Server } from "node:http";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

/**
 * Loopback HTTP server standing in for a public site: fixture pages (/missing-label.html,
 * /labelled.html), /redirect?to=<url>, /slow?ms=<n>, /many.html (60 subresources) and
 * /big?bytes=<n>.
 */
export async function startFixtureServer(): Promise<{ server: Server; url: string; close: () => Promise<void> }> {
  const server = createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    const send = (status: number, body: string, type = "text/html; charset=utf-8", headers: Record<string, string> = {}) => {
      res.writeHead(status, { "content-type": type, "content-length": Buffer.byteLength(body), ...headers });
      res.end(body);
    };
    if (url.pathname === "/missing-label.html" || url.pathname === "/labelled.html") {
      send(200, readFileSync(path.join(here, "fixtures", url.pathname.slice(1)), "utf8"));
    } else if (url.pathname === "/redirect") {
      res.writeHead(302, { location: url.searchParams.get("to") ?? "/labelled.html" });
      res.end();
    } else if (url.pathname === "/slow") {
      setTimeout(() => send(200, "<html lang='en'><title>slow</title><body>late</body></html>"), Number(url.searchParams.get("ms") ?? "5000"));
    } else if (url.pathname === "/many.html") {
      const imgs = Array.from({ length: 60 }, (_, i) => `<img src="/px?i=${i}" alt="">`).join("");
      send(200, `<html lang='en'><head><title>many</title></head><body>${imgs}</body></html>`);
    } else if (url.pathname === "/px") {
      send(200, "x", "text/plain");
    } else if (url.pathname === "/big") {
      const n = Number(url.searchParams.get("bytes") ?? "1000000");
      send(200, `<html lang='en'><head><title>big</title></head><body>${"a".repeat(Math.max(0, n - 60))}</body></html>`);
    } else if (url.pathname === "/echo-headers") {
      send(200, `<html lang='en'><head><title>h</title></head><body><pre id="h">${JSON.stringify(req.headers)}</pre></body></html>`);
    } else {
      send(404, "<html lang='en'><head><title>404</title></head><body><h1>Not found</h1></body></html>");
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  return { server, url: `http://127.0.0.1:${port}`, close: () => new Promise((resolve) => server.close(() => resolve())) };
}
