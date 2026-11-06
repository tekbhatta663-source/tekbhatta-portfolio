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
        val ring = PcmRingBuffer(8) // rounds to a 16-slot array, wraps constantly
        val write = shortArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(6, ring.write(write))
        val out = ShortArray(6)
        assertEquals(6, ring.read(out, 6))
        assertArrayEquals(write, out)

        // Write past the physical end of the array: indices must wrap.
        assertEquals(4, ring.write(shortArrayOf(9, 10, 11, 12)))
        val out2 = ShortArray(4)
        assertEquals(4, ring.read(out2, 4))
        assertArrayEquals(shortArrayOf(9, 10, 11, 12), out2)
    }

    @Test
    fun `ring buffer reports availability and fullness`() {
        val ring = PcmRingBuffer(8)
        // Capacity is rounded up, so read it rather than assuming the request.
        assertTrue(ring.capacity >= 8)
        assertEquals(0, ring.available)
        assertEquals(ring.capacity, ring.free)

        ring.write(shortArrayOf(1, 2, 3))
        assertEquals(3, ring.available)
        assertEquals(ring.capacity - 3, ring.free)

        ring.write(ShortArray(ring.capacity - 3) { 7 })
        assertEquals(ring.capacity, ring.available)
        assertTrue(ring.isFull)
        assertTrue(ring.free == 0)
    }

    @Test
    fun `ring buffer drops the oldest audio, never the newest`() {
        val ring = PcmRingBuffer(4)
        val cap = ring.capacity
        // Fill exactly, then push two extra samples.
        ring.write(ShortArray(cap) { (it + 1).toShort() })
        assertTrue(ring.isFull)
        ring.write(shortArrayOf(100, 101))
        assertEquals(2, ring.takeDropped())

        val out = ShortArray(cap)
        assertEquals(cap, ring.read(out, cap))
        // The two oldest values are gone; the two newest are present and last.
        assertEquals((3).toShort(), out[0])
        assertEquals(100.toShort(), out[cap - 2])
        assertEquals(101.toShort(), out[cap - 1])
    }

    @Test
    fun `ring buffer peek does not consume`() {
        val ring = PcmRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3, 4))
        val peeked = ShortArray(4)
        assertEquals(4, ring.peek(peeked, 4))
        assertEquals(4, ring.available)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), peeked)
        assertEquals(4, ring.read(peeked, 4))
        assertEquals(0, ring.available)
    }

    @Test
    fun `ring buffer read is bounded by what is available`() {
        val ring = PcmRingBuffer(8)
        ring.write(shortArrayOf(1, 2))
        val out = ShortArray(8)
        assertEquals(2, ring.read(out, 8))
        assertEquals(0, ring.read(out, 8))
    }

    @Test
    fun `ring buffer clear empties it without corrupting the writer`() {
        val ring = PcmRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3, 4, 5))
        ring.clear()
        assertEquals(0, ring.available)
        ring.write(shortArrayOf(6, 7))
        val out = ShortArray(2)
        assertEquals(2, ring.read(out, 2))
        assertArrayEquals(shortArrayOf(6, 7), out)
    }

    @Test
    fun `ring buffer survives a producer consumer loop without losing order`() {
        val ring = PcmRingBuffer(256)
        val expected = ArrayList<Short>()
        val out = ShortArray(40)
        var next = 0
        repeat(500) {
            // Producer writes less than the consumer takes, so it never fills.
            if (ring.free >= 16) {
                val chunk = ShortArray(16) { (next++).toShort() }
                assertTrue(ring.write(chunk) == 16)
                chunk.forEach { expected += it }
            }
            val got = ring.read(out, 40)
            for (i in 0 until got) {
                assertEquals(expected.removeAt(0), out[i])
            }
        }
        assertEquals(0, ring.takeDropped())
    }

    @Test
    fun `writeInto encodes into a reused buffer without reallocating`() {
        val scratch = ByteArray(8)
        val samples = shortArrayOf(0x0102, -1, 0x0304)
        Pcm16.writeInto(scratch, 0, samples, samples.size)
        assertArrayEquals(Pcm16.fromShorts(samples), scratch.copyOf(6))

        // Partial encode must only touch the bytes it owns.
        val guarded = ByteArray(8) { 0x7F }
        Pcm16.writeInto(guarded, 2, samples, 1)
        assertEquals(0x02, guarded[2].toInt())
        assertEquals(0x01, guarded[3].toInt())
        assertEquals(0x7F, guarded[4].toInt())
    }
}
