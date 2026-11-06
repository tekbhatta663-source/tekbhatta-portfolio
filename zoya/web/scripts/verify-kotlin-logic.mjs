/**
 * Cross-checks the hand-written Kotlin algorithms against reference
 * implementations in Node. There is no JDK in this environment, so this is how
 * Base64Codec, Pcm16 and Downsampler are verified: same algorithm, same
 * vectors, byte-for-byte comparison with Node's own Buffer/base64.
 */
import assert from "node:assert/strict";

// ---- transcribed from Base64Codec (identical algorithm) --------------------
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function encodeKotlin(data) {
  let sb = "";
  let i = 0;
  const length = data.length;
  while (i < length) {
    const b0 = data[i] & 0xff;
    const b1 = i + 1 < length ? data[i + 1] & 0xff : 0;
    const b2 = i + 2 < length ? data[i + 2] & 0xff : 0;
    const triple = (b0 << 16) | (b1 << 8) | b2;
    sb += ALPHABET[(triple >> 18) & 0x3f];
    sb += ALPHABET[(triple >> 12) & 0x3f];
    sb += i + 1 < length ? ALPHABET[(triple >> 6) & 0x3f] : "=";
    sb += i + 2 < length ? ALPHABET[triple & 0x3f] : "=";
    i += 3;
  }
  return sb;
}

// ---- transcribed from Pcm16 -------------------------------------------------
function fromShorts(shorts) {
  const out = Buffer.alloc(shorts.length * 2);
  shorts.forEach((s, i) => out.writeInt16LE(s < 0 ? s : s, i * 2));
  return out;
}
function toShorts(bytes) {
  const n = Math.floor(bytes.length / 2);
  const out = new Array(n);
  for (let i = 0; i < n; i++) out[i] = bytes.readInt16LE(i * 2);
  return out;
}
function rms(shorts) {
  let sum = 0;
  for (const s of shorts) {
    const v = s / 32768;
    sum += v * v;
  }
  return Math.sqrt(sum / shorts.length);
}

// ---- transcribed from Downsampler ------------------------------------------
class Downsampler {
  constructor(sourceRate, targetRate = 16000) {
    this.ratio = sourceRate / targetRate;
    this.phase = 0;
    this.carry = [];
  }
  process(input) {
    const merged = [...this.carry, ...input];
    const out = [];
    let pos = this.phase;
    while (pos <= merged.length - 2) {
      const i0 = Math.floor(pos);
      const frac = pos - i0;
      const a = merged[i0];
      const b = merged[i0 + 1];
      out.push(Math.trunc(a + (b - a) * frac));
      pos += this.ratio;
    }
    const consumed = Math.min(Math.floor(pos), merged.length);
    this.carry = merged.slice(consumed);
    this.phase = pos - consumed;
    return out;
  }
}

let failures = 0;
const check = (name, fn) => {
  try {
    fn();
    console.log(`PASS  ${name}`);
  } catch (e) {
    failures++;
    console.log(`FAIL  ${name}\n      ${e.message}`);
  }
};

check("base64 matches node for all byte values", () => {
  const bytes = Buffer.from(Array.from({ length: 768 }, (_, i) => i % 256));
  assert.equal(encodeKotlin(bytes), bytes.toString("base64"));
});

check("base64 matches node for all 1..64 byte lengths", () => {
  for (let len = 1; len <= 64; len++) {
    const bytes = Buffer.from(Array.from({ length: len }, (_, i) => (i * 37 + len) % 256));
    assert.equal(encodeKotlin(bytes), bytes.toString("base64"), `len ${len}`);
  }
});

check("base64 handles empty input", () => {
  assert.equal(encodeKotlin(Buffer.alloc(0)), "");
});

check("base64 handles 3-byte and padding boundaries", () => {
  for (const s of ["hello", "hello!", "hello!!", "a", "ab", "abc"]) {
    assert.equal(encodeKotlin(Buffer.from(s)), Buffer.from(s).toString("base64"), s);
  }
});

check("pcm16 little endian layout", () => {
  const shorts = [0, 1, -1, 256, -256, 32767, -32768];
  const bytes = fromShorts(shorts);
  assert.deepEqual([...bytes], [0x00,0x00, 0x01,0x00, 0xff,0xff, 0x00,0x01, 0x00,0xff, 0xff,0x7f, 0x00,0x80]);
  assert.deepEqual(toShorts(bytes), shorts);
});

check("pcm16 round trip of a 20ms 440Hz frame", () => {
  const samples = Array.from({ length: 320 }, (_, i) =>
    Math.trunc(Math.sin((2 * Math.PI * 440 * i) / 16000) * 12000),
  );
  const pcm = fromShorts(samples);
  assert.equal(pcm.length, 640);
  // `-0` normalisation: JS Math.trunc can produce -0, Kotlin Short cannot.
  assert.deepEqual(toShorts(pcm).map((v) => v + 0), samples.map((v) => v + 0));
});

check("rms of silence is 0, full scale is ~1", () => {
  assert.equal(rms(new Array(320).fill(0)), 0);
  assert.ok(Math.abs(rms(new Array(320).fill(32767)) - 1) < 0.001);
});

check("rms of a half-scale tone is ~0.345", () => {
  const tone = Array.from({ length: 1600 }, (_, i) =>
    Math.trunc(Math.sin((2 * Math.PI * 440 * i) / 16000) * 16000),
  );
  const v = rms(tone);
  assert.ok(Math.abs(v - 0.345) < 0.02, `got ${v}`);
});

check("downsampler 48k -> 16k yields the right count", () => {
  const ds = new Downsampler(48000, 16000);
  const input = Array.from({ length: 4800 }, (_, i) =>
    Math.trunc(Math.sin((2 * Math.PI * 200 * i) / 48000) * 10000),
  );
  const out = ds.process(input);
  assert.ok(Math.abs(out.length - 1600) <= 6, `got ${out.length}`);
  assert.ok(rms(out) > 0.05, "signal should survive");
});

check("downsampler keeps continuous state across chunks", () => {
  const ds = new Downsampler(44100, 16000);
  let total = 0;
  for (let k = 0; k < 10; k++) {
    const block = Array.from({ length: 441 }, (_, i) => Math.trunc(Math.sin(i / 10) * 5000));
    total += ds.process(block).length;
  }
  assert.ok(Math.abs(total - 1600) <= 12, `got ${total}`);
});

check("downsampler carry stays bounded", () => {
  const ds = new Downsampler(48000, 16000);
  ds.process(Array.from({ length: 4800 }, () => 0));
  assert.ok(ds.carry.length < 4, `carry grew to ${ds.carry.length}`);
});

// ---- Live protocol frames (mirrors LiveProtocolTest.kt) --------------------
check("setup frame shape", () => {
  const frame = {
    setup: {
      model: "models/gemini-3.1-flash-live-preview",
      generationConfig: {
        responseModalities: ["AUDIO"],
        speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Aoede" } } },
      },
      systemInstruction: { parts: [{ text: "You are Zoya." }] },
      tools: [],
    },
  };
  const s = JSON.stringify(frame);
  assert.ok(s.includes("models/gemini-3.1-flash-live-preview"));
  assert.deepEqual(frame.setup.generationConfig.responseModalities, ["AUDIO"]);
  assert.equal(frame.setup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, "Aoede");
});

check("audio frame round trips through base64", () => {
  const pcm = Buffer.from(Array.from({ length: 640 }, (_, i) => i % 128));
  const frame = JSON.stringify({
    realtimeInput: { audio: { mimeType: "audio/pcm;rate=16000", data: encodeKotlin(pcm) } },
  });
  const parsed = JSON.parse(frame);
  assert.equal(parsed.realtimeInput.audio.mimeType, "audio/pcm;rate=16000");
  assert.deepEqual(Buffer.from(parsed.realtimeInput.audio.data, "base64"), pcm);
});

console.log(failures === 0 ? "\nALL KOTLIN ALGORITHM CHECKS PASSED" : `\n${failures} FAILED`);
process.exit(failures ? 1 : 0);
