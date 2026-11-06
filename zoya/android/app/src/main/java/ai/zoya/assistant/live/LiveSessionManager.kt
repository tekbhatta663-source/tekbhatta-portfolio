package ai.zoya.assistant.live

import ai.zoya.assistant.BuildConfig
import ai.zoya.assistant.audio.AudioEngine
import ai.zoya.assistant.audio.Pcm16
import ai.zoya.assistant.data.ZoyaPrefs
import ai.zoya.assistant.domain.ZoyaState
import ai.zoya.assistant.tools.ToolExecutionEngine
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LiveSessionManager
 * ------------------
 * One Live API session, end to end.
 *
 * Ownership:
 *   - owns the OkHttp websocket and the protocol frames
 *   - pulls mic frames from [AudioEngine.capture] and ships them up
 *   - pushes model audio back into [AudioEngine] for playback
 *   - routes toolCalls to [ToolExecutionEngine] and answers immediately
 *   - reconnects with the server's resumption handle when the socket drops
 *
 * Thread safety: every write to the websocket goes through [sendMutex] because
 * OkHttp throws if two threads send at once. The mic coroutine and the tool
 * coroutine both write, so this is not theoretical. Reads never block the
 * audio path: the model's audio goes straight into the playback queue.
 *
 * It knows nothing about Compose. The service and the ViewModel both observe
 * [state] and [events]; nothing here touches a View.
 */
class LiveSessionManager(
    private val audio: AudioEngine,
    private val prefs: ZoyaPrefs,
    private val tools: ToolExecutionEngine,
) {
    companion object {
        private const val TAG = "Zoya/Live"
        private const val MAX_RECONNECTS = 8
        private const val BARGE_IN_RMS = 0.11f
    }

    sealed interface Event {
        data class ToolUsed(val summary: String, val ok: Boolean) : Event
        data class Notice(val message: String) : Event
        data object Ready : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    private val _state = MutableStateFlow(ZoyaState.Asleep)
    val state: StateFlow<ZoyaState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var webSocket: WebSocket? = null
    private var micJob: Job? = null
    private val connected = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val reconnects = AtomicInteger(0)
    private val modelSpeaking = AtomicBoolean(false)
    private var loudFrames = 0
    private var resumeHandle: String? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // live streams never time out
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---------------------------------------------------------------- public --

    /** Open a session and start streaming. Safe to call repeatedly. */
    fun start() {
        if (connected.get()) return
        closing.set(false)
        _state.value = ZoyaState.Connecting

        scope.launch {
            val credential = resolveCredential()
            if (credential == null) {
                _state.value = ZoyaState.Error
                _lastError.value =
                    "No Gemini key. Add GEMINI_API_KEY to zoya.properties (or set TOKEN_SERVER_URL) and rebuild."
                _events.emit(Event.Notice("No API key configured."))
                return@launch
            }

            val url = LiveProtocol.WS_BASE +
                if (credential.startsWith("auth_tokens/")) {
                    "?access_token=$credential"
                } else {
                    "?key=$credential"
                }

            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .build()

            val listener = SocketListener(credential)
            webSocket = client.newWebSocket(request, listener)
        }
    }

    /** Hang up: closes the socket and stops shipping mic audio. */
    fun stop() {
        closing.set(true)
        connected.set(false)
        micJob?.cancel()
        micJob = null
        runCatching { webSocket?.close(1000, "user hangup") }
        webSocket = null
        audio.flushPlayback()
        _state.value = ZoyaState.Asleep
    }

    fun setSleeping() {
        _state.value = if (connected.get()) ZoyaState.WaitingForWake else ZoyaState.Asleep
    }

    /** Push an out-of-band instruction (wake word, timer, host event). */
    fun nudge(text: String) {
        scope.launch {
            if (!connected.get()) return@launch
            send(LiveProtocol.textFrame(text))
        }
    }

    /** Manual barge-in from the UI (tap the orb while she speaks). */
    fun interrupt() {
        audio.flushPlayback()
        modelSpeaking.set(false)
        if (connected.get()) _state.value = ZoyaState.Listening
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    // --------------------------------------------------------------- socket --

    private inner class SocketListener(private val credential: String) : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "socket open")
            scope.launch {
                send(
                    LiveProtocol.setupFrame(
                        apiKey = credential,
                        systemInstruction = buildSystemInstruction(),
                        voiceName = prefs.voice,
                        tools = tools.declarations,
                        resumeHandle = resumeHandle,
                    ),
                )
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val events = runCatching { LiveProtocol.parse(text) }
                .onFailure { Log.w(TAG, "unparseable frame", it) }
                .getOrDefault(emptyList())

            for (event in events) {
                when (event) {
                    is LiveProtocol.ServerEvent.SetupComplete -> onSetupComplete()
                    is LiveProtocol.ServerEvent.Audio -> onAudio(event.bytes)
                    is LiveProtocol.ServerEvent.Interrupted -> onInterrupted()
                    is LiveProtocol.ServerEvent.TurnComplete -> {
                        if (!audio.speaking.value) {
                            _state.value = ZoyaState.Listening
                            modelSpeaking.set(false)
                        }
                    }
                    is LiveProtocol.ServerEvent.ToolCalls -> onToolCalls(event)
                    is LiveProtocol.ServerEvent.ToolCancelled ->
                        Log.i(TAG, "server cancelled tools ${event.ids}")
                    is LiveProtocol.ServerEvent.Resumption -> {
                        resumeHandle = event.handle
                        prefs.resumeHandle = event.handle
                    }
                    is LiveProtocol.ServerEvent.GoAway -> {
                        Log.i(TAG, "goAway, ${event.timeLeft} left; reconnecting")
                        reconnect(reason = "goAway", delayMs = 250)
                    }
                    is LiveProtocol.ServerEvent.Failure -> {
                        _lastError.value = event.message
                    }
                }
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "socket failure (${response?.code})", t)
            connected.set(false)
            if (closing.get()) return
            _lastError.value = t.message ?: "Connection failed (${response?.code})"
            reconnect(reason = "failure", delayMs = 600)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "socket closed $code $reason")
            connected.set(false)
            if (closing.get()) return
            reconnect(reason = "closed($code)", delayMs = if (code == 1000) 400 else 900)
        }
    }

    private fun onSetupComplete() {
        Log.i(TAG, "session ready")
        connected.set(true)
        reconnects.set(0)
        _lastError.value = null
        startMicPump()
        if (_state.value == ZoyaState.Connecting) _state.value = ZoyaState.Listening
        scope.launch { _events.emit(Event.Ready) }
    }

    private fun onAudio(pcm: ByteArray) {
        modelSpeaking.set(true)
        if (_state.value != ZoyaState.Speaking) _state.value = ZoyaState.Speaking
        audio.enqueuePcm16(pcm)
        // Detect the end of the utterance once the queue drains.
        if (!audio.speaking.value) {
            modelSpeaking.set(false)
            if (_state.value == ZoyaState.Speaking) _state.value = ZoyaState.Listening
        }
    }

    private fun onInterrupted() {
        // Server-side barge-in: the user spoke over her.
        audio.flushPlayback()
        modelSpeaking.set(false)
        _state.value = ZoyaState.Listening
    }

    private fun onToolCalls(event: LiveProtocol.ServerEvent.ToolCalls) {
        _state.value = ZoyaState.Thinking
        scope.launch {
            val responses = event.calls.map { call ->
                val name = call.name.orEmpty()
                val args = call.args ?: JsonObject(emptyMap())
                val result = runCatching { tools.execute(name, args) }
                    .getOrElse { e ->
                        ToolExecutionEngine.Result(
                            ok = false,
                            summary = e.message ?: "tool crashed",
                            payload = JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(e.message ?: "tool crashed"))),
                        )
                    }
                _events.emit(Event.ToolUsed(result.summary, result.ok))
                Triple(call.id, name, result.payload)
            }

            // Answer immediately; the model's turn is blocked until it hears back.
            send(LiveProtocol.toolResponseFrame(responses))

            if (_state.value == ZoyaState.Thinking) {
                _state.value = if (audio.speaking.value) ZoyaState.Speaking else ZoyaState.Listening
            }
        }
    }

    private fun reconnect(reason: String, delayMs: Long) {
        if (closing.get()) return
        micJob?.cancel()
        micJob = null
        runCatching { webSocket?.close(1001, "reconnect") }
        webSocket = null

        val attempt = reconnects.incrementAndGet()
        if (attempt > MAX_RECONNECTS) {
            _state.value = ZoyaState.Error
            _lastError.value = "Lost connection and gave up after $MAX_RECONNECTS tries."
            return
        }
        _state.value = ZoyaState.Connecting
        scope.launch {
            delay(delayMs * attempt)
            if (!closing.get()) start()
        }
    }

    // ------------------------------------------------------------- mic pump --

    /**
     * Bridges the microphone ring to the socket. This is the only place PCM16
     * crosses from the recorder thread to the network thread.
     *
     * Shape matters: we pull 20 ms of samples per iteration out of a lock-free
     * ring into one reusable array, then base64 that array once. Nothing is
     * allocated per frame except the frame string itself, so the GC has nothing
     * to pause for while audio is flowing.
     */
    private fun startMicPump() {
        if (micJob?.isActive == true) return
        micJob = scope.launch {
            val samples = ShortArray(AudioEngine.FRAME_SAMPLES)
            val pcm = ByteArray(AudioEngine.FRAME_SAMPLES * 2)

            while (isActive && connected.get()) {
                val n = audio.readMic(samples, timeoutMs = 100)
                if (n <= 0) continue

                // Local barge-in: if she is talking and the user starts, cut her
                // off before the server's `interrupted` frame arrives.
                if (modelSpeaking.get()) {
                    val rms = Pcm16.rms(samples, n)
                    loudFrames = if (rms > BARGE_IN_RMS) loudFrames + 1 else 0
                    if (loudFrames >= 3) {
                        loudFrames = 0
                        audio.flushPlayback()
                        modelSpeaking.set(false)
                        _state.value = ZoyaState.Listening
                    }
                }

                if (_state.value == ZoyaState.Speaking) continue
                Pcm16.writeInto(pcm, 0, samples, n)
                send(LiveProtocol.audioFrame(pcm, n * 2))
            }
        }
    }

    // -------------------------------------------------------------- helpers --

    private suspend fun send(payload: String) {
        val ws = webSocket ?: return
        sendMutex.withLock {
            // OkHttp queues internally, but a false result means the socket is
            // gone; treat it as a dropped frame rather than an exception.
            if (!ws.send(payload)) Log.w(TAG, "frame dropped (socket not open)")
        }
    }

    /**
     * Two ways to authenticate:
     *  - TOKEN_SERVER_URL set  -> fetch a single-use token (key stays server-side)
     *  - otherwise             -> use the key baked in at build time
     */
    private suspend fun resolveCredential(): String? = withContext(Dispatchers.IO) {
        val server = BuildConfig.TOKEN_SERVER_URL
        if (server.isNotBlank()) {
            runCatching {
                val body = (
                    """{"model":"${LiveProtocol.MODEL}","voice":"${currentVoice()}"}"""
                        ).toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$server/api/token").post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val text = resp.body?.string() ?: return@use null
                    val obj = LiveProtocol.json.parseToJsonElement(text).let {
                        it as? JsonObject
                    }
                    obj?.get("token")?.let {
                        (it as kotlinx.serialization.json.JsonPrimitive).content
                    }
                }
            }.getOrNull() ?: return@withContext null
        }
        BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
    }

    /**
     * Snapshot the current voice without suspending. [prefs.voice] is a plain
     * SharedPreferences-backed property, unlike the StateFlow mirrors, so it is
     * safe to read from any thread — which is exactly what a token request is.
     */
    private fun currentVoice(): String = prefs.voice

    private fun buildSystemInstruction(): String = buildString {
        append(ai.zoya.assistant.domain.ZoyaPersona.SYSTEM_INSTRUCTION)
        append("\n\n# Live context\n")
        append("Local time: ${java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}.")
        append("\nTimezone: ${java.util.TimeZone.getDefault().id}.")
        append("\nPlatform: native Android phone. You have real device tools: openApp, searchAndCallContact, sendWhatsAppMessage, sendGmail, openWebsite, setTimer, rememberFact.")
        val name = prefs.userName
        if (name.isNotBlank()) append("\nThe user's name is $name.")
        val facts = prefs.facts
        if (facts.isNotEmpty()) {
            append("\nThings you already remember about the user:")
            facts.forEach { (k, v) -> append("\n- $k: $v") }
        } else {
            append("\nYou don't know anything about this user yet.")
        }
        if (!prefs.wakeWordEnabled) {
            append("\nThe wake word is off, so they summoned you directly.")
        }
    }
}
