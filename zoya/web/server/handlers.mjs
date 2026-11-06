/**
 * Zoya server handlers — shared by Vite (dev + preview middleware) and by
 * server/serve.mjs (production). One implementation, two hosts.
 *
 *   POST /api/token  { model?, voice? } -> { token, model, voice, apiVersion }
 *   POST /api/tool   { name, args }     -> policy check for outbound URLs
 *   GET  /api/health                    -> { ok, configured, model }
 *
 * The GEMINI_API_KEY never reaches the browser. The client only ever sees a
 * single-use ephemeral token locked to the Live model + persona voice.
 */
import { GoogleGenAI } from "@google/genai";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = resolve(fileURLToPath(new URL(".", import.meta.url)));

/**
 * Minimal .env loader.
 *
 * Vite only injects VITE_-prefixed vars into process.env, so the server reads
 * its own keys straight from disk. Real environment variables always win, which
 * keeps Docker/Vercel/host config authoritative.
 */
let envLoaded = false;
function loadEnvFiles() {
  if (envLoaded) return;
  envLoaded = true;
  const candidates = [
    resolve(HERE, "../.env.local"),
    resolve(HERE, "../.env"),
    resolve(HERE, "../../.env.local"),
    resolve(HERE, "../../.env"),
  ];
  for (const file of candidates) {
    let raw;
    try {
      raw = readFileSync(file, "utf8");
    } catch {
      continue;
    }
    for (const line of raw.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const eq = trimmed.indexOf("=");
      if (eq < 1) continue;
      const key = trimmed.slice(0, eq).trim();
      let value = trimmed.slice(eq + 1).trim();
      if (
        (value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))
      ) {
        value = value.slice(1, -1);
      }
      if (!(key in process.env)) process.env[key] = value;
    }
  }
}

const DEFAULT_MODEL = "gemini-3.1-flash-live-preview";
const DEFAULT_VOICE = "Aoede";
/** Ephemeral auth tokens are only issued by the v1alpha surface. */
const API_VERSION = "v1alpha";

/** Very small per-IP rate limiter: every token mint costs money. */
const buckets = new Map();
const WINDOW_MS = 60_000;
const MAX_PER_WINDOW = 12;

function rateLimited(ip) {
  const now = Date.now();
  const bucket = buckets.get(ip) ?? { count: 0, reset: now + WINDOW_MS };
  if (now > bucket.reset) {
    bucket.count = 0;
    bucket.reset = now + WINDOW_MS;
  }
  bucket.count += 1;
  buckets.set(ip, bucket);
  if (buckets.size > 5000) buckets.clear();
  return bucket.count > MAX_PER_WINDOW;
}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("Body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new Error("Invalid JSON body"));
      }
    });
    req.on("error", reject);
  });
}

function json(res, status, payload) {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.end(JSON.stringify(payload));
}

/**
 * Allow only http(s). Blocks javascript:, data:, file:, intent:// and bare
 * hosts. The client normalises first; this is defence in depth.
 */
export function isSafeUrl(raw) {
  try {
    const url = new URL(raw);
    return (url.protocol === "https:" || url.protocol === "http:") && Boolean(url.hostname);
  } catch {
    return false;
  }
}

function clientIp(req) {
  const fwd = req.headers["x-forwarded-for"];
  if (typeof fwd === "string" && fwd) return fwd.split(",")[0].trim();
  return req.socket?.remoteAddress ?? "unknown";
}

async function handleToken(req, res) {
  loadEnvFiles();
  if (rateLimited(clientIp(req))) {
    return json(res, 429, { error: "Slow down — too many sessions started at once." });
  }

  const apiKey = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
  if (!apiKey) {
    return json(res, 501, {
      error:
        "GEMINI_API_KEY is not set. Copy .env.example to .env.local, add your key, then restart the dev server.",
    });
  }

  let body = {};
  try {
    body = await readBody(req);
  } catch (err) {
    return json(res, 400, { error: String(err?.message ?? err) });
  }

  const model = typeof body.model === "string" && body.model ? body.model : DEFAULT_MODEL;
  const voice = typeof body.voice === "string" && body.voice ? body.voice : DEFAULT_VOICE;

  const ai = new GoogleGenAI({ apiKey, httpOptions: { apiVersion: API_VERSION } });

  try {
    const token = await ai.tokens.create({
      config: {
        // One connection per token; reconnects reuse the session resume handle.
        uses: 1,
        expireTime: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
        newSessionExpireTime: new Date(Date.now() + 2 * 60 * 1000).toISOString(),
        liveConnectConstraints: {
          model,
          config: {
            // Locked: the browser cannot ask for TEXT output or another voice.
            responseModalities: ["AUDIO"],
            speechConfig: {
              voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } },
            },
          },
        },
      },
    });

    if (!token?.name) return json(res, 502, { error: "Gemini returned an empty token." });

    return json(res, 200, {
      token: token.name,
      model,
      voice,
      apiVersion: API_VERSION,
      expiresAt: token.expireTime ?? null,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error("[zoya] token mint failed:", message);
    return json(res, 502, {
      error: message.replace(apiKey, "[redacted]").slice(0, 400),
    });
  }
}

/**
 * Outbound-link policy check.
 *
 * A browser will not let a server navigate a tab, so the actual navigation is
 * done by the client inside a window it reserved during a user gesture. This
 * route exists to keep one allow-list in one place and to log what she opens.
 */
async function handleTool(req, res) {
  if (rateLimited(clientIp(req))) return json(res, 429, { error: "Too many tool calls." });

  let body;
  try {
    body = await readBody(req);
  } catch (err) {
    return json(res, 400, { error: String(err?.message ?? err) });
  }

  if (body?.name !== "openWebsite") {
    return json(res, 400, {
      error: "This route only validates outbound links; other tools run on the device.",
    });
  }

  const url = typeof body?.args?.url === "string" ? body.args.url : "";
  if (!isSafeUrl(url)) {
    return json(res, 400, { error: "Refused to open an unsafe URL", allowed: false });
  }

  return json(res, 200, { allowed: true, url, via: "client-gesture-window" });
}

export async function handleApiRequest(req, res) {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = url.pathname;

  // Same-origin only. The preview proxy rewrites Host, so compare only when
  // both headers look like real hosts.
  const origin = req.headers.origin;
  const host = req.headers.host;
  if (origin && host && origin !== "null") {
    try {
      if (new URL(origin).host !== host) {
        return json(res, 403, { error: "Cross-origin request refused" });
      }
    } catch {
      return json(res, 403, { error: "Bad origin" });
    }
  }

  if (path === "/api/health") {
    loadEnvFiles();
    const configured = Boolean(process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY);
    return json(res, 200, { ok: true, configured, model: DEFAULT_MODEL });
  }

  if (path === "/api/token") {
    if (req.method !== "POST") return json(res, 405, { error: "Use POST" });
    return handleToken(req, res);
  }

  if (path === "/api/tool") {
    if (req.method !== "POST") return json(res, 405, { error: "Use POST" });
    return handleTool(req, res);
  }

  return json(res, 404, { error: "Unknown API route" });
}
