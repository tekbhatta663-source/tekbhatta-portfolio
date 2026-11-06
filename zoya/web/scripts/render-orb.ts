/**
 * Renders the orb to PNGs for every state, straight through the same painter
 * the browser uses. This is how the visuals get reviewed without opening a
 * browser — and it fails loudly if the painter ever throws.
 *
 *   node --import tsx scripts/render-orb.ts
 */
import { createCanvas } from "@napi-rs/canvas";
import { mkdirSync } from "node:fs";
import { drawOrbFrame, type OrbFrame, type OrbState } from "../src/lib/orbPainter";

const OUT = "scripts/out";
mkdirSync(OUT, { recursive: true });

const SIZE = 720;
const spectrum = new Float32Array(32).map((_, i) =>
  Math.max(0, 0.15 + 0.85 * Math.abs(Math.sin(i * 0.7)) * Math.exp(-i / 26)),
);

const states: Array<{ state: OrbState; time: number }> = [
  { state: "disconnected", time: 1.2 },
  { state: "connecting", time: 2.4 },
  { state: "listening", time: 3.1 },
  { state: "thinking", time: 1.7 },
  { state: "speaking", time: 4.3 },
  { state: "error", time: 0.9 },
];

for (const { state, time } of states) {
  const canvas = createCanvas(SIZE, SIZE);
  const ctx = canvas.getContext("2d");
  const frame: OrbFrame = {
    width: SIZE,
    height: SIZE,
    state,
    time,
    input: 0.42,
    output: 0.68,
    spectrum,
    dpr: 2,
    // Match the app background so the glow reads the way it does on a phone.
    background: "#05060a",
  };
  drawOrbFrame(ctx as never, frame);

  const path = `${OUT}/orb-${state}.png`;
  const { writeFileSync } = await import("node:fs");
  writeFileSync(path, canvas.toBuffer("image/png"));
  console.log("wrote", path);
}

// A guard: the painter must survive degenerate frames.
drawOrbFrame(createCanvas(2, 2).getContext("2d") as never, {
  width: 2,
  height: 2,
  state: "speaking",
  time: 0,
  input: 0,
  output: 0,
  spectrum: new Float32Array(0),
  dpr: 1,
});
console.log("degenerate frame ok");
