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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *  - **Capture**: AudioRecord @ 16 kHz mono -> 20 ms frames, emitted as
 *    [CaptureFrame] on a hot SharedFlow. Never blocks the caller.
 *  - **Playback**: one AudioTrack @ 24 kHz with a bounded writer thread. The
 *    model's audio is queued, never played on the socket thread. flush()
 *    empties the queue in one call — that is barge-in.
 *  - **Levels**: mic RMS and playback RMS as StateFlows for the Compose orb.
 *
 * Thread model: one recorder thread, one player thread, both owned here.
 * Everything crossing a thread boundary is either a channel or a ring buffer.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "Zoya/Audio"
        const val CAPTURE_RATE = 16_000
        const val PLAYBACK_RATE = 24_000
        /** 20 ms at 16 kHz. */
        const val FRAME_SAMPLES = 320
        private val PLAYBACK_BACKLOG_LIMIT_MS = 900L
    }

    /** One 20 ms frame: [pcm16] is little-endian bytes ready for the socket. */
    data class CaptureFrame(val pcm16: ByteArray, val rms: Float)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _capture = MutableSharedFlow<CaptureFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val capture: SharedFlow<CaptureFrame> = _capture.asSharedFlow()

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
    private var chunker: PcmChunker? = null

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
        downsampler = Downsampler(CAPTURE_RATE, CAPTURE_RATE)
        chunker = PcmChunker(FRAME_SAMPLES)
        capturing = true
        _error.value = null

        recordJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val readBuf = ShortArray(FRAME_SAMPLES)
            val outBytes = ByteArray(FRAME_SAMPLES * 2)
            try {
                rec.startRecording()
                while (isActive && capturing) {
                    val n = rec.read(readBuf, 0, readBuf.size)
                    if (n <= 0) continue

                    val level = Pcm16.rms(readBuf, n)
                    _micLevel.value = smooth(_micLevel.value, level)

                    if (muted.get() || !emitFrames) continue

                    // Already 16 kHz, so this is a pass-through; kept for
                    // devices that report a different actual rate.
                    val pcm = downsampler?.process(readBuf, n) ?: readBuf.copyOf(n)
                    chunker?.push(pcm, pcm.size)

                    while (true) {
                        val chunk = chunker?.poll() ?: break
                        for (i in chunk.indices) {
                            val s = chunk[i].toInt()
                            outBytes[i * 2] = (s and 0xFF).toByte()
                            outBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        _capture.tryEmit(CaptureFrame(outBytes.copyOf(), level))
                    }
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
        recordJob?.cancel()
        recordJob = null
        chunker?.clear()
        downsampler?.reset()
        _micLevel.value = 0f
    }

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
