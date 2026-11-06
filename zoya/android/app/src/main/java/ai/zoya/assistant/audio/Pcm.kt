package ai.zoya.assistant.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * PcmRingBuffer
 * -------------
 * A bounded, single-producer/single-consumer ring of 16-bit PCM frames.
 *
 * The mic thread writes, the websocket thread reads. Nothing is allocated per
 * frame, and back-pressure is explicit: when the socket stalls we drop the
 * oldest audio rather than growing without bound or blocking the recorder
 * (blocking the recorder is how you get an ANR and a dead microphone).
 */
class PcmRingBuffer(capacityShorts: Int) {

    private val buffer = ShortArray(capacityShorts)
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)
    private val dropped = AtomicInteger(0)

    /** Shorts currently readable. */
    val available: Int
        get() {
            val w = writeIndex.get()
            val r = readIndex.get()
            return if (w >= r) w - r else capacityShorts() - r + w
        }

    private fun capacityShorts() = buffer.size

    /**
     * Copy [count] samples starting at [offset] into the ring.
     * Returns false only if [count] exceeds total capacity.
     */
    fun write(src: ShortArray, offset: Int, count: Int): Boolean {
        if (count <= 0) return true
        if (count > buffer.size) return false
        val w = writeIndex.get()
        var idx = w
        for (i in 0 until count) {
            buffer[idx] = src[offset + i]
            idx++
            if (idx == buffer.size) idx = 0
        }
        writeIndex.set(idx)
        // Overflow policy: drop the oldest audio so latency never balloons.
        val r = readIndex.get()
        val free = if (w >= r) buffer.size - (w - r) else r - w
        if (count > free) {
            dropped.addAndGet(count - free)
            readIndex.set(idx)
        }
        return true
    }

    /** Copy up to [max] samples out. Returns how many were written to [dst]. */
    fun read(dst: ShortArray, max: Int, offset: Int = 0): Int {
        val n = min(max, available)
        if (n <= 0) return 0
        var r = readIndex.get()
        for (i in 0 until n) {
            dst[offset + i] = buffer[r]
            r++
            if (r == buffer.size) r = 0
        }
        readIndex.set(r)
        return n
    }

    fun clear() {
        readIndex.set(writeIndex.get())
    }

    fun takeDropped(): Int = dropped.getAndSet(0)
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
        var o = 0
        for (i in offset until offset + count) {
            val s = src[i].toInt()
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return out
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

/** Fixed-size 20 ms chunker so every socket frame has the same shape. */
class PcmChunker(private val samplesPerChunk: Int = 320) {
    private val pending = ArrayBlockingQueue<ShortArray>(256)
    private var acc = ShortArray(samplesPerChunk * 2)
    private var filled = 0

    fun push(samples: ShortArray, length: Int = samples.size) {
        var read = 0
        while (read < length) {
            val take = min(samplesPerChunk - filled, length - read)
            if (acc.size < filled + take) acc = acc.copyOf(max(acc.size * 2, filled + take))
            samples.copyInto(acc, filled, read, read + take)
            filled += take
            read += take
            if (filled == samplesPerChunk) {
                pending.offer(acc.copyOf(samplesPerChunk))
                filled = 0
            }
        }
    }

    fun poll(timeoutMs: Long = 0): ShortArray? =
        if (timeoutMs > 0) {
            pending.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            pending.poll()
        }

    fun clear() {
        pending.clear()
        filled = 0
    }
}
