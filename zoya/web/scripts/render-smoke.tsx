/**
 * Node smoke test for the React tree: no browser, no audio.
 *
 * Proves that both screens mount without throwing, that the orb renders a
 * canvas, and that the first-run -> live transition works. Run with:
 *   node --import tsx scripts/render-smoke.tsx
 */
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body><div id='root'></div></body></html>", {
  pretendToBeVisual: true,
  url: "http://localhost:5173/",
});

const g = globalThis as unknown as Record<string, unknown>;
g.window = dom.window;
g.document = dom.window.document;
Object.defineProperty(globalThis, "navigator", {
  value: dom.window.navigator,
  configurable: true,
});
g.HTMLElement = dom.window.HTMLElement;
g.Element = dom.window.Element;
g.Node = dom.window.Node;
g.localStorage = dom.window.localStorage;
g.requestAnimationFrame = (cb: FrameRequestCallback) => setTimeout(() => cb(performance.now()), 16) as never;
g.cancelAnimationFrame = (id: number) => clearTimeout(id);
g.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
g.fetch = (async () => ({
  ok: true,
  status: 200,
  json: async () => ({ ok: true, configured: false }),
  text: async () => "",
})) as never;

const { createElement, act } = await import("react");
const { createRoot } = await import("react-dom/client");

const App = (await import("../src/App.tsx")).default;
const FirstRun = (await import("../src/components/FirstRun.tsx")).default;
const ActivityFeed = (await import("../src/components/ActivityFeed.tsx")).default;
const ZoyaOrb = (await import("../src/components/ZoyaOrb.tsx")).default;

const container = dom.window.document.getElementById("root")!;
const root = createRoot(container);

let failures = 0;
function check(name: string, cond: boolean) {
  console.log(`${cond ? "PASS" : "FAIL"}  ${name}`);
  if (!cond) failures++;
}

// --- first run screen -------------------------------------------------------
dom.window.localStorage.clear();
await act(async () => {
  root.render(createElement(App));
});
const text = container.textContent ?? "";
check("first-run headline renders", text.includes("Zoya"));
check("first-run start button renders", text.includes("Wake her up"));
check("no-key warning is shown when unconfigured", text.includes("GEMINI_API_KEY"));
check("voice picker defaults to Aoede", text.includes("Aoede"));

// --- activity feed ----------------------------------------------------------
const { createRoot: createRoot2 } = await import("react-dom/client");
const feedHost = dom.window.document.createElement("div");
dom.window.document.body.appendChild(feedHost);
const feedRoot = createRoot2(feedHost as unknown as HTMLElement);
await act(async () => {
  feedRoot.render(
    createElement(ActivityFeed, {
      items: [
        { id: "a", kind: "tool", label: "Opened YouTube", ok: true, at: Date.now() },
        { id: "b", kind: "tool", label: "Permission denied", ok: false, at: Date.now() },
      ],
    }),
  );
});
check("activity feed renders tool lines", (feedHost.textContent ?? "").includes("Opened YouTube"));
check("activity feed marks failures", (feedHost.textContent ?? "").includes("Permission denied"));

// --- orb --------------------------------------------------------------------
const orbHost = dom.window.document.createElement("div");
dom.window.document.body.appendChild(orbHost);
const orbRoot = createRoot2(orbHost as unknown as HTMLElement);
const spectrum = new Float32Array(32).fill(0.4);
for (const state of ["disconnected", "connecting", "listening", "thinking", "speaking"] as const) {
  await act(async () => {
    orbRoot.render(
      createElement(ZoyaOrb, {
        state,
        input: 0.3,
        output: 0.5,
        spectrum,
        onTap: () => {},
        busy: false,
        label: state,
      }),
    );
  });
  check(
    `orb renders canvas in "${state}" state`,
    orbHost.querySelector("canvas") !== null,
  );
}

// --- orb tap callback fires -------------------------------------------------
let tapped = 0;
await act(async () => {
  orbRoot.render(
    createElement(ZoyaOrb, {
      state: "disconnected",
      input: 0,
      output: 0,
      spectrum: null,
      onTap: () => tapped++,
      busy: false,
      label: "asleep",
    }),
  );
});
(orbHost.querySelector("button") as unknown as { click: () => void }).click();
check("orb tap triggers handler", tapped === 1);

// --- settings round trip ----------------------------------------------------
const { hasSeenIntro, markIntroSeen } = await import("../src/components/FirstRun.tsx");
markIntroSeen();
check("intro is marked seen", hasSeenIntro());

// --- persona sanity ---------------------------------------------------------
const { ZOYA_SYSTEM_INSTRUCTION, ZOYA_TOOLS } = {
  ...(await import("../src/lib/persona.ts")),
  ...(await import("../src/lib/tools.ts")),
} as unknown as { ZOYA_SYSTEM_INSTRUCTION: string; ZOYA_TOOLS: unknown[] };
check("persona mentions sassy guardrails", ZOYA_SYSTEM_INSTRUCTION.includes("explicit"));
check("persona forbids formatting", ZOYA_SYSTEM_INSTRUCTION.includes("spoken out loud"));
check("tool list has openWebsite", ZOYA_TOOLS.length >= 8);

// --- tool execution (no browser APIs needed for these) ----------------------
const tools = await import("../src/lib/tools.ts");
const ctx = { userGestureWindow: () => null };
const time = await tools.executeTool("getCurrentTime", {}, ctx);
check("getCurrentTime returns a timezone", typeof time.output.timeZone === "string");
const remembered = await tools.executeTool(
  "rememberFact",
  { key: "user_city", value: "Kathmandu" },
  ctx,
);
check("rememberFact stores", remembered.ok);
const recalled = await tools.executeTool("recallFacts", {}, ctx);
check(
  "recallFacts returns the stored fact",
  JSON.stringify(recalled.output).includes("Kathmandu"),
);
const timer = await tools.executeTool("setTimer", { seconds: 5, label: "tea" }, ctx);
check("setTimer accepts seconds", timer.ok);
const unknown = await tools.executeTool("selfDestruct", {}, ctx);
check("unknown tool fails safely", unknown.ok === false);

// --- url normalisation ------------------------------------------------------
check("bare domain becomes https", tools.normaliseUrl("youtube.com") === "https://youtube.com");
check(
  "plain words become a search",
  tools.normaliseUrl("best momo in kathmandu")!.startsWith("https://www.google.com/search"),
);
check("full url is preserved", tools.normaliseUrl("https://a.b/c?d=1") === "https://a.b/c?d=1");

await act(async () => {});
console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);
