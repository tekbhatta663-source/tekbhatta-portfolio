/**
 * AudioLevelMonitor
 * -----------------
 * One 30 fps ticker that turns analyser nodes into the numbers the UI needs:
 * mic level, output level and a 32-band spectrum for the waveform. Keeps all
 * of that out of React state churn (subscribers get a plain object).
 */
export type LevelFrame = {
  input: number;
  output: number;
  spectrum: Float32Array;
};

type Subscriber = (frame: LevelFrame) => void;

const BANDS = 32;

export class AudioLevelMonitor {
  inputLevel = 0;
  outputLevel = 0;
  readonly spectrum = new Float32Array(BANDS);

  private analyser: AnalyserNode | null = null;
  private data: Uint8Array<ArrayBuffer> | null = null;
  private timer: number | null = null;
  private subs = new Set<Subscriber>();
  private smoothInput = 0;
  private smoothOutput = 0;

  pushInput(level: number): void {
    // Fast attack, slow release reads as a living thing rather than a meter.
    this.smoothInput = level > this.smoothInput ? level : this.smoothInput * 0.86 + level * 0.14;
    this.inputLevel = Math.min(1, this.smoothInput);
  }

  attach(analyser: AnalyserNode | null): void {
    this.analyser = analyser;
    this.data = analyser ? new Uint8Array(new ArrayBuffer(analyser.frequencyBinCount)) : null;
    if (analyser) this.start();
  }

  private start(): void {
    if (this.timer !== null) return;
    const tick = () => {
      if (this.analyser && this.data) {
        this.analyser.getByteFrequencyData(this.data);
        let sum = 0;
        const step = Math.max(1, Math.floor(this.data.length / BANDS));
        for (let b = 0; b < BANDS; b++) {
          let acc = 0;
          for (let i = 0; i < step; i++) acc += this.data[b * step + i] ?? 0;
          const v = acc / step / 255;
          this.spectrum[b] = this.spectrum[b] * 0.55 + v * 0.45;
          sum += v;
        }
        const rms = sum / BANDS;
        this.smoothOutput =
          rms > this.smoothOutput ? rms : this.smoothOutput * 0.82 + rms * 0.18;
        this.outputLevel = Math.min(1, this.smoothOutput * 1.6);
      }
      this.smoothInput *= 0.96;
      this.inputLevel = Math.min(1, this.smoothInput);
      if (this.subs.size) {
        const frame: LevelFrame = {
          input: this.inputLevel,
          output: this.outputLevel,
          spectrum: this.spectrum,
        };
        this.subs.forEach((fn) => fn(frame));
      }
    };
    this.timer = window.setInterval(tick, 33);
  }

  subscribe(fn: Subscriber): () => void {
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }

  /** Called when the model's audio queue drains. */
  resetOutput(): void {
    this.smoothOutput = 0;
    this.outputLevel = 0;
    this.spectrum.fill(0);
  }

  stop(): void {
    if (this.timer !== null) window.clearInterval(this.timer);
    this.timer = null;
    this.subs.clear();
  }
}
