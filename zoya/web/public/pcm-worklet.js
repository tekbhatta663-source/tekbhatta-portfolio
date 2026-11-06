/**
 * AudioWorklet processor: mic in (any rate) -> PCM16 mono 16 kHz chunks.
 *
 * Runs on the audio render thread, so it does the resampling too. Each
 * message carries one chunk plus the input RMS so the UI can drive the
 * "listening" visual without extra analyser nodes.
 */

const CHUNK_MS = 40;
const TARGET_RATE = 16000;

class Pcm16Downsampler extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = (options && options.processorOptions) || {};
    this.targetRate = opts.targetRate || TARGET_RATE;
    this.chunkSamples = Math.round((this.targetRate * CHUNK_MS) / 1000);
    this.buffer = new Float32Array(0);
    this.ratio = sampleRate / this.targetRate;
    this.phase = 0;
    this.port.onmessage = (e) => {
      if (e.data === "flush") {
        this.buffer = new Float32Array(0);
        this.phase = 0;
      }
    };
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0) return true;
    const channel = input[0];
    if (!channel) return true;

    // Track loudness of the raw mic signal for the meters / barge-in.
    let sum = 0;
    for (let i = 0; i < channel.length; i++) sum += channel[i] * channel[i];
    const rms = Math.sqrt(sum / channel.length);

    // Append raw samples to the carry buffer.
    const merged = new Float32Array(this.buffer.length + channel.length);
    merged.set(this.buffer, 0);
    merged.set(channel, this.buffer.length);

    // Linear-interpolation decimation towards 16 kHz.
    const out = [];
    let pos = this.phase;
    while (pos <= merged.length - 2) {
      const i0 = Math.floor(pos);
      const frac = pos - i0;
      out.push(merged[i0] + (merged[i0 + 1] - merged[i0]) * frac);
      pos += this.ratio;
    }
    const consumed = Math.min(Math.floor(pos), merged.length);
    this.buffer = merged.slice(consumed);
    this.phase = pos - consumed;

    if (out.length > 0) {
      this.pending = this.pending || [];
      for (let i = 0; i < out.length; i++) this.pending.push(out[i]);
    }

    if (this.pending && this.pending.length >= this.chunkSamples) {
      const take = this.chunkSamples;
      const chunk = new Int16Array(take);
      for (let i = 0; i < take; i++) {
        const s = Math.max(-1, Math.min(1, this.pending[i]));
        chunk[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
      }
      this.pending = this.pending.slice(take);
      this.port.postMessage({ buffer: chunk.buffer, rms }, [chunk.buffer]);
    }
    return true;
  }
}

registerProcessor("pcm16-downsampler", Pcm16Downsampler);
