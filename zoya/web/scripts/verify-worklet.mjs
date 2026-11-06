/**
 * Runs public/pcm-worklet.js outside the browser against a fake
 * AudioWorkletProcessor, so the resampler and chunker are verified without a
 * mic or an audio device.
 *
 *   node scripts/verify-worklet.mjs
 */
import { readFileSync } from "node:fs";

const source = readFileSync(new URL("../public/pcm-worklet.js", import.meta.url), "utf8")
  .replace('registerProcessor("pcm16-downsampler", Pcm16Downsampler);', "")
  .replace(
    "class Pcm16Downsampler extends AudioWorkletProcessor {",
    "class Pcm16Downsampler extends AudioWorkletProcessor {",
  );

/**
 * Stand-in for the real AudioWorkletProcessor. `super()` inside the worklet is
 * what normally creates `this.port`, so this class must do exactly that.
 */
class FakeProcessor {
  constructor() {
    const port = { posted: [], onmessage: null };
    port.postMessage = (msg) => port.posted.push(msg);
    this.port = port;
  }
}

/** Build a processor for a given device sample rate. */
function makeProcessor(deviceRate, targetRate = 16000) {
  const factory = new Function(
    "AudioWorkletProcessor",
    "sampleRate",
    `${source}; return Pcm16Downsampler;`,
  );
  const Cls = factory(FakeProcessor, deviceRate);
  return new Cls({ processorOptions: { targetRate } });
}

let failures = 0;
const check = (name, cond, extra = "") => {
  console.log(`${cond ? "PASS" : "FAIL"}  ${name}${cond ? "" : "  " + extra}`);
  if (!cond) failures++;
};

function feed(proc, seconds, deviceRate, hz = 1000, amp = 0.5, quantum = 128) {
  const quanta = Math.round((seconds * deviceRate) / quantum);
  for (let q = 0; q < quanta; q++) {
    const frame = new Float32Array(quantum);
    for (let i = 0; i < quantum; i++) {
      frame[i] = Math.sin((2 * Math.PI * hz * (q * quantum + i)) / deviceRate) * amp;
    }
    proc.process([[frame]]);
  }
}

const samplesIn = (proc) =>
  proc.port.posted.reduce((n, c) => n + c.buffer.byteLength / 2, 0);

// --- 48 kHz device: real resampling path --------------------------------
{
  const proc = makeProcessor(48000);
  check("48k is not passthrough", proc.passthrough === false);
  feed(proc, 1.0, 48000);
  const got = samplesIn(proc);
  check(
    "1 s at 48 kHz produces ~16000 samples at 16 kHz",
    Math.abs(got - 16000) <= 64,
    `got ${got}`,
  );
  check("chunks are 40 ms (640 samples)", proc.port.posted[0].buffer.byteLength === 1280,
    `${proc.port.posted[0].buffer.byteLength}`);
  check("25 chunks per second", Math.abs(proc.port.posted.length - 25) <= 1,
    `${proc.port.posted.length}`);
  check("rms is reported and sane", proc.port.posted[5].rms > 0.2 && proc.port.posted[5].rms < 0.5,
    `${proc.port.posted[5].rms}`);
}

// --- 44.1 kHz device: fractional ratio ---------------------------------
{
  const proc = makeProcessor(44100);
  feed(proc, 1.0, 44100);
  const got = samplesIn(proc);
  check("1 s at 44.1 kHz produces ~16000 samples", Math.abs(got - 16000) <= 64, `got ${got}`);
}

// --- already 16 kHz: the fast path -------------------------------------
{
  const proc = makeProcessor(16000);
  check("16k is passthrough", proc.passthrough === true);
  let total = 0;
  for (let q = 0; q < Math.round(16000 / 128); q++) {
    const frame = new Float32Array(128);
    for (let i = 0; i < 128; i++) frame[i] = Math.sin((2 * Math.PI * 440 * (q * 128 + i)) / 16000) * 0.5;
    proc.process([[frame]]);
    total += 128;
  }
  check("passthrough preserves sample count", samplesIn(proc) === total, `${samplesIn(proc)} vs ${total}`);
}

// --- carry-over between process() calls --------------------------------
{
  const proc = makeProcessor(48000);
  // 100 tiny quanta of 1 sample each: worst case for the carry ring.
  let n = 0;
  for (let i = 0; i < 48000; i++) {
    proc.process([[Float32Array.of(0.25)]]);
    n++;
  }
  const got = samplesIn(proc);
  check("carry survives 1-sample quanta", Math.abs(got - 16000) <= 64, `got ${got} from ${n} calls`);
  check("carry ring stays bounded", proc.carryLen < 256, `${proc.carryLen}`);
}

// --- clipping and flush ------------------------------------------------
{
  const proc = makeProcessor(16000);
  proc.process([[Float32Array.of(4.0, -4.0, 0)]]);
  proc.port.posted.length = 0;
  proc.port.onmessage({ data: "flush" });
  check("flush clears accumulator", proc.chunkFill === 0 && proc.carryLen === 0);

  // Over-range input must clamp, not wrap around into a loud click.
  const proc2 = makeProcessor(16000);
  const frame = new Float32Array(640).fill(2.0);
  proc2.process([[frame]]);
  const bytes = new Int16Array(proc2.port.posted[0].buffer);
  check("input is clamped to full scale", bytes[0] === 32767, `${bytes[0]}`);
}

// --- silence / empty input --------------------------------------------
{
  const proc = makeProcessor(48000);
  proc.process([[]]);
  proc.process([]);
  proc.process([[new Float32Array(0)]]);
  check("empty inputs are ignored safely", proc.port.posted.length === 0);
}

console.log(failures === 0 ? "\nALL WORKLET CHECKS PASSED" : `\n${failures} FAILED`);
process.exit(failures ? 1 : 0);
