/**
 * AudioStreamer
 * -------------
 * Owns every byte of audio in and out. Deliberately knows nothing about
 * Gemini, React or the DOM beyond the Web Audio API, so it can be unit
 * tested and reused.
 *
 *  - input : getUserMedia -> AudioWorklet (public/pcm-worklet.js) -> PCM16
 *            mono chunks @16 kHz, plus an RMS level for the UI.
 *  - output: a 24 kHz AudioContext -> AudioBufferSourceNodes scheduled on an
 *            absolute play head (gapless), an analyser for the speaking
 *            waveform, and an instant flush() for barge-in.
 *
 * Model audio out is 24 kHz; mic audio in is 16 kHz. Both are int16 LE mono,
 * which is exactly what the Live API expects.
 */
import { AudioLevelMonitor } from "./AudioLevelMonitor";

const OUTPUT_RATE = 24000;
const MIC_RATE = 16000;
/** Never schedule more than this far ahead of the play head. */
const MAX_SCHEDULE_AHEAD_S = 1.2;
/** Small lead-in that absorbs network jitter without feeling laggy. */
const LEAD_IN_S = 0.06;

export type AudioStreamerEvents = {
  /** A 16 kHz PCM16 chunk is ready to be shipped to the model. */
  onInputChunk?: (pcm16: Uint8Array) => void;
};

export class AudioStreamer {
  private events: AudioStreamerEvents;

  private inputCtx: AudioContext | null = null;
  private inputStream: MediaStream | null = null;
  private inputNode: MediaStreamAudioSourceNode | null = null;
  private worklet: AudioWorkletNode | null = null;

  private outputCtx: AudioContext | null = null;
  private gain: GainNode | null = null;
  private analyser: AnalyserNode | null = null;
  private volume = 1;

  private muted = false;
  private playHead = 0;
  private liveSources = new Set<AudioBufferSourceNode>();

  readonly levels = new AudioLevelMonitor();

  constructor(events: AudioStreamerEvents = {}) {
    this.events = events;
  }

  // ---------------------------------------------------------------- input --

  async startInput(): Promise<void> {
    if (this.inputCtx) return;

    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
      video: false,
    });
    this.inputStream = stream;

    const ctx = new AudioContext({ latencyHint: "interactive" });
    this.inputCtx = ctx;
    if (ctx.state === "suspended") await ctx.resume();
    await ctx.audioWorklet.addModule("/pcm-worklet.js");

    const source = ctx.createMediaStreamSource(stream);
    this.inputNode = source;

    const node = new AudioWorkletNode(ctx, "pcm16-downsampler", {
      numberOfInputs: 1,
      numberOfOutputs: 0,
      processorOptions: { targetRate: MIC_RATE },
    });
    this.worklet = node;

    node.port.onmessage = (event: MessageEvent) => {
      const { buffer, rms } = event.data as { buffer: ArrayBuffer; rms: number };
      if (typeof rms === "number") this.levels.pushInput(Math.min(1, rms * 7));
      if (this.muted) return;
      this.events.onInputChunk?.(new Uint8Array(buffer));
    };

    source.connect(node);
  }

  stopInput(): void {
    this.worklet?.port.postMessage("flush");
    this.worklet?.disconnect();
    this.worklet = null;
    this.inputNode?.disconnect();
    this.inputNode = null;
    this.inputStream?.getTracks().forEach((t) => t.stop());
    this.inputStream = null;
    const ctx = this.inputCtx;
    this.inputCtx = null;
    if (ctx) void ctx.close().catch(() => {});
  }

  /** While true, mic audio is captured but not forwarded (prevents echo loops). */
  setInputMuted(muted: boolean): void {
    this.muted = muted;
  }

  get inputRunning(): boolean {
    return !!this.inputCtx;
  }

  // --------------------------------------------------------------- output --

  /** Must first be called from a user gesture (autoplay policy). */
  async ensureOutput(): Promise<AudioContext> {
    if (!this.outputCtx) {
      const ctx = new AudioContext({ latencyHint: "interactive", sampleRate: OUTPUT_RATE });
      this.outputCtx = ctx;
      this.buildOutputGraph(ctx);
      this.playHead = 0;
    }
    if (this.outputCtx.state === "suspended") await this.outputCtx.resume();
    return this.outputCtx;
  }

  private buildOutputGraph(ctx: AudioContext): void {
    const gain = ctx.createGain();
    gain.gain.value = this.volume;
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    analyser.smoothingTimeConstant = 0.6;
    gain.connect(analyser);
    analyser.connect(ctx.destination);
    this.gain = gain;
    this.analyser = analyser;
    this.levels.attach(analyser);
  }

  get outputReady(): boolean {
    return !!this.outputCtx;
  }

  /**
   * Queue one 24 kHz PCM16 chunk from the model. Chunks are scheduled
   * back-to-back on an absolute play head so playback stays gapless.
   */
  async playPcm16(pcm16: Uint8Array): Promise<void> {
    if (this.muted || pcm16.byteLength < 2) return;
    const ctx = await this.ensureOutput();
    if (!this.gain) return;

    const samples = Math.floor(pcm16.byteLength / 2);
    const float = new Float32Array(samples);
    const view = new DataView(pcm16.buffer, pcm16.byteOffset, pcm16.byteLength);
    for (let i = 0; i < samples; i++) float[i] = view.getInt16(i * 2, true) / 0x8000;

    const buffer = ctx.createBuffer(1, samples, OUTPUT_RATE);
    buffer.copyToChannel(float, 0);

    const now = ctx.currentTime;
    const startAt = Math.max(now + LEAD_IN_S, this.playHead);
    if (startAt - now > MAX_SCHEDULE_AHEAD_S) return; // falling behind: drop, don't drift

    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(this.gain);
    src.start(startAt);
    this.playHead = startAt + buffer.duration;

    this.liveSources.add(src);
    src.onended = () => {
      this.liveSources.delete(src);
      src.disconnect();
      if (this.liveSources.size === 0) this.levels.resetOutput();
    };
  }

  /** Barge-in: stop everything that is playing, right now. */
  flush(): void {
    this.playHead = 0;
    for (const src of this.liveSources) {
      try {
        src.stop();
      } catch {
        /* already finished */
      }
      src.disconnect();
    }
    this.liveSources.clear();
    if (this.outputCtx) this.playHead = this.outputCtx.currentTime;
    this.levels.resetOutput();
  }

  /** True while scheduled model audio is still playing (or still queued). */
  get isPlaying(): boolean {
    if (!this.outputCtx) return false;
    return this.playHead > this.outputCtx.currentTime + 0.02;
  }

  get outputDiagram(): AnalyserNode | null {
    return this.analyser;
  }

  setVolume(v: number): void {
    this.volume = Math.max(0, Math.min(1, v));
    if (this.gain) this.gain.gain.value = this.volume;
  }

  async dispose(): Promise<void> {
    this.stopInput();
    this.levels.stop();
    this.flush();
    const ctx = this.outputCtx;
    this.outputCtx = null;
    this.gain = null;
    this.analyser = null;
    if (ctx) await ctx.close().catch(() => {});
  }
}
