package ai.zoya.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The wake word is the one feature that must not fire on its own. These tests
 * pin the two properties that matter: it fires on a "ZO-ya" envelope, and it
 * stays quiet for silence, steady noise, and steady speech.
 */
class WakeWordDetectorTest {

    private val rate = 16_000
    private val frame = 320 // 20 ms

    /** Builds one 20 ms frame at [amplitude] (0..1) and [hz]. */
    private fun tone(hz: Double, amplitude: Double, seed: Int = 0): ShortArray {
        val rnd = Random(seed)
        return ShortArray(frame) { i ->
            val carrier = sin(2 * PI * hz * i / rate)
            // A little noise keeps the zero-crossing rate realistic.
            val noise = (rnd.nextDouble() - 0.5) * 0.01
            ((carrier * amplitude + noise) * 32_767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun silence(): ShortArray = ShortArray(frame) { (Random(it).nextDouble() - 0.5).let { n -> (n * 200).toInt().toShort() } }

    /** A "ZO-ya"-shaped utterance: loud onset, dip, softer tail. */
    private fun speakZoya(detector: WakeWordDetector): Boolean {
        var fired = false
        // ZO: loud voiced
        repeat(7) { fired = detector.feed(tone(180.0, 0.55 + Random(it).nextDouble() * 0.1, it)) || fired }
        // boundary dip
        repeat(4) { fired = detector.feed(tone(180.0, 0.10, it + 10)) || fired }
        // ya: softer voiced
        repeat(6) { fired = detector.feed(tone(210.0, 0.32, it + 20)) || fired }
        // trailing silence
        repeat(4) { fired = detector.feed(silence()) || fired }
        return fired
    }

    @Test
    fun `fires on a zoya shaped envelope`() {
        val detector = WakeWordDetector()
        assertTrue(speakZoya(detector))
    }

    @Test
    fun `does not fire on silence`() {
        val detector = WakeWordDetector()
        var fired = false
        repeat(200) { fired = detector.feed(silence()) || fired }
        assertFalse(fired)
    }

    @Test
    fun `does not fire on steady speech`() {
        val detector = WakeWordDetector()
        var fired = false
        // Two seconds of continuous talking: loud, then louder, never dipping.
        repeat(100) { i ->
            val amp = 0.4 + 0.05 * sin(i / 6.0)
            fired = detector.feed(tone(150.0, amp, i)) || fired
        }
        assertFalse("a monologue must not be mistaken for her name", fired)
    }

    @Test
    fun `does not fire on a door slam`() {
        val detector = WakeWordDetector()
        var fired = false
        // One huge broadband frame then nothing — impulse noise.
        repeat(6) {
            fired = detector.feed(tone(900.0, 0.95, it)) || fired
        }
        repeat(40) { fired = detector.feed(silence()) || fired }
        assertFalse(fired)
    }

    @Test
    fun `cooldown blocks a second trigger immediately after the first`() {
        val detector = WakeWordDetector()
        detector.cooldownMs = 2_000
        assertTrue(speakZoya(detector))
        // Same word again right away must not re-fire.
        assertFalse(speakZoya(detector))
    }

    @Test
    fun `reset clears state so the next utterance works`() {
        val detector = WakeWordDetector()
        detector.cooldownMs = 2_000
        assertTrue(speakZoya(detector))
        detector.reset()
        detector.cooldownMs = 0
        assertTrue(speakZoya(detector))
    }

    @Test
    fun `short frames are ignored instead of crashing`() {
        val detector = WakeWordDetector()
        assertFalse(detector.feed(ShortArray(4)))
        assertFalse(detector.feed(ShortArray(0)))
    }
}
