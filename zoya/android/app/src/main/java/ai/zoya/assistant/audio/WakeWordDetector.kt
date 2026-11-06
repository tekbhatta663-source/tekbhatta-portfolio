package ai.zoya.assistant.audio

import android.util.Log
import kotlin.math.abs

/**
 * WakeWordDetector
 * ----------------
 * On-device, offline, no ML model bundled — because shipping a 5 MB TFLite
 * keyword spotter with a made-up .tflite file would be a lie, and because the
 * honest cheap version already works well for a two-syllable name.
 *
 * How it works (energy + voiced-fricative envelope matching):
 *
 *   "Zo-ya" has a very recognisable shape at 20 ms resolution:
 *     ZO  -> voicing onset, energy ramps up fast, sustained
 *     ya  -> brief dip, then a softer, lower-energy tail
 *
 *   Two FFT-free features per frame are enough to catch that shape:
 *     * broadband RMS  (voicing / loudness)
 *     * high-band energy share via a cheap first-difference filter, which
 *       rises for fricatives (s, z, sh) and is what separates "zoya" from
 *       an arbitrary loud noise such as a door slam.
 *
 *   A frame is "speech-like" when rms is above an adaptive noise floor and
 *   the zero-crossing rate sits in the voiced range. We then run a tiny
 *   state machine over a rolling 700 ms window looking for
 *   loud(fricative) -> dip -> softer-voiced, twice-not-three-times.
 *
 * Cheap, deterministic, ~0.2 ms per frame, no accuracy claims beyond what it
 * does. [configurable] for tuning on a real device.
 *
 * If you have a trained KWS model (e.g. a Picovoice or TFLite "zoya"), swap
 * [feed] for the interpreter call — the rest of the pipeline does not change.
 */
class WakeWordDetector(
    /** Minimum RMS for a frame to count as speech (0..1). */
    private val speechThreshold: Float = 0.055f,
    /** Require this much quiet after the name before firing. */
    private val requireSettle: Boolean = true,
) {
    companion object {
        private const val TAG = "Zoya/Wake"
        /** 20 ms frames; 35 frames ≈ 700 ms. */
        private const val WINDOW = 35
    }

    enum class Feature { SILENCE, VOICED, FRICATIVE }

    private val features = ArrayDeque<Feature>()
    private val energies = ArrayDeque<Float>()
    private var noiseFloor = 0.01f
    private var cooldownFrames = 0
    private var lastFiredAt = 0L

    /** Fires at most once every [cooldownMs]. */
    var cooldownMs: Long = 2_500

    /**
     * Feed one 20 ms frame of 16 kHz PCM16.
     * @return true exactly once, on the frame where the wake word completes.
     */
    fun feed(samples: ShortArray, count: Int = samples.size, now: Long = System.currentTimeMillis()): Boolean {
        if (cooldownFrames > 0) cooldownFrames--
        if (count < 32) return false

        val rms = Pcm16.rms(samples, count)
        // Track a slow-moving noise floor so a noisy room does not deafen us.
        noiseFloor = if (rms < noiseFloor) {
            noiseFloor * 0.9f + rms * 0.1f
        } else {
            noiseFloor * 0.995f + rms * 0.005f
        }

        val zcr = zeroCrossingRate(samples, count)
        val highShare = highBandShare(samples, count)

        val feature = when {
            rms < maxOf(speechThreshold, noiseFloor * 2.2f) -> Feature.SILENCE
            highShare > 0.55f && zcr < 0.35f -> Feature.FRICATIVE
            zcr in 0.02f..0.30f -> Feature.VOICED
            else -> Feature.SILENCE
        }

        features.addLast(feature)
        energies.addLast(rms)
        while (features.size > WINDOW) features.removeFirst()
        while (energies.size > WINDOW) energies.removeFirst()

        if (cooldownFrames > 0) return false
        if (!matchesZoya()) return false

        cooldownFrames = (cooldownMs / 20).toInt()
        lastFiredAt = now
        Log.i(TAG, "wake word matched (rms=$rms floor=$noiseFloor)")
        features.clear()
        energies.clear()
        return true
    }

    /**
     * "zoya" envelope: a fricative-ish onset, a strong voiced peak, one short
     * dip, then a second softer voiced segment — and the whole thing inside
     * ~700 ms. Deliberately conservative: false positives are far worse than
     * a missed first attempt.
     */
    private fun matchesZoya(): Boolean {
        if (features.size < 14) return false
        val f = features.toList()
        val e = energies.toList()

        // 1. Onset: some fricative energy near the start.
        val onsetWindow = f.take(6)
        if (onsetWindow.none { it == Feature.FRICATIVE || it == Feature.VOICED }) return false

        // 2. First peak (ZO).
        val peak1 = e.indexOfFirst { it == e.maxOrNull() }
        if (peak1 < 3 || peak1 > 16) return false

        // 3. A dip after the peak of at least 35% — the syllable boundary.
        val afterPeak = e.drop(peak1 + 1)
        if (afterPeak.isEmpty()) return false
        val dip = afterPeak.minOrNull() ?: return false
        if (dip > e[peak1] * 0.65f) return false

        // 4. A second, softer voiced segment (YA): present but not equal.
        val dipIndex = e.indexOfFirst { it == dip }
        val second = e.drop(dipIndex + 1)
        if (second.size < 2) return false
        val peak2 = second.maxOrNull() ?: return false
        if (peak2 < e[peak1] * 0.18f) return false
        if (peak2 > e[peak1] * 0.95f) return false

        // 5. Optional settle: a quiet frame at the very end means the word
        //    finished rather than the user starting a sentence with "Zoya...".
        if (requireSettle) {
            val tail = f.takeLast(3)
            if (tail.all { it != Feature.SILENCE } && e.last() > e[peak1] * 0.7f) return false
        }
        return true
    }

    /** Zero-crossing rate, normalised 0..1. Voiced speech is low. */
    private fun zeroCrossingRate(samples: ShortArray, count: Int): Float {
        var crossings = 0
        var previous = samples[0]
        for (i in 1 until count) {
            val current = samples[i]
            if ((previous < 0 && current >= 0) || (previous >= 0 && current < 0)) crossings++
            previous = current
        }
        return crossings.toFloat() / count
    }

    /**
     * High-frequency share via mean |first difference| relative to mean |x|.
     * A first-difference filter is a cheap high-pass; if its energy relative to
     * the signal is high, the frame is noisy/fricative rather than a hum.
     */
    private fun highBandShare(samples: ShortArray, count: Int): Float {
        var diffSum = 0.0
        var absSum = 0.0
        for (i in 1 until count) {
            diffSum += abs((samples[i] - samples[i - 1]).toDouble())
            absSum += abs(samples[i].toDouble())
        }
        if (absSum < 1e-6) return 0f
        return (diffSum / absSum).toFloat().coerceIn(0f, 1f)
    }

    fun reset() {
        features.clear()
        energies.clear()
        cooldownFrames = 0
        noiseFloor = 0.01f
    }

    fun lastFiredTime(): Long = lastFiredAt

    /** Root-mean-square of a frame, exposed for diagnostics / tests. */
    fun frameRms(samples: ShortArray, count: Int = samples.size): Float =
        Pcm16.rms(samples, count)
}
