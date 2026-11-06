/**
 * LiveSession
 * -----------
 * Thin, testable wrapper around one Gemini Live websocket.
 *
 * Responsibilities:
 *  - connect (with an ephemeral token, never the raw API key)
 *  - pump mic PCM up, pump model PCM down into AudioStreamer
 *  - barge-in: when the user starts talking or the server says "interrupted",
 *    stop playback in the same frame
 *  - run function calls and answer them with sendToolResponse immediately
 *  - survive network blips: session resumption handle + capped reconnect
 *
 * Everything it emits arrives as a typed `LiveEvent`; nothing here touches
 * React state, which keeps the UI layer dumb and the audio path fast.
 */
import type { GoogleGenAI, LiveServerMessage, Session } from "@google/genai";
import { AudioStreamer } from "./AudioStreamer";
import { executeTool, ZOYA_TOOLS, type ToolResult } from "./tools";
import { ZOYA_SYSTEM_INSTRUCTION, ZOYA_MODEL, DEFAULT_VOICE } from "./persona";

export type ZoyaState =
  | "disconnected"
  | "connecting"
  | "listening"
  | "thinking"
  | "speaking"
  | "error";

export type ActivityItem = {
  id: string;
  kind: "tool" | "note";
  label: string;
  ok: boolean;
  at: number;
};

export type LiveEvent =
  | { type: "state"; state: ZoyaState }
  | { type: "error"; message: string }
  | { type: "activity"; item: ActivityItem }
  | { type: "level"; input: number; output: number; spectrum: Float32Array }
  | { type: "turnEnd" }
  | { type: "ready" };

export type LiveSessionOptions = {
  voice?: string;
  /** Extra context (user name, local time) folded into the system instruction. */
  contextNote?: string;
  onEvent: (e: LiveEvent) => void;
};

/** Ephemeral Live tokens live in v1alpha only. */
const API_VERSION = "v1alpha";
const MAX_RECONNECTS = 6;
/** Local barge-in: mic energy above this while Zoya talks stops her. */
const BARGE_IN_RMS = 0.085;
const BARGE_IN_FRAMES = 3;

export class LiveSession {
  private ai: GoogleGenAI | null = null;
  private session: Session | null = null;
  private streamer: AudioStreamer | null = null;
  private opts: LiveSessionOptions;
  private emit: (e: LiveEvent) => void;

  private levelUnsub: (() => void) | null = null;
  private timerListener: ((e: Event) => void) | null = null;

  private resumeHandle: string | null = null;
  private reconnects = 0;
  private closing = false;
  private connecting = false;

  private modelSpeaking = false;
  private loudFrames = 0;

  /**
   * A window opened during the tap that started the session. Navigating it
   * later still works, so openWebsite survives popup blockers.
   */
  private gestureWindow: Window | null = null;

  state: ZoyaState = "disconnected";

  constructor(opts: LiveSessionOptions) {
    this.opts = opts;
    this.emit = opts.onEvent;
  }

  private setState(state: ZoyaState): void {
    if (this.state === state) return;
    this.state = state;
    this.emit({ type: "state", state });
  }

  private note(label: string, ok = true): void {
    this.emit({
      type: "activity",
      item: {
        id: Math.random().toString(36).slice(2),
        kind: "note",
        label,
        ok,
        at: Date.now(),
      },
    });
  }

  // ------------------------------------------------------------- lifecycle --

  /**
   * Open the mic + a Live session. Must be called from a user gesture: the
   * AudioContext needs it, and the popup window we may navigate later too.
   */
  async start(): Promise<void> {
    if (this.connecting || this.session) return;
    this.connecting = true;
    this.closing = false;
    this.setState("connecting");

    try {
      try {
        this.gestureWindow = window.open("", "zoya-outbound");
      } catch {
        this.gestureWindow = null;
      }

      this.ensureStreamer();
      await this.streamer!.startInput();
      await this.streamer!.ensureOutput();

      await this.connectSession();
      if (!this.closing) this.setState("listening");
    } catch (err) {
      this.emit({ type: "error", message: describe(err) });
      this.setState("error");
      await this.teardown();
      throw err;
    } finally {
      this.connecting = false;
    }
  }

  private gestureWindowRef = (): Window | null => {
    if (this.gestureWindow && !this.gestureWindow.closed) return this.gestureWindow;
    return null;
  };

  private ensureStreamer(): void {
    if (this.streamer) return;
    this.streamer = new AudioStreamer({
      onInputChunk: (pcm) => {
        try {
          this.session?.sendRealtimeInput({
            audio: { data: toBase64(pcm), mimeType: "audio/pcm;rate=16000" },
          });
        } catch {
          /* socket closing: drop the frame, never throw on the audio path */
        }
      },
    });

    this.levelUnsub = this.streamer.levels.subscribe((f) => {
      // Local barge-in detection. Gemini also sends `interrupted`, but that
      // arrives a few hundred ms late; this cuts her off on the first syllable.
      if (this.modelSpeaking && this.streamer?.outputReady) {
        this.loudFrames = f.input > BARGE_IN_RMS ? this.loudFrames + 1 : 0;
        if (this.loudFrames >= BARGE_IN_FRAMES) {
          this.loudFrames = 0;
          this.modelSpeaking = false;
          this.streamer.flush();
          this.setState("listening");
          this.note("You cut her off");
        }
      }
      this.emit({ type: "level", input: f.input, output: f.output, spectrum: f.spectrum });
    });

    // Timer notifications go back to the model as a real turn so Zoya
    // announces them in her own voice instead of a system beep.
    this.timerListener = (e: Event) => {
      const detail = (e as CustomEvent<{ id: number; label: string }>).detail;
      this.note(`Timer fired · ${detail.label}`);
      try {
        this.session?.sendClientContent({
          turns: [
            {
              role: "user",
              parts: [
                {
                  text: `[on-device event: the "${detail.label}" timer just finished. Tell me in character, one short line.]`,
                },
              ],
            },
          ],
          turnComplete: true,
        });
      } catch {
        /* offline */
      }
    };
    window.addEventListener("zoya:timer", this.timerListener);
  }

  private async fetchToken(): Promise<{ token: string; model: string }> {
    const res = await fetch("/api/token", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ model: ZOYA_MODEL, voice: this.opts.voice }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(
        res.status === 501
          ? "Live API isn't configured. Add GEMINI_API_KEY to .env.local and restart."
          : `Couldn't start a session (${res.status}). ${text.slice(0, 140)}`,
      );
    }
    return (await res.json()) as { token: string; model: string };
  }

  private buildConfig(): Record<string, unknown> {
    const extra = this.opts.contextNote ? `\n\n# Live context\n${this.opts.contextNote}` : "";
    return {
      responseModalities: ["AUDIO"],
      systemInstruction: { parts: [{ text: ZOYA_SYSTEM_INSTRUCTION + extra }] },
      speechConfig: {
        voiceConfig: {
          prebuiltVoiceConfig: { voiceName: this.opts.voice || DEFAULT_VOICE },
        },
      },
      temperature: 0.9,
      tools: [{ functionDeclarations: ZOYA_TOOLS }],
      inputAudioTranscription: {},
      outputAudioTranscription: {},
      realtimeInputConfig: {
        automaticActivityDetection: {
          startOfSpeechSensitivity: "START_SENSITIVITY_HIGH",
          endOfSpeechSensitivity: "END_SENSITIVITY_LOW",
          prefixPaddingMs: 40,
          silenceDurationMs: 600,
        },
      },
      // Long conversations compress instead of dying at the context limit.
      contextWindowCompression: { slidingWindow: {} },
      sessionResumption: this.resumeHandle ? { handle: this.resumeHandle } : {},
    };
  }

  private async connectSession(): Promise<void> {
    const { token, model } = await this.fetchToken();
    // Loaded on demand: the SDK is ~500 kB, and nothing needs it until the
    // user actually taps the orb. Keeps first paint instant on a phone.
    const { GoogleGenAI: Client } = await import("@google/genai");
    this.ai = new Client({
      apiKey: token,
      httpOptions: { apiVersion: API_VERSION },
    });

    this.session = await this.ai.live.connect({
      model: model || ZOYA_MODEL,
      // The SDK's typed LiveConnectConfig lags the wire format; the shape
      // below is what the API accepts today.
      config: this.buildConfig() as never,
      callbacks: {
        onopen: () => {
          this.reconnects = 0;
          this.note("Session live");
          this.emit({ type: "ready" });
        },
        onmessage: (msg: LiveServerMessage) => this.handleMessage(msg),
        onerror: (e: ErrorEvent) => {
          if (!this.closing) this.emit({ type: "error", message: e?.message || "Socket error" });
        },
        onclose: (e: CloseEvent) => this.handleClose(e),
      },
    });
  }

  private handleClose(e: CloseEvent): void {
    if (this.closing) return;
    const clean = e.code === 1000 || e.code === 1005;
    void this.reconnectSoon(clean ? 250 : 800);
  }

  private async reconnectSoon(delay: number): Promise<void> {
    if (this.closing) return;
    this.session = null;
    if (this.reconnects >= MAX_RECONNECTS) {
      this.setState("error");
      this.emit({ type: "error", message: "Connection lost. Tap the orb to try again." });
      await this.teardown();
      return;
    }
    this.reconnects++;
    this.setState("connecting");
    await sleep(delay * this.reconnects);
    if (this.closing) return;
    try {
      await this.connectSession();
      if (!this.closing) this.setState("listening");
    } catch (err) {
      this.emit({ type: "error", message: describe(err) });
      await this.reconnectSoon(delay);
    }
  }

  async stop(): Promise<void> {
    this.closing = true;
    this.setState("disconnected");
    await this.teardown();
  }

  private async teardown(): Promise<void> {
    this.streamer?.flush();
    this.streamer?.stopInput();
    this.levelUnsub?.();
    this.levelUnsub = null;
    if (this.timerListener) window.removeEventListener("zoya:timer", this.timerListener);
    this.timerListener = null;
    await this.streamer?.dispose().catch(() => {});
    this.streamer = null;
    try {
      this.session?.close();
    } catch {
      /* already gone */
    }
    this.session = null;
    this.ai = null;
    this.modelSpeaking = false;
    this.setState("disconnected");
  }

  setVolume(v: number): void {
    this.streamer?.setVolume(v);
  }

  get spectrum(): Float32Array | null {
    return this.streamer?.levels.spectrum ?? null;
  }

  // ------------------------------------------------------------- messaging --

  private handleMessage(msg: LiveServerMessage): void {
    const resume = msg.sessionResumptionUpdate;
    if (resume?.resumable && resume.newHandle) this.resumeHandle = resume.newHandle;

    if (msg.goAway) void this.reconnectSoon(120);

    if (msg.toolCall?.functionCalls?.length) {
      void this.runToolCalls(msg.toolCall.functionCalls);
    }

    const content = msg.serverContent;
    if (!content) return;

    if (content.interrupted) {
      this.streamer?.flush();
      this.modelSpeaking = false;
      this.loudFrames = 0;
      this.setState("listening");
    }

    for (const part of content.modelTurn?.parts ?? []) {
      const inline = part.inlineData;
      if (inline?.data && (inline.mimeType ?? "").startsWith("audio/")) {
        this.modelSpeaking = true;
        if (this.state !== "speaking") this.setState("speaking");
        void this.streamer?.playPcm16(base64ToBytes(inline.data));
      }
    }

    if (content.generationComplete || content.turnComplete) this.watchDrain();
    if (content.turnComplete) this.emit({ type: "turnEnd" });
  }

  /** Return to "listening" once the last scheduled buffer has played out. */
  private watchDrain(): void {
    const started = performance.now();
    const tick = () => {
      if (this.closing) return;
      if (!this.streamer?.isPlaying) {
        this.modelSpeaking = false;
        if (this.state === "speaking" || this.state === "thinking") {
          this.setState("listening");
        }
        return;
      }
      if (performance.now() - started > 60000) return;
      window.setTimeout(tick, 120);
    };
    window.setTimeout(tick, 200);
  }

  // ------------------------------------------------------------- tool flow --

  private async runToolCalls(
    calls: Array<{ id?: string; name?: string; args?: Record<string, unknown> }>,
  ): Promise<void> {
    this.setState("thinking");

    const responses = await Promise.all(
      calls.map(async (call) => {
        const name = call.name ?? "";
        const args = (call.args ?? {}) as Record<string, unknown>;
        let result: ToolResult;
        try {
          result = await executeTool(name, args, {
            userGestureWindow: this.gestureWindowRef,
          });
        } catch (err) {
          result = { ok: false, summary: describe(err), output: { error: describe(err) } };
        }
        this.emit({
          type: "activity",
          item: {
            id: Math.random().toString(36).slice(2),
            kind: "tool",
            label: result.summary,
            ok: result.ok,
            at: Date.now(),
          },
        });
        return { id: call.id, name, response: result.output };
      }),
    );

    try {
      // Answer straight away: the model's turn is blocked until it hears back.
      this.session?.sendToolResponse({ functionResponses: responses });
    } catch (err) {
      this.emit({ type: "error", message: describe(err) });
    }
    if (this.state === "thinking") this.setState("listening");
  }
}

/* ---------------------------------------------------------------- utils -- */

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

function describe(err: unknown): string {
  if (err instanceof Error) {
    if (err.name === "NotAllowedError") return "Mic permission denied. Allow it and try again.";
    if (err.name === "NotFoundError") return "No microphone found.";
    return err.message;
  }
  return String(err);
}

export function toBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
