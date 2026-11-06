/**
 * ZoyaOrb
 * -------
 * The whole interface: one canvas, one button, six states.
 *
 *   disconnected  slow breathing glow
 *   connecting    a sweeping arc
 *   listening     rim waveform from the real mic envelope
 *   thinking      tight neon pulse ring
 *   speaking      rim waveform from the real output spectrum
 *   error         dim, steady, still invites a tap
 *
 * The drawing itself is in lib/orbPainter.ts (pure, testable, renderable to a
 * PNG without a browser). This file is only the React shell around it.
 */
import { useEffect, useRef } from "react";
import { drawOrbFrame, type OrbState } from "../lib/orbPainter";
import type { ZoyaState } from "../lib/LiveSession";

type Props = {
  state: ZoyaState;
  /** 0..1 mic loudness. */
  input: number;
  /** 0..1 model output loudness. */
  output: number;
  /** 32-band spectrum of the output signal. */
  spectrum: Float32Array | null;
  onTap: () => void;
  busy: boolean;
  label: string;
};

export default function ZoyaOrb({
  state,
  input,
  output,
  spectrum,
  onTap,
  busy,
  label,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  // The render loop reads from a ref so React never re-renders at 60fps.
  const live = useRef({ state, input, output, spectrum });
  live.current = { state, input, output, spectrum };

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let raf = 0;
    let t = 0;
    let dpr = Math.min(window.devicePixelRatio || 1, 2);

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.max(1, Math.round(rect.width * dpr));
      canvas.height = Math.max(1, Math.round(rect.height * dpr));
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(canvas);

    const loop = () => {
      t += 1 / 60;
      const { state: s, input: i, output: o, spectrum: sp } = live.current;
      drawOrbFrame(ctx as unknown as Parameters<typeof drawOrbFrame>[0], {
        width: canvas.width,
        height: canvas.height,
        state: s as OrbState,
        time: t,
        input: i,
        output: o,
        spectrum: sp,
        dpr,
      });
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, []);

  const stateText =
    state === "disconnected" ? "Tap to wake" : state === "error" ? "Tap to retry" : label;

  return (
    <button
      type="button"
      onClick={onTap}
      disabled={busy}
      aria-label={state === "disconnected" ? "Start Zoya" : "Stop Zoya"}
      aria-pressed={state !== "disconnected"}
      className="no-tap group relative grid h-[min(78vw,360px)] w-[min(78vw,360px)] place-items-center rounded-full outline-none focus-visible:ring-2 focus-visible:ring-rose/70 disabled:cursor-progress"
    >
      <canvas ref={canvasRef} className="absolute inset-0 h-full w-full" aria-hidden />
      <span className="relative z-10 flex flex-col items-center gap-1 text-center">
        <span
          className="text-[11px] font-medium tracking-[0.42em] text-white/55 uppercase"
          style={{ textShadow: "0 1px 12px rgba(0,0,0,.8)" }}
        >
          {stateText}
        </span>
        {state === "speaking" && (
          <span className="text-[10px] tracking-[0.3em] text-rose-soft/70 uppercase">
            tap to cut her off
          </span>
        )}
      </span>
    </button>
  );
}
