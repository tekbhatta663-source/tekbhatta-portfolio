package ai.zoya.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AudioEngine
 * -----------
 * Everything that touches a speaker or a microphone. Three concerns, three
 * separate places, so each can be reasoned about (and stopped) on its own:
 *
 *  - **Capture**: AudioRecord @ 16 kHz mono -> [PcmRingBuffer]. The recorder
 *    thread only ever writes to the ring; readers pull from it, so a stalled
 *    network can never block the microphone.
 *  - **Playback**: one AudioTrack @ 24 kHz with a bounded writer thread. The
 *    model's audio is queued, never played on the socket thread. flush()
 *    empties the queue in one call — that is barge-in.
 *  - **Levels**: mic RMS and playback RMS as StateFlows for the Compose orb.
 *
 * Thread model: one recorder thread, one player thread, both owned here. The
 * only things crossing a thread boundary are a lock-free ring (mic) and a
 * bounded queue (playback); neither allocates per frame.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "Zoya/Audio"
        const val CAPTURE_RATE = 16_000
        const val PLAYBACK_RATE = 24_000
        /** 20 ms at 16 kHz. */
        const val FRAME_SAMPLES = 320
        /** ~256 ms of microphone audio in flight (4096 - 1, power of two). */
        private const val UPLINK_SAMPLES = 4096
        private val PLAYBACK_BACKLOG_LIMIT_MS = 900L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _playbackLevel = MutableStateFlow(0f)
    val playbackLevel: StateFlow<Float> = _playbackLevel.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    /** True while queued model audio is still playing out. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ------------------------------------------------------------- capture --

    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    @Volatile private var capturing = false
    private val muted = AtomicBoolean(false)
    private var downsampler: Downsampler? = null

    /**
     * Mic -> socket hand-off. Written by the recorder thread only, read by
     * whatever is streaming (the session's mic pump). Deliberately NOT a
     * SharedFlow of byte arrays: at 50 frames/second that boxes a ShortArray,
     * copies it twice and hands the GC 50 short-lived objects per second. This
     * ring is allocated once and copied into one reusable output buffer.
     */
    private val uplink = PcmRingBuffer(UPLINK_SAMPLES)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Start capturing. Idempotent.
     * @param emitFrames false for a "sleep" capture that only feeds the wake
     *   word detector, so we do not ship audio to Gemini before she is woken.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(emitFrames: Boolean = true): Boolean {
        if (capturing) return true
        if (!hasMicPermission()) {
            _error.value = "Microphone permission is missing."
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            CAPTURE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            _error.value = "This device cannot record mono 16 kHz audio."
            return false
        }
        val bufferSize = max(minBuf, FRAME_SAMPLES * 2 * 8)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            _error.value = "Could not open the microphone: ${e.message}"
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _error.value = "Microphone is not available (in use by another app?)."
            return false
        }

        recorder = rec
        // 16 kHz in, 16 kHz out: this is a guard for devices that ignore the
        // requested rate, not a real conversion. Cheap when it is a no-op.
        downsampler = Downsampler(CAPTURE_RATE, CAPTURE_RATE)
        uplink.clear()
        capturing = true
        _error.value = null

        recordJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val readBuf = ShortArray(FRAME_SAMPLES)
            try {
                rec.startRecording()
                while (isActive && capturing) {
                    val n = rec.read(readBuf, 0, readBuf.size)
                    if (n <= 0) continue

                    val level = Pcm16.rms(readBuf, n)
                    _micLevel.value = smooth(_micLevel.value, level)

                    // While muted (local barge-in / user mute) we keep the ring
                    // empty rather than filling it with audio nobody may hear.
                    if (muted.get() || !emitFrames) {
                        uplink.clear()
                        continue
                    }

                    // AudioRecord is opened at 16 kHz, so this is a pass-through;
                    // the downsampler stays as a guard for devices that hand back
                    // frames at their native rate despite the request.
                    val pcm = downsampler?.process(readBuf, n) ?: readBuf
                    if (pcm.isEmpty()) continue
                    uplink.write(pcm, 0, pcm.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture loop died", e)
                _error.value = "Microphone stopped: ${e.message}"
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
                if (recorder === rec) recorder = null
            }
        }
        return true
    }

    fun stopCapture() {
        capturing = false
        // Wait for the recorder loop to exit: it writes to the ring, and a
        // restart must not race a thread that still believes it is recording.
        recordJob?.cancel()
        runCatching { recordJob?.join(400) }
        recordJob = null
        // Belt and braces: a device whose read() ignores cancellation should not
        // be left holding the mic open.
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        uplink.clear()
        downsampler?.reset()
        _micLevel.value = 0f
    }

    /**
     * Pull the next [dst.size] samples of microphone audio into [dst].
     *
     * Blocking with a timeout, called from the streaming coroutine (never from
     * the recorder thread). Returns 0 on timeout so the caller can check for
     * cancellation and socket state without spinning.
     *
     * The 2 ms sleep is jittery on purpose: parked-and-woken threads inside
     * audio callbacks are exactly what AudioRecord warns about ("unable to
     * configure", "obtain buffer timed out"). We are not on the audio callback
     * here, and ~20 wakeups a second is nothing next to a 320-sample frame.
     */
    fun readMic(dst: ShortArray, timeoutMs: Long = 100): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val n = uplink.read(dst, dst.size)
            if (n > 0) return n
            if (System.nanoTime() > deadline || !capturing) return 0
            try {
                Thread.sleep(2)
            } catch (e: InterruptedException) {
                // Cancellation must not swallow the interrupt flag.
                Thread.currentThread().interrupt()
                return 0
            }
        }
    }

    fun clearMic() = uplink.clear()

    /** Samples the ring had to discard because the socket could not keep up. */
    fun takeDroppedMicSamples(): Int = uplink.takeDropped()

    /** True = ignore mic input upstream (used while Zoya speaks, if desired). */
    fun setMuted(value: Boolean) = muted.set(value)

    fun isCapturing(): Boolean = capturing

    // ------------------------------------------------------------ playback --

    private var track: AudioTrack? = null
    private var playerJob: Job? = null
    private val playerQueue = ArrayBlockingQueue<ShortArray>(512)
    private val playing = AtomicBoolean(false)
    private val queuedSamples = AtomicLong(0)
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun ensurePlayback(): AudioTrack {
        track?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }

        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBuf, PLAYBACK_RATE / 4 * 2) // >= 250 ms

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        t.play()
        track = t
        startPlayerThread(t)
        return t
    }

    private fun startPlayerThread(t: AudioTrack) {
        if (playerJob?.isActive == true) return
        playing.set(true)
        playerJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (isActive && playing.get()) {
                    val chunk = playerQueue.poll(60, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        _speaking.value = queuedSamples.get() > 0
                        continue
                    }
                    val bytes = Pcm16.fromShorts(chunk)
                    var offset = 0
                    while (offset < bytes.size && playing.get()) {
                        val written = t.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        offset += written
                    }
                    queuedSamples.addAndGet(-chunk.size.toLong())
                    _playbackLevel.value = smooth(_playbackLevel.value, Pcm16.rms(chunk))
                    _speaking.value = queuedSamples.get() > 0 || playerQueue.isNotEmpty()
                }
            } catch (e: Exception) {
                Log.e(TAG, "player loop died", e)
            } finally {
                _speaking.value = false
                _playbackLevel.value = 0f
            }
        }
    }

    /** Queue model audio. Bytes are 24 kHz PCM16 LE. */
    fun enqueuePcm16(bytes: ByteArray) {
        if (bytes.size < 2) return
        val shorts = Pcm16.toShorts(bytes)

        // Latency guard: if the queue is already long, drop this chunk. A
        // growing backlog means the model is outrunning the speaker, and a
        // 3-second-late answer is worse than a clipped one.
        val backlogMs = queuedSamples.get() * 1000 / PLAYBACK_RATE
        if (backlogMs > PLAYBACK_BACKLOG_LIMIT_MS) return

        ensurePlayback()
        queuedSamples.addAndGet(shorts.size.toLong())
        _speaking.value = true
        playerQueue.offer(shorts)
    }

    /** Barge-in. Empties the queue; the writer thread keeps running. */
    fun flushPlayback() {
        playerQueue.clear()
        queuedSamples.set(0)
        _speaking.value = false
        _playbackLevel.value = 0f
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.play() }
    }

    fun stopPlayback() {
        playing.set(false)
        playerJob?.cancel()
        playerJob = null
        playerQueue.clear()
        queuedSamples.set(0)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        _speaking.value = false
        abandonFocus()
    }

    // ----------------------------------------------------------- lifecycle --

    /**
     * Take audio focus so Zoya does not talk over the user's music forever,
     * and so phone calls still win. Duck is intentional: we want the user to
     * hear her, but a call must interrupt her, not the other way round.
     */
    fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        flushPlayback()
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    fun release() {
        stopCapture()
        stopPlayback()
        scope.cancel()
    }

    private fun smooth(previous: Float, next: Float): Float {
        // Fast attack, slow release: reads as alive rather than twitchy.
        val attack = next > previous
        val factor = if (attack) 0.65f else 0.18f
        val value = previous + (next - previous) * factor
        return min(1f, max(0f, value * 2.4f))
    }

    /** Exposed for the waveform: current output amplitude, 0..1. */
    fun outputAmplitude(): Float = sqrt(_playbackLevel.value)
}
