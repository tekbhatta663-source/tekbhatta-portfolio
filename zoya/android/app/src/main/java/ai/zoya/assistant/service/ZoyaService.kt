package ai.zoya.assistant.service

import ai.zoya.assistant.audio.AudioEngine
import ai.zoya.assistant.audio.WakeWordDetector
import ai.zoya.assistant.data.ZoyaPrefs
import ai.zoya.assistant.domain.ZoyaState
import ai.zoya.assistant.live.LiveSessionManager
import ai.zoya.assistant.tools.PermissionState
import ai.zoya.assistant.tools.ToolExecutionEngine
import ai.zoya.assistant.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * ZoyaService
 * -----------
 * The always-on half of Zoya.
 *
 * Responsibilities:
 *  - hold a microphone foreground service so the OS does not reap us
 *  - keep a persistent notification that reflects her real state
 *  - run the wake-word detector over the mic while she sleeps
 *  - own the [LiveSessionManager] for the whole process lifetime, so the UI,
 *    the notification and a Bluetooth headset all talk to the same session
 *
 * Deliberate choices:
 *  - START_STICKY + a restart path, because "it just stopped working" is the
 *    number one complaint about Android assistants.
 *  - The service is the single owner of audio. MainActivity only sends commands.
 *  - While asleep we never stream to Gemini; only local frames are inspected,
 *    which is what keeps the wake-word path cheap and private.
 */
class ZoyaService : Service() {

    companion object {
        private const val TAG = "Zoya/Service"
        const val CHANNEL_ID = "zoya.listening"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "ai.zoya.START"
        const val ACTION_STOP = "ai.zoya.STOP"
        const val ACTION_WAKE = "ai.zoya.WAKE"
        const val ACTION_HANGUP = "ai.zoya.HANGUP"
        const val ACTION_TOGGLE_MUTE = "ai.zoya.MUTE"

        @Volatile
        var instance: ZoyaService? = null
            private set

        /**
         * The one place the UI gets the session from. Null until the service
         * has actually started, which the ViewModel waits on.
         */
        @Volatile
        var session: LiveSessionManager? = null
            private set

        @Volatile
        var audioEngine: AudioEngine? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: ZoyaPrefs
    private lateinit var audio: AudioEngine
    private lateinit var live: LiveSessionManager
    private lateinit var tools: ToolExecutionEngine

    private var wakeDetector = WakeWordDetector()
    private var wakeJob: Job? = null
    private var stateJob: Job? = null
    private var timerJob: Job? = null
    private var lastNotificationState: ZoyaState? = null
    private var muted = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        instance = this
        prefs = ZoyaPrefs(this)
        audio = AudioEngine(this).also { audioEngine = it }
        tools = ToolExecutionEngine(this, prefs)
        live = LiveSessionManager(audio, prefs, tools).also { session = it }

        createChannel()
        observeState()
        armTimers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_HANGUP -> {
                live.stop()
                enterSleep()
            }

            ACTION_WAKE -> {
                wakeUp(reason = "notification")
            }

            ACTION_TOGGLE_MUTE -> {
                muted = !muted
                audio.setMuted(muted)
                pushNotification(live.state.value, force = true)
            }

            else -> {
                startInForeground(live.state.value)
                if (PermissionState.has(this, PermissionState.RECORD_AUDIO)) {
                    // The activity calls wakeUp() the moment it is visible; this
                    // only covers a cold start straight into the background, so
                    // she should sit quietly until asked.
                    enterSleep()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away should not kill the assistant.
        Log.i(TAG, "task removed, staying alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stateJob?.cancel()
        timerJob?.cancel()
        stopWakeLoop()
        live.shutdown()
        audio.release()
        session = null
        audioEngine = null
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- foreground --

    private fun startInForeground(state: ZoyaState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(ai.zoya.assistant.R.string.notif_channel_listening),
            NotificationManager.IMPORTANCE_LOW, // persistent, but must not buzz
        ).apply {
            description = getString(ai.zoya.assistant.R.string.notif_channel_listening_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ZoyaState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, text) = when (state) {
            ZoyaState.Asleep ->
                getString(ai.zoya.assistant.R.string.notif_title_idle) to "Tap to wake her."
            ZoyaState.WaitingForWake, ZoyaState.Connecting ->
                getString(ai.zoya.assistant.R.string.notif_title_waiting) to
                    if (state == ZoyaState.Connecting) "Connecting…" else "Say her name."
            ZoyaState.Listening -> getString(ai.zoya.assistant.R.string.notif_title_live) to
                "Listening. Talk to her."
            ZoyaState.Thinking -> getString(ai.zoya.assistant.R.string.notif_title_live) to
                "On it…"
            ZoyaState.Speaking -> getString(ai.zoya.assistant.R.string.notif_title_speaking) to
                "Tap to interrupt."
            ZoyaState.Error -> "Zoya hit a snag" to "Tap to try again."
        }

        val hangup = if (state.isLive) {
            PendingIntent.getService(
                this,
                1,
                Intent(this, ZoyaService::class.java).setAction(ACTION_HANGUP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getService(
                this,
                1,
                Intent(this, ZoyaService::class.java).setAction(ACTION_WAKE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val muteAction = PendingIntent.getService(
            this,
            2,
            Intent(this, ZoyaService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ai.zoya.assistant.R.drawable.ic_zoya_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                getString(
                    if (state.isLive) {
                        ai.zoya.assistant.R.string.notif_action_stop
                    } else {
                        ai.zoya.assistant.R.string.notif_action_open
                    },
                ),
                if (state.isLive) hangup else openIntent,
            )
            .addAction(0, if (muted) "Unmute" else "Mute", muteAction)
            .build()
    }

    private fun observeState() {
        stateJob = scope.launch {
            combine(
                live.state,
                audio.speaking,
            ) { state, speaking -> state to speaking }
                .distinctUntilChanged()
                .collect { (state, speaking) -> updateNotification(state, speaking) }
        }
    }

    private fun updateNotification(state: ZoyaState, speaking: Boolean) {
        val effective = if (speaking && state == ZoyaState.Listening) ZoyaState.Speaking else state
        if (effective == lastNotificationState) return
        lastNotificationState = effective
        pushNotification(effective, force = true)
    }

    private fun pushNotification(state: ZoyaState, force: Boolean) {
        if (!force && state == lastNotificationState) return
        lastNotificationState = state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionState.has(this, PermissionState.POST_NOTIFICATIONS)
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(state))
        }
    }

    // ------------------------------------------------------------- wake path --

    /** Mic open, only the wake word is being matched. Cheap and private. */
    private fun enterSleep() {
        if (!PermissionState.has(this, PermissionState.RECORD_AUDIO)) {
            live.setSleeping()
            return
        }
        live.stop()
        wakeDetector.reset()
        stopWakeLoop()

        audio.startCapture(emitFrames = false)
        live.setSleeping()

        wakeJob = scope.launch {
            val samples = ShortArray(ai.zoya.assistant.audio.AudioEngine.FRAME_SAMPLES)
            while (isActive && !live.state.value.isLive) {
                val n = audio.readMic(samples, timeoutMs = 120)
                if (n <= 0) continue
                if (wakeDetector.feed(samples, n)) wakeUp(reason = "wake-word")
            }
        }
        Log.i(TAG, "sleeping, waiting for the wake word")
    }

    private fun stopWakeLoop() {
        wakeJob?.cancel()
        wakeJob = null
    }

    /**
     * Wake word (or the notification, or the UI) brings the session online.
     * The mic keeps streaming: capture was already running for the detector, so
     * there is no re-open latency and no audio gap.
     */
    fun wakeUp(reason: String) {
        Log.i(TAG, "wake ($reason)")
        stopWakeLoop()
        wakeDetector.reset()

        // Always restart capture: the sleeping capture runs with
        // emitFrames = false, so frames would never reach the socket.
        audio.stopCapture()
        audio.startCapture(emitFrames = true)
        audio.requestFocus()
        live.start()

        // Nudge her to greet on a wake word only; a notification tap means the
        // user is already looking at the screen.
        if (reason == "wake-word") {
            scope.launch {
                delay(700)
                if (live.state.value.isLive) {
                    live.nudge("[You were summoned by the wake word. Greet them in one short line, in character.]")
                }
            }
        }
    }

    fun hangUp() {
        live.stop()
        enterSleep()
    }

    // ---------------------------------------------------------------- timers --

    /**
     * Timers are device alarms, not server timers: the model gets told when one
     * fires and delivers the line herself.
     */
    private fun armTimers() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                prefs.timers.filter { it.firesAt <= now }.forEach { timer ->
                    prefs.removeTimer(timer.firesAt)
                    live.nudge(
                        "[on-device event: the \"${timer.label}\" timer just finished. " +
                            "Announce it in character, one short line.]",
                    )
                    audio.flushPlayback()
                    audio.enqueuePcm16(Tone.beep())
                }
                delay(1000)
            }
        }
    }

    // ------------------------------------------------------------------ stop --

    private fun shutdown() {
        Log.i(TAG, "shutdown requested by user")
        stopWakeLoop()
        live.stop()
        audio.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

/**
 * Placeholder so the service compiles without a resource: a tiny 200 ms
 * 880 Hz tone the user can actually hear when a timer fires.
 */
private object Tone {
    fun beep(): ByteArray {
        val rate = 24_000
        val samples = rate / 5
        val out = java.nio.ByteBuffer.allocate(samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val t = i.toDouble() / rate
            val envelope = 1.0 - (i.toDouble() / samples)
            val v = (kotlin.math.sin(2 * Math.PI * 880 * t) * envelope * 9000).toInt().toShort()
            out.putShort(v)
        }
        return out.array()
    }
}
