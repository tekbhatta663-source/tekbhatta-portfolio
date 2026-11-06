package ai.zoya.assistant.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * PcmRingBuffer
 * -------------
 * Bounded single-producer/single-consumer ring of 16-bit PCM samples.
 *
 * The mic thread writes; the socket thread reads. This is the only place where
 * PCM crosses a thread boundary in the Android client, so it is written to have
 * exactly these properties:
 *
 *  - **no allocation per frame** — the buffer is allocated once, and both
 *    [write] and [read] copy sample by sample into caller-owned arrays. No
 *    boxing, no ArrayBlockingQueue of ShortArrays, nothing for the GC to do
 *    while audio is flowing (a GC pause on the audio thread is an audible gap).
 *  - **drop-oldest back-pressure** — if the socket stalls, the *oldest* audio
 *    is discarded so latency can never balloon. Blocking the recorder instead
 *    would stall the mic and eventually ANR.
 *  - **lock-free single-producer / single-consumer** — plain volatile indices
 *    with acquire/release semantics, no mutex on the audio path.
 *
 * Capacity is rounded up internally, so any requested size works; the helper
 * `nextPowerOfTwoMinusOne` shows what a given request becomes. With the
 * AudioEngine's 4096-sample uplink that is 4095 samples of headroom (~256 ms at
 * 16 kHz), which is the whole latency budget for a live conversation.
 */
class PcmRingBuffer(capacitySamples: Int) {
    // Contract: you get *at least* [capacitySamples]. The real figure is
    // [capacity], which callers and tests should read rather than assuming.

    /**
     * Slots in the backing array: the next power of two at least one larger
     * than [capacitySamples], so index masking replaces modulo (a ~10x faster
     * wrap on ART) and a full ring is still distinguishable from an empty one.
     */
    private val slots: Int = Integer.highestOneBit((capacitySamples + 1).coerceAtLeast(2) - 1) * 2
    private val buffer = ShortArray(slots)
    private val mask = slots - 1

    /** Samples this ring can hold before it starts dropping. */
    val capacity: Int get() = mask

    @Volatile private var writeIndex = 0
    @Volatile private var readIndex = 0
    private val droppedSamples = AtomicInteger(0)

    /** Samples currently readable. */
    val available: Int
        get() = (writeIndex - readIndex) and mask

    /** Free space in samples. */
    val free: Int get() = capacity - available

    /** True when the producer has filled the ring. */
    val isFull: Boolean get() = available == capacity

    /**
     * Copy [count] samples from [src] into the ring.
     *
     * @return the number of samples actually stored; shorter than [count] only
     *   if [count] exceeds the capacity of the whole ring (a caller bug).
     */
    fun write(src: ShortArray, offset: Int = 0, count: Int = src.size): Int {
        if (count <= 0) return 0
        val toStore = min(count, capacity)
        // Make room first: advance the reader past whatever must go.
        val overflow = toStore - free
        if (overflow > 0) {
            readIndex = (readIndex + overflow) and mask
            droppedSamples.addAndGet(overflow)
        }
        var w = writeIndex
        for (i in 0 until toStore) {
            buffer[w] = src[offset + i]
            w = (w + 1) and mask
        }
        writeIndex = w
        return toStore
    }

    /** Copy the oldest [count] samples out without removing them. */
    fun peek(dst: ShortArray, count: Int, offset: Int = 0): Int {
        val n = min(count, available)
        var r = readIndex
        for (i in 0 until n) {
            dst[offset + i] = buffer[r]
            r = (r + 1) and mask
        }
        return n
    }

    /** Copy up to [count] samples out and consume them. */
    fun read(dst: ShortArray, count: Int, offset: Int = 0): Int {
        val n = min(count, available)
        var r = readIndex
        for (i in 0 until n) {
            dst[offset + i] = buffer[r]
            r = (r + 1) and mask
        }
        readIndex = r
        return n
    }

    fun clear() {
        readIndex = writeIndex
        droppedSamples.set(0)
    }

    /** Samples lost to overflow since the last call (for diagnostics). */
    fun takeDropped(): Int = droppedSamples.getAndSet(0)

    companion object {
        /** What [capacitySamples] rounds up to, for tests and sizing. */
        fun effectiveCapacity(capacitySamples: Int): Int =
            Integer.highestOneBit((capacitySamples + 1).coerceAtLeast(2) - 1) * 2 - 1
    }
}

/**
 * Base64 (RFC 4648, no line breaks) for the Live API's JSON frames.
 *
 * Hand-written because `android.util.Base64` pulls in a JNI class that is
 * awkward to unit test, and java.util.Base64 needs API 26+ — which we target,
 * but this keeps the audio path identical on every device and unit-testable.
 */
object Base64Codec {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
        table['='.code] = -2
    }

    fun encode(data: ByteArray, length: Int = data.size): String {
        val sb = StringBuilder(((length + 2) / 3) * 4)
        var i = 0
        while (i < length) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < length) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < length) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(ALPHABET[(triple shr 18) and 0x3F])
            sb.append(ALPHABET[(triple shr 12) and 0x3F])
            sb.append(if (i + 1 < length) ALPHABET[(triple shr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < length) ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.filter { it != '\n' && it != '\r' && it != ' ' }
        var pad = 0
        if (clean.endsWith("==")) pad = 2 else if (clean.endsWith("=")) pad = 1
        val out = ByteArray(clean.length / 4 * 3 - pad)
        var o = 0
        var i = 0
        while (i < clean.length) {
            val c0 = DECODE.getOrElse(clean[i].code) { -1 }
            val c1 = DECODE.getOrElse(clean[i + 1].code) { -1 }
            val c2 = DECODE.getOrElse(clean[i + 2].code) { -1 }
            val c3 = DECODE.getOrElse(clean[i + 3].code) { -1 }
            require(c0 >= 0 && c1 >= 0) { "Invalid base64" }
            val triple = (c0 shl 18) or (c1 shl 12) or
                ((if (c2 < 0) 0 else c2) shl 6) or (if (c3 < 0) 0 else c3)
            if (o < out.size) out[o++] = ((triple shr 16) and 0xFF).toByte()
            if (o < out.size) out[o++] = ((triple shr 8) and 0xFF).toByte()
            if (o < out.size) out[o++] = (triple and 0xFF).toByte()
            i += 4
        }
        return out
    }
}

/** Little-endian Int16 <-> ByteArray, the only sample format the API takes. */
object Pcm16 {
    fun fromShorts(src: ShortArray, count: Int = src.size, offset: Int = 0): ByteArray {
        val out = ByteArray(count * 2)
        writeInto(out, 0, src, count, offset)
        return out
    }

    /**
     * Encode [count] samples into an existing array, writing [count] * 2 bytes
     * starting at [dstOffset]. Allocation-free, so the capture loop can reuse a
     * single scratch buffer instead of creating a ByteArray 50 times a second.
     */
    fun writeInto(
        dst: ByteArray,
        dstOffset: Int,
        src: ShortArray,
        count: Int,
        srcOffset: Int = 0,
    ) {
        var o = dstOffset
        for (i in srcOffset until srcOffset + count) {
            val s = src[i].toInt()
            dst[o++] = (s and 0xFF).toByte()
            dst[o++] = ((s shr 8) and 0xFF).toByte()
        }
    }

    fun toShorts(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): ShortArray {
        val count = length / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = src[offset + i * 2].toInt() and 0xFF
            val hi = src[offset + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    /** RMS in 0..1, used for the orb and for local barge-in detection. */
    fun rms(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return kotlin.math.sqrt(sum / count).toFloat()
    }
}

/**
 * Linear resampler to 16 kHz capture.
 *
 * AudioRecord hands us 44.1/48 kHz on most phones. Doing this in Kotlin keeps
 * the mic path independent of MediaRecorder/Sampler quirks and keeps the
 * frames exactly 20 ms, which the Live API likes.
 */
class Downsampler(private val sourceRate: Int, private val targetRate: Int = 16_000) {
    private val ratio = sourceRate.toDouble() / targetRate
    private var phase = 0.0
    private var carry = ShortArray(0)

    fun process(input: ShortArray, length: Int): ShortArray {
        val merged = ShortArray(carry.size + length)
        carry.copyInto(merged)
        input.copyInto(merged, carry.size, 0, length)

        val outCapacity = ((merged.size - phase) / ratio).toInt() + 2
        val out = ShortArray(max(0, outCapacity))
        var o = 0
        var pos = phase
        while (pos <= merged.size - 2 && o < out.size) {
            val i0 = pos.toInt()
            val frac = pos - i0
            val a = merged[i0].toDouble()
            val b = merged[i0 + 1].toDouble()
            out[o++] = (a + (b - a) * frac).toInt().toShort()
            pos += ratio
        }
        val consumed = min(pos.toInt(), merged.size)
        carry = merged.copyOfRange(consumed, merged.size)
        phase = pos - consumed
        return if (o == out.size) out else out.copyOf(o)
    }

    fun reset() {
        carry = ShortArray(0)
        phase = 0.0
    }
}
