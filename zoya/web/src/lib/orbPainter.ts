/**
 * orbPainter
 * ----------
 * The orb's artwork as a pure function of (state, time, levels).
 *
 * Kept out of the component on purpose: it is the only thing in the UI with
 * real drawing logic, so it lives where it can be rendered to a file and
 * inspected without a browser (see scripts/render-orb.ts) and unit-tested for
 * "does not throw" across every state.
 */
export type OrbState =
  | "disconnected"
  | "connecting"
  | "listening"
  | "thinking"
  | "speaking"
  | "error";

export type OrbFrame = {
  /** Canvas size in device pixels. */
  width: number;
  height: number;
  state: OrbState;
  /** Seconds since the animation started. */
  time: number;
  /** 0..1 smoothed mic level. */
  input: number;
  /** 0..1 smoothed playback level. */
  output: number;
  /** 32-band spectrum of the output signal (speaking only). */
  spectrum?: Float32Array | null;
  /** Extra scale for the press/orb reaction. */
  scale?: number;
  /** devicePixelRatio used to size the canvas. */
  dpr?: number;
  /**
   * Optional opaque background. The app sits the canvas on a CSS gradient and
   * leaves this unset; offline renders pass a colour so the aura reads right.
   */
  background?: string;
};

const ROSE = "#ff7ea8";
const VIOLET = "#a45bff";

/** Minimal surface the painter needs; matches CanvasRenderingContext2D. */
export type Ctx2D = {
  clearRect(x: number, y: number, w: number, h: number): void;
  save(): void;
  restore(): void;
  beginPath(): void;
  closePath(): void;
  moveTo(x: number, y: number): void;
  lineTo(x: number, y: number): void;
  arc(
    x: number,
    y: number,
    r: number,
    start: number,
    end: number,
    counter?: boolean,
  ): void;
  arcTo(x1: number, y1: number, x2: number, y2: number, r: number): void;
  fill(): void;
  stroke(): void;
  translate(x: number, y: number): void;
  createRadialGradient(
    x0: number,
    y0: number,
    r0: number,
    x1: number,
    y1: number,
    r1: number,
  ): { addColorStop(offset: number, color: string): void };
  createLinearGradient(
    x0: number,
    y0: number,
    x1: number,
    y1: number,
  ): { addColorStop(offset: number, color: string): void };
  fillStyle: string | { addColorStop(o: number, c: string): void };
  strokeStyle: string | { addColorStop(o: number, c: string): void };
  lineWidth: number;
  lineCap: string;
  lineJoin: string;
  shadowColor: string;
  shadowBlur: number;
};

export function drawOrbFrame(ctx: Ctx2D, frame: OrbFrame): void {
  const { width: w, height: h, state, time: t } = frame;
  const dpr = frame.dpr ?? 1;
  const spectrum = frame.spectrum ?? null;
  const scale = frame.scale ?? 1;

  const cx = w / 2;
  const cy = h / 2;
  const R = Math.min(w, h) * 0.29 * scale;

  ctx.clearRect(0, 0, w, h);
  if (frame.background) {
    ctx.fillStyle = frame.background;
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(w, 0);
    ctx.lineTo(w, h);
    ctx.lineTo(0, h);
    ctx.closePath();
    ctx.fill();
  }
  ctx.save();

  const breathing = 0.5 + 0.5 * Math.sin(t * 0.9);
  const energy =
    state === "speaking"
      ? Math.min(1, 0.35 + frame.output * 1.5)
      : state === "listening"
        ? Math.min(1, 0.18 + frame.input * 1.4)
        : state === "thinking"
          ? 0.7
          : state === "connecting"
            ? 0.45
            : 0.22;
  const pulse =
    state === "disconnected" || state === "error" ? 0.35 + breathing * 0.35 : energy;

  // ------------------------------------------------------------- outer aura --
  const aura = ctx.createRadialGradient(cx, cy, R * 0.2, cx, cy, R * 2.1);
  aura.addColorStop(0, `rgba(255,126,168,${0.3 * pulse})`);
  aura.addColorStop(0.34, `rgba(164,91,255,${0.18 * pulse})`);
  aura.addColorStop(0.7, `rgba(103,232,249,${0.05 * pulse})`);
  aura.addColorStop(1, "rgba(5,6,10,0)");
  ctx.fillStyle = aura;
  ctx.beginPath();
  ctx.arc(cx, cy, R * 2.1, 0, Math.PI * 2);
  ctx.fill();

  // ------------------------------------------------------ orbiting hairlines --
  if (state !== "disconnected" && state !== "error") {
    ctx.lineWidth = Math.max(1, dpr);
    for (let ring = 0; ring < 2; ring++) {
      const rr = R * (1.36 + ring * 0.17);
      const speed = (ring === 0 ? 1 : -0.6) * (state === "thinking" ? 2.2 : 0.55);
      const start = t * speed;
      const arc = Math.PI * (ring === 0 ? 0.44 : 0.3);
      ctx.beginPath();
      ctx.strokeStyle =
        ring === 0
          ? `rgba(255,179,201,${0.25 + 0.35 * pulse})`
          : `rgba(164,91,255,${0.2 + 0.3 * pulse})`;
      ctx.arc(cx, cy, rr, start, start + arc);
      ctx.stroke();
    }
  }

  // ------------------------------------------------------- rim waveform ----
  const bars = 96;
  if (state === "listening" || state === "speaking") {
    ctx.lineCap = "round";
    for (let i = 0; i < bars; i++) {
      const a = (i / bars) * Math.PI * 2 - Math.PI / 2;
      let amp: number;
      if (state === "speaking" && spectrum && spectrum.length > 0) {
        amp = spectrum[Math.floor((i / bars) * spectrum.length)] ?? 0;
      } else {
        amp =
          frame.input *
          (0.5 +
            0.5 *
              Math.abs(
                Math.sin(a * 3 + t * 3.1) * 0.6 + Math.sin(a * 7 - t * 1.7) * 0.4,
              ));
      }
      const len = R * (0.075 + amp * 0.42);
      const inner = R * 1.06;
      const x1 = cx + Math.cos(a) * inner;
      const y1 = cy + Math.sin(a) * inner;
      const x2 = cx + Math.cos(a) * (inner + len);
      const y2 = cy + Math.sin(a) * (inner + len);
      const alpha = 0.25 + Math.min(0.75, amp * 1.6);
      ctx.strokeStyle =
        state === "speaking"
          ? `rgba(255,179,201,${alpha})`
          : `rgba(103,232,249,${alpha * 0.9})`;
      ctx.lineWidth = Math.max(1.4, R * 0.014) * dpr;
      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      ctx.stroke();
    }
  } else if (state === "thinking") {
    ctx.lineCap = "round";
    for (let i = 0; i < 48; i++) {
      const a = (i / 48) * Math.PI * 2;
      const wave = 0.5 + 0.5 * Math.sin(t * 6 + i * 0.6);
      const inner = R * 1.07;
      const len = R * (0.03 + wave * 0.12);
      ctx.strokeStyle = `rgba(164,91,255,${0.25 + wave * 0.6})`;
      ctx.lineWidth = Math.max(1.4, R * 0.016) * dpr;
      ctx.beginPath();
      ctx.moveTo(cx + Math.cos(a) * inner, cy + Math.sin(a) * inner);
      ctx.lineTo(cx + Math.cos(a) * (inner + len), cy + Math.sin(a) * (inner + len));
      ctx.stroke();
    }
  }

  // ------------------------------------------------------------- the orb ----
  const orbScale = 1 + (state === "speaking" ? 0.03 * Math.sin(t * 12) : 0);
  const orbR = R * orbScale;

  const core = ctx.createRadialGradient(
    cx - orbR * 0.28,
    cy - orbR * 0.34,
    orbR * 0.06,
    cx,
    cy,
    orbR,
  );
  core.addColorStop(0, "rgba(255,255,255,0.95)");
  core.addColorStop(0.16, "rgba(255,196,214,0.92)");
  core.addColorStop(0.46, ROSE);
  core.addColorStop(0.78, VIOLET);
  core.addColorStop(1, "rgba(20,12,40,0.92)");
  ctx.save();
  ctx.shadowColor = `rgba(255,126,168,${0.45 + 0.35 * pulse})`;
  ctx.shadowBlur = R * (0.55 + 0.7 * pulse);
  ctx.fillStyle = core;
  ctx.beginPath();
  ctx.arc(cx, cy, orbR, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  const glass = ctx.createLinearGradient(cx, cy - orbR, cx, cy + orbR);
  glass.addColorStop(0, "rgba(255,255,255,0.28)");
  glass.addColorStop(0.45, "rgba(255,255,255,0.02)");
  glass.addColorStop(1, "rgba(5,6,10,0.35)");
  ctx.fillStyle = glass;
  ctx.beginPath();
  ctx.arc(cx, cy, orbR, 0, Math.PI * 2);
  ctx.fill();

  drawGlyph(ctx, state, cx, cy, orbR, t * 1000, dpr);

  ctx.restore();
}

function drawGlyph(
  ctx: Ctx2D,
  state: OrbState,
  cx: number,
  cy: number,
  R: number,
  ms: number,
  dpr: number,
): void {
  ctx.save();
  ctx.translate(cx, cy);
  ctx.strokeStyle = "rgba(10,6,20,0.72)";
  ctx.fillStyle = "rgba(10,6,20,0.72)";
  ctx.lineCap = "round";
  ctx.lineJoin = "round";

  if (state === "connecting") {
    const a0 = (ms / 300) % (Math.PI * 2);
    ctx.lineWidth = Math.max(2, R * 0.045);
    ctx.beginPath();
    ctx.arc(0, 0, R * 0.34, a0, a0 + Math.PI * 1.25);
    ctx.stroke();
    ctx.restore();
    return;
  }

  if (state === "thinking") {
    for (let i = 0; i < 3; i++) {
      const phase = (ms / 420 + i * 0.6) % 2;
      const x = (i - 1) * R * 0.24;
      const alpha = 0.3 + 0.7 * Math.max(0, 1 - Math.abs(phase - 1));
      ctx.fillStyle = `rgba(10,6,20,${alpha * 0.8})`;
      ctx.beginPath();
      ctx.arc(x, 0, R * 0.12, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
    return;
  }

  if (state === "speaking") {
    for (let i = 0; i < 5; i++) {
      const h = R * 0.16 + R * 0.2 * Math.abs(Math.sin(ms / 190 + i * 0.9));
      const x = (i - 2) * R * 0.16;
      roundRect(ctx, x - R * 0.035, -h / 2, R * 0.07, h, R * 0.04);
      ctx.fill();
    }
    ctx.restore();
    return;
  }

  // Microphone (idle / listening / error / disconnected).
  const w = R * 0.22;
  const h = R * 0.4;
  roundRect(ctx, -w / 2, -h * 0.62, w, h, w / 2);
  ctx.fill();
  ctx.beginPath();
  ctx.lineWidth = Math.max(2, R * 0.035) * dpr * 0.5;
  ctx.arc(0, h * 0.28, R * 0.19, 0.15 * Math.PI, 0.85 * Math.PI);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(0, h * 0.28 + R * 0.19);
  ctx.lineTo(0, h * 0.28 + R * 0.33);
  ctx.stroke();
  ctx.restore();
}

function roundRect(
  ctx: Ctx2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
): void {
  const radius = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
}
