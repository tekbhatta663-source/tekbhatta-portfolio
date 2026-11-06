#!/usr/bin/env node
/**
 * Production server for Zoya.
 *
 *   npm run build       (in zoya/web)
 *   node zoya/web/server/serve.mjs
 *
 * Serves zoya/web/dist plus the /api routes from handlers.mjs. Any host that
 * can run Node works; there is no framework, no build step for the server and
 * no dependency beyond @google/genai (already installed in zoya/web).
 *
 * Security headers: mic access requires a secure context (https or localhost),
 * so we send a strict CSP that still allows getUserMedia + the Gemini socket.
 */
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { handleApiRequest } from "./handlers.mjs";

const here = resolve(fileURLToPath(new URL(".", import.meta.url)));
const DIST = resolve(here, "../dist");
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || "0.0.0.0";

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".webmanifest": "application/manifest+json",
  ".woff2": "font/woff2",
  ".map": "application/json",
};

const CSP = [
  "default-src 'self'",
  // Vite emits inline module preloads; the worklet is a same-origin blob-free file.
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "media-src 'self' blob:",
  "font-src 'self' data:",
  // The socket goes straight from the browser to Google.
  "connect-src 'self' https://generativelanguage.googleapis.com wss://generativelanguage.googleapis.com https://*.googleapis.com",
  "worker-src 'self' blob:",
  "frame-ancestors *",
  "base-uri 'self'",
  "form-action 'self'",
].join("; ");

function withHeaders(res) {
  res.setHeader("Content-Security-Policy", CSP);
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  // getUserMedia is powerful-feature gated; this is for the mic only.
  res.setHeader("Permissions-Policy", "microphone=(self), camera=(), geolocation=()");
  // Allow the Arena preview proxy to embed the app.
  res.setHeader("Cross-Origin-Opener-Policy", "unsafe-none");
}

async function sendFile(res, filePath) {
  const data = await readFile(filePath);
  res.statusCode = 200;
  res.setHeader("content-type", MIME[extname(filePath)] ?? "application/octet-stream");
  const immutable = /\/assets\/.+-[A-Za-z0-9_-]{6,}\./.test(filePath);
  res.setHeader(
    "cache-control",
    immutable ? "public, max-age=31536000, immutable" : "no-cache",
  );
  res.end(data);
}

const server = createServer(async (req, res) => {
  try {
    if ((req.url ?? "").startsWith("/api/")) {
      withHeaders(res);
      return await handleApiRequest(req, res);
    }

    withHeaders(res);
    const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
    let pathname = decodeURIComponent(url.pathname);

    // Traversal guard: resolve inside DIST only.
    const target = normalize(join(DIST, pathname));
    if (!target.startsWith(DIST)) {
      res.statusCode = 403;
      return res.end("Forbidden");
    }

    try {
      const info = await stat(target);
      if (info.isFile()) return await sendFile(res, target);
    } catch {
      /* fall through */
    }

    // SPA fallback.
    return await sendFile(res, join(DIST, "index.html"));
  } catch (err) {
    console.error("[zoya] request failed", err);
    if (!res.headersSent) res.statusCode = 500;
    res.end("Internal error");
  }
});

server.listen(PORT, HOST, () => {
  const configured = Boolean(process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY);
  console.log(`Zoya listening on http://${HOST}:${PORT}`);
  console.log(`  dist:      ${DIST}`);
  console.log(`  gemini:    ${configured ? "key present" : "NO KEY — /api/token will 501"}`);
});
