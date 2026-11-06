/**
 * AudioWorklet processor: mic in (any rate) -> PCM16 mono 16 kHz chunks.
 *
 * Runs on the **audio render thread**, where allocation is a real hazard: the
 * render thread shares its budget with every other audio node, and a GC pause
 * here is an audible dropout. So this file never allocates in process():
 *
 *   - the sample rate conversion writes into a preallocated Float32Array
 *     (ring of fixed size, integer read/write indices, no Array.push)
 *   - the Int16 chunk is one buffer per emitted chunk, allocated when the
 *     chunk *completes* — 25 per second at 40 ms, and it has to exist either
 *     way to cross the port boundary
 *   - nothing is sliced, concat'd or pushed per sample
 *
 * 40 ms chunks are a deliberate trade-off: 25 messages/second keeps the
 * postMessage overhead negligible while staying well inside the Live API's
 * latency budget (the model starts answering after ~200 ms of speech).
 */

const CHUNK_MS = 40;
const TARGET_RATE = 16000;
/** Power-of-two ring for resampler carry-over (must be a power of two). */
const CARRY_CAPACITY = 256;
const CARRY_MASK = CARRY_CAPACITY - 1;
/** Hard cap on a single chunk, so a stuck resampler can never grow unbounded. */
const MAX_CHUNK_SAMPLES = 8192;

class Pcm16Downsampler extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = (options && options.processorOptions) || {};
    this.targetRate = opts.targetRate || TARGET_RATE;
    this.chunkSamples = Math.min(
      MAX_CHUNK_SAMPLES,
      Math.round((this.targetRate * CHUNK_MS) / 1000),
    );

    // 1.0 = no resampling needed (device already at 16 kHz) — the common case
    // on Android Chrome, and by far the fastest path.
    this.ratio = sampleRate / this.targetRate;
    this.passthrough = Math.abs(this.ratio - 1) < 1e-6;

    // Carry ring for linear-interpolation state between process() calls.
    this.carry = new Float32Array(CARRY_CAPACITY);
    this.carryLen = 0;
    this.phase = 0;

    // Output accumulator for the current chunk.
    this.chunk = new Int16Array(this.chunkSamples);
    this.chunkFill = 0;

    this.port.onmessage = (e) => {
      if (e.data === "flush") {
        this.carryLen = 0;
        this.phase = 0;
        this.chunkFill = 0;
      }
    };
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0) return true;
    const channel = input[0];
    if (!channel || channel.length === 0) return true;

    // One pass for loudness; the UI and the barge-in logic both need it.
    let sum = 0;
    for (let i = 0; i < channel.length; i++) {
      const v = channel[i];
      sum += v * v;
    }
    const rms = Math.sqrt(sum / channel.length);

    if (this.passthrough) {
      // Device is already at 16 kHz: push floats straight through.
      for (let i = 0; i < channel.length; i++) this.emit(channel[i], rms);
      return true;
    }

    // Append to the carry ring, then decode with linear interpolation.
    for (let i = 0; i < channel.length; i++) {
      const slot = this.carryLen & CARRY_MASK;
      this.carry[slot] = channel[i];
      this.carryLen++;
    }

    // Sample positions are relative to the carry ring's start.
    let pos = this.phase;
    while (pos + 1 < this.carryLen) {
      const i0 = pos | 0;
      const frac = pos - i0;
      const a = this.carry[i0 & CARRY_MASK];
      const b = this.carry[(i0 + 1) & CARRY_MASK];
      this.emit(a + (b - a) * frac, rms);
      pos += this.ratio;
    }

    // Slide the consumed samples out of the ring.
    const consumed = Math.min(pos | 0, this.carryLen);
    if (consumed > 0) {
      const remaining = this.carryLen - consumed;
      for (let i = 0; i < remaining; i++) {
        this.carry[i & CARRY_MASK] = this.carry[(consumed + i) & CARRY_MASK];
      }
      this.carryLen = remaining;
      this.phase = pos - consumed;
    } else {
      this.phase = pos;
    }

    // Pathological guard: if the ring ever fills (it should not), reset rather
    // than overwrite unread audio.
    if (this.carryLen >= CARRY_CAPACITY) {
      this.carryLen = 0;
      this.phase = 0;
    }
    return true;
  }

  /** Accumulate one 16 kHz sample; post the chunk when it is full. */
  emit(floatSample, rms) {
    const clipped = floatSample > 1 ? 1 : floatSample < -1 ? -1 : floatSample;
    this.chunk[this.chunkFill++] = clipped < 0 ? clipped * 0x8000 : clipped * 0x7fff;

    if (this.chunkFill < this.chunkSamples) return;

    // Transfer the finished buffer and start a fresh one: zero-copy across the
    // port boundary, and the allocation happens once per 40 ms, not per sample.
    const out = new Int16Array(this.chunk);
    this.port.postMessage({ buffer: out.buffer, rms }, [out.buffer]);
    this.chunkFill = 0;
  }
}

registerProcessor("pcm16-downsampler", Pcm16Downsampler);
