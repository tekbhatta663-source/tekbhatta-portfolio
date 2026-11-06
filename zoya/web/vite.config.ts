import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/**
 * Dev/preview middleware that exposes the two server-side routes Zoya needs:
 *
 *   POST /api/token  -> mints a short-lived ephemeral Live API token so the
 *                       browser never sees GEMINI_API_KEY.
 *   POST /api/tool   -> runs browser tools that cannot run in the tab
 *                       (openWebsite). Everything else runs client-side.
 *
 * Both are implemented as plain node handlers in web/server/handlers.mjs and are
 * ALSO served by server/serve.mjs for production, so there is exactly one
 * implementation.
 */
function zoyaApi(): Plugin {
  return {
    name: "zoya-api",
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        if (!req.url?.startsWith("/api/")) return next();
        const { handleApiRequest } = await import("./server/handlers.mjs");
        await handleApiRequest(req, res);
      });
    },
    configurePreviewServer(server) {
      server.middlewares.use(async (req, res, next) => {
        if (!req.url?.startsWith("/api/")) return next();
        // Preview has no module graph to load from; import directly.
        const { handleApiRequest } = await import("./server/handlers.mjs");
        await handleApiRequest(req, res);
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), zoyaApi()],
  server: {
    host: "0.0.0.0",
    port: 5173,
    // The Arena preview is proxied from https://<port>-<sandbox>.e2b.app
    allowedHosts: true,
    cors: true,
    // Offline render output and the scripts that write it must not trigger
    // reloads while the app is open.
    watch: { ignored: ["**/scripts/out/**", "**/dist/**"] },
  },
  preview: { host: "0.0.0.0", port: 4173, allowedHosts: true },
  build: { target: "es2022", sourcemap: false, outDir: "dist" },
});
