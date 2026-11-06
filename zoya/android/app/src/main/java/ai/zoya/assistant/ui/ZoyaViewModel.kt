package ai.zoya.assistant.ui

import ai.zoya.assistant.audio.AudioEngine
import ai.zoya.assistant.data.ZoyaPrefs
import ai.zoya.assistant.domain.ZoyaState
import ai.zoya.assistant.live.LiveSessionManager
import ai.zoya.assistant.service.ZoyaService
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ZoyaViewModel
 * -------------
 * MVVM seam between the service (which owns audio + the websocket) and Compose.
 *
 * It holds no audio itself. It binds to [ZoyaService.session] once the service
 * exists, surfaces one immutable [UiState], and forwards user intents as
 * service commands. That means rotating the phone, or the activity dying while
 * Zoya keeps talking in the background, changes nothing about the session.
 */
class ZoyaViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val state: ZoyaState = ZoyaState.Asleep,
        val micLevel: Float = 0f,
        val outputLevel: Float = 0f,
        val voice: String = "Aoede",
        val wakeWordEnabled: Boolean = true,
        val userName: String = "",
        val lastTool: String? = null,
        val error: String? = null,
        val configured: Boolean = ai.zoya.assistant.BuildConfig.GEMINI_API_KEY.isNotBlank() ||
            ai.zoya.assistant.BuildConfig.TOKEN_SERVER_URL.isNotBlank(),
    )

    private val prefs = ZoyaPrefs(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _activity = MutableStateFlow<List<String>>(emptyList())
    val activity: StateFlow<List<String>> = _activity.asStateFlow()

    private var bound: LiveSessionManager? = null
    private var boundAudio: AudioEngine? = null

    /**
     * Set when the user asked to wake before the service existed (cold start
     * races the activity). Flushed as soon as [bind] succeeds, so tapping the
     * orb during startup is never silently swallowed.
     */
    private var pendingWake = false
    /** Controls whether the service should be live right now (post-onboarding). */
    private var wantLive = false

    init {
        viewModelScope.launch {
            // The service may be created after this ViewModel (e.g. cold start).
            var attempts = 0
            while (ZoyaService.session == null && attempts < 40) {
                delay(100)
                attempts++
            }
            bind()
        }
    }

    private fun bind() {
        val session = ZoyaService.session ?: return
        val audio = ZoyaService.audioEngine ?: return
        bound = session
        boundAudio = audio

        if (pendingWake) {
            pendingWake = false
            ZoyaService.instance?.wakeUp(reason = "ui-pending")
        } else if (!wantLive) {
            // Post-onboarding relaunch: go straight to the wake-word state
            // rather than opening a session the user did not ask for.
            ZoyaService.instance?.hangUp()
        }

        viewModelScope.launch {
            combine(
                session.state,
                audio.micLevel,
                audio.playbackLevel,
                prefs.voiceFlow,
                prefs.wakeFlow,
                prefs.userNameFlow,
                session.lastError,
            ) { values ->
                UiState(
                    state = values[0] as ZoyaState,
                    micLevel = values[1] as Float,
                    outputLevel = values[2] as Float,
                    voice = values[3] as String,
                    wakeWordEnabled = values[4] as Boolean,
                    userName = values[5] as String,
                    lastTool = _ui.value.lastTool,
                    error = values[6] as String?,
                    configured = _ui.value.configured,
                )
            }.collect { _ui.value = it }
        }

        viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    is LiveSessionManager.Event.ToolUsed -> {
                        _ui.value = _ui.value.copy(lastTool = event.summary)
                        _activity.value = (_activity.value + event.summary).takeLast(4)
                    }
                    is LiveSessionManager.Event.Notice ->
                        _ui.value = _ui.value.copy(error = event.message)
                    is LiveSessionManager.Event.Ready ->
                        _ui.value = _ui.value.copy(error = null)
                }
            }
        }
    }

    // --------------------------------------------------------------- intents --

    /** Tap on the orb: wake, hang up, or cut her off — context decides. */
    fun onOrbTap() {
        val session = bound ?: return
        when (session.state.value) {
            ZoyaState.Speaking, ZoyaState.Thinking -> session.interrupt()
            ZoyaState.Listening, ZoyaState.Connecting -> ZoyaService.instance?.hangUp()
            else -> wake()
        }
    }

    fun wake() {
        wantLive = true
        val service = ZoyaService.instance
        if (service == null) {
            pendingWake = true
        } else {
            service.wakeUp(reason = "ui")
        }
    }

    fun hangUp() {
        wantLive = false
        ZoyaService.instance?.hangUp()
    }

    fun setVoice(voice: String) {
        prefs.voice = voice
        // The voice is baked into the session setup, so a change needs a new
        // session — but only if one is actually running.
        val service = ZoyaService.instance ?: return
        if (bound?.state?.value?.isLive == true) {
            service.hangUp()
            service.wakeUp(reason = "voice-change")
        }
    }

    /**
     * Turning the wake word off hangs up: there is no reason to keep a live
     * socket open if she can no longer be summoned while backgrounded. Turning
     * it on leaves the current session alone and just resumes listening.
     */
    fun setWakeWord(enabled: Boolean) {
        prefs.wakeWordEnabled = enabled
        if (!enabled) ZoyaService.instance?.hangUp()
    }

    fun setUserName(name: String) {
        prefs.userName = name.trim().take(32)
    }

    fun forget() {
        prefs.forgetEverything()
        _activity.value = emptyList()
    }

    fun setAutostart(enabled: Boolean) {
        prefs.autostartOnBoot = enabled
    }

    val autostart: Boolean get() = prefs.autostartOnBoot
    val onboarded: Boolean get() = prefs.onboarded

    fun markOnboarded() {
        prefs.onboarded = true
    }

    override fun onCleared() {
        super.onCleared()
        bound = null
        boundAudio = null
    }
}
