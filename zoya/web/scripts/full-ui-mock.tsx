/**
 * Renders the *whole* app shell to HTML with a mocked Live event stream, so the
 * layout can be inspected (and screenshotted by any tool) without a mic, a key
 * or a browser. Useful as a design review harness.
 *
 *   node --import tsx scripts/full-ui-mock.tsx <state>
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";
import FirstRun from "../src/components/FirstRun";
import ZoyaOrb from "../src/components/ZoyaOrb";
import ActivityFeed from "../src/components/ActivityFeed";
import type { ZoyaState } from "../src/lib/LiveSession";

const state = (process.argv[2] as ZoyaState) ?? "listening";

const orbHtml = renderToStaticMarkup(
  createElement(ZoyaOrb, {
    state,
    input: 0.4,
    output: 0.7,
    spectrum: null,
    onTap: () => {},
    busy: false,
    label: state === "thinking" ? "on it" : state,
  }),
);

const feedHtml = renderToStaticMarkup(
  createElement(ActivityFeed, {
    items: [{ id: "1", kind: "tool", label: "Opened YouTube", ok: true, at: Date.now() }],
  }),
);

const introHtml = renderToStaticMarkup(
  createElement(FirstRun, { onStart: () => {}, configured: false }),
);

mkdirSync("scripts/out", { recursive: true });
writeFileSync(
  `scripts/out/ui-${state}.html`,
  `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;background:#05060a;color:#eef1f8;font-family:system-ui;
display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;gap:18px}
</style></head><body>${feedHtml}${orbHtml}
<p style="color:#9aa0b4;font-size:13.5px">${
    state === "speaking" ? "Zoya's talking" : "Say something"
  }</p></body></html>`,
);
writeFileSync("scripts/out/ui-intro.html", `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;background:#05060a;color:#eef1f8;font-family:system-ui;height:100vh}</style>
</head><body>${introHtml}</body></html>`);

console.log("wrote scripts/out/ui-" + state + ".html and ui-intro.html");
