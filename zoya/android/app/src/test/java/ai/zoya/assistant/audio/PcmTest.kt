package ai.zoya.assistant.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure-JVM tests for the audio plumbing. No emulator needed: these are the
 * pieces where a subtle bug produces static instead of speech, so they get
 * real coverage rather than a comment.
 */
class PcmTest {

    @Test
    fun `base64 round trip preserves every byte value`() {
        val bytes = ByteArray(768) { (it % 256).toByte() }
        val encoded = Base64Codec.encode(bytes)
        val decoded = Base64Codec.decode(encoded)
        assertArrayEquals(bytes, decoded)
    }

    @Test
    fun `base64 encoding matches the RFC for padding cases`() {
        assertEquals("aGVsbG8=", Base64Codec.encode("hello".toByteArray()))
        assertEquals("aGVsbG8h", Base64Codec.encode("hello!".toByteArray()))
        assertEquals("aGVsbG8hIQ==", Base64Codec.encode("hello!!".toByteArray()))
        assertEquals("", Base64Codec.encode(ByteArray(0)))
    }

    @Test
    fun `base64 decodes the padding cases back`() {
        assertEquals("hello", String(Base64Codec.decode("aGVsbG8=")))
        assertEquals("hello!", String(Base64Codec.decode("aGVsbG8h")))
        assertEquals("hello!!", String(Base64Codec.decode("aGVsbG8hIQ==")))
    }

    @Test
    fun `base64 survives a 20ms frame at 16k`() {
        // 320 samples of a 440 Hz tone, exactly what the mic ships.
        val samples = ShortArray(320) { i ->
            (sin(2 * Math.PI * 440 * i / 16_000.0) * 12_000).toInt().toShort()
        }
        val pcm = Pcm16.fromShorts(samples)
        assertEquals(640, pcm.size)
        val back = Pcm16.toShorts(Base64Codec.decode(Base64Codec.encode(pcm)))
        assertArrayEquals(samples, back)
    }

    @Test
    fun `pcm16 little endian layout is correct`() {
        val samples = shortArrayOf(0, 1, -1, 256, -256, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = Pcm16.fromShorts(samples)
        // 1 -> 0x01 0x00 ; -1 -> 0xFF 0xFF ; 256 -> 0x00 0x01
        assertEquals(0x01, bytes[2].toInt())
        assertEquals(0x00, bytes[3].toInt())
        assertEquals(0xFF, bytes[4].toInt() and 0xFF)
        assertEquals(0xFF, bytes[5].toInt() and 0xFF)
        assertEquals(0x00, bytes[6].toInt())
        assertEquals(0x01, bytes[7].toInt())
        assertArrayEquals(samples, Pcm16.toShorts(bytes))
    }

    @Test
    fun `rms of silence is zero and rms of full scale is about one`() {
        assertEquals(0f, Pcm16.rms(ShortArray(320)), 1e-6f)
        val full = ShortArray(320) { Short.MAX_VALUE }
        assertEquals(1f, Pcm16.rms(full), 0.01f)
    }

    @Test
    fun `rms is roughly half amplitude for a half-scale tone`() {
        val tone = ShortArray(1600) { i ->
            (sin(2 * Math.PI * 440 * i / 16_000.0) * 16_000).toInt().toShort()
        }
        val rms = Pcm16.rms(tone)
        // 16000 / 32768 / sqrt(2) ~= 0.345
        assertTrue("rms was $rms", abs(rms - 0.345f) < 0.02f)
    }

    @Test
    fun `ring buffer preserves order across a wrap`() {
        val ring = PcmRingBuffer(10)
        val write = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertTrue(ring.write(write, 0, write.size))
        val out = ShortArray(8)
        assertEquals(8, ring.read(out, 8))
        assertArrayEquals(write, out)

        // Write past the end so the index wraps.
        assertTrue(ring.write(shortArrayOf(9, 10, 11, 12), 0, 4))
        val out2 = ShortArray(4)
        assertEquals(4, ring.read(out2, 4))
        assertArrayEquals(shortArrayOf(9, 10, 11, 12), out2)
    }

    @Test
    fun `ring buffer drops the oldest audio when full`() {
        val ring = PcmRingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4), 0, 4)
        ring.write(shortArrayOf(5, 6), 0, 2)
        assertEquals(2, ring.takeDropped())
        val out = ShortArray(4)
        assertEquals(4, ring.read(out, 4))
        // The two oldest samples were sacrificed, not the newest.
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), out)
    }

    @Test
    fun `downsampler halves the sample count and keeps the tone`() {
        val srcRate = 48_000
        val ds = Downsampler(srcRate, 16_000)
        val input = ShortArray(4_800) { i ->
            (sin(2 * Math.PI * 200 * i / srcRate) * 10_000).toInt().toShort()
        }
        val out = ds.process(input, input.size)
        // 4800 samples at 48k = 100 ms -> 1600 samples at 16k.
        assertEquals(1_600L, out.size.toLong(), 6L)
        assertTrue("output should still be audible", Pcm16.rms(out) > 0.05f)
    }

    @Test
    fun `downsampler keeps no more than one sample of state between calls`() {
        val ds = Downsampler(44_100, 16_000)
        var total = 0
        repeat(10) {
            val block = ShortArray(441).also { b ->
                for (i in b.indices) b[i] = (sin(i / 10.0) * 5000).toInt().toShort()
            }
            total += ds.process(block, block.size).size
        }
        // 4410 samples at 44.1k = 100 ms -> about 1600 at 16k.
        assertEquals(1_600L, total.toLong(), 12L)
    }

    @Test
    fun `chunker emits fixed 320 sample frames`() {
        val chunker = PcmChunker(320)
        chunker.push(ShortArray(700) { it.toShort() }, 700)
        val first = chunker.poll()!!
        val second = chunker.poll()!!
        assertEquals(320, first.size)
        assertEquals(320, second.size)
        assertEquals(null, chunker.poll())
        assertEquals(0.toShort(), first[0])
        assertEquals(320.toShort(), second[0])
    }

    @Test
    fun `chunker ignores nothing when input is not a multiple of the frame size`() {
        val chunker = PcmChunker(320)
        chunker.push(ShortArray(1000), 1000)
        var total = 0
        while (true) {
            val chunk = chunker.poll() ?: break
            total += chunk.size
        }
        assertEquals(960, total) // 3 full frames, 40 samples still buffered
    }
}
