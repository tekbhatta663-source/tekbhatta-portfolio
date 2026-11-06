package ai.zoya.assistant.ui

import ai.zoya.assistant.service.ZoyaService
import ai.zoya.assistant.tools.PermissionState
import ai.zoya.assistant.domain.ZoyaState
import ai.zoya.assistant.ui.components.PermissionsOnboarding
import ai.zoya.assistant.ui.components.ZoyaOrb
import ai.zoya.assistant.ui.theme.Cyan
import ai.zoya.assistant.ui.theme.Ink
import ai.zoya.assistant.ui.theme.Rose
import ai.zoya.assistant.ui.theme.RoseSoft
import ai.zoya.assistant.ui.theme.ZoyaTheme
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * MainActivity
 * ------------
 * Two screens and nothing else:
 *   - onboarding, until the essentials are granted and the user says go
 *   - the orb, which is the entire application
 *
 * The activity starts the foreground service on first frame (if the mic is
 * allowed) and then genuinely steps out of the way: killing the activity does
 * not end the conversation, because the session lives in the service.
 */
class MainActivity : ComponentActivity() {

    /** Set by the orb screen so onStart can put her straight back into listening. */
    internal var onZoyaVisible: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZoyaTheme {
                ZoyaRoot()
            }
        }
    }

    /**
     * Start the service from the activity, not from Application.onCreate: this
     * is a visible, user-initiated context, which is required for a microphone
     * foreground service on Android 14+.
     */
    private fun startZoyaService() {
        if (!PermissionState.has(this, PermissionState.RECORD_AUDIO)) return
        val intent = Intent(this, ZoyaService::class.java).setAction(ZoyaService.ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startZoyaService()
        // Returning to a visible app means the user wants to talk.
        onZoyaVisible?.invoke()
    }
}

@Composable
private fun ZoyaRoot(vm: ZoyaViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? ComponentActivity
    var showOnboarding by remember { mutableStateOf(!vm.onboarded) }

    // Once the mic is allowed the service can come up. Tying this to the
    // lifecycle rather than an effect means returning from the background goes
    // straight into listening again, which is what a voice app should do.
    DisposableEffect(showOnboarding, activity) {
        val host = activity as? MainActivity
        if (!showOnboarding && host != null) {
            host.onZoyaVisible = { vm.wake() }
        }
        onDispose { host?.onZoyaVisible = null }
    }

    AnimatedContent(
        targetState = showOnboarding,
        transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(260)) },
        label = "screen",
    ) { onboarding ->
        if (onboarding) {
            PermissionsOnboarding(
                userName = ui.userName,
                voice = ui.voice,
                wakeWord = ui.wakeWordEnabled,
                autostart = vm.autostart,
                configured = ui.configured,
                onUserNameChange = vm::setUserName,
                onVoiceChange = vm::setVoice,
                onWakeWordChange = vm::setWakeWord,
                onAutostartChange = vm::setAutostart,
                onDone = {
                    vm.markOnboarded()
                    showOnboarding = false
                },
            )
        } else {
            ZoyaHome(
                state = ui.state,
                micLevel = ui.micLevel,
                outputLevel = ui.outputLevel,
                lastTool = ui.lastTool,
                error = ui.error,
                wakeWord = ui.wakeWordEnabled,
                onOrbTap = vm::onOrbTap,
                onSettings = { showOnboarding = true },
            )
        }
    }
}

@Composable
private fun ZoyaHome(
    state: ZoyaState,
    micLevel: Float,
    outputLevel: Float,
    lastTool: String?,
    error: String?,
    wakeWord: Boolean,
    onOrbTap: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF07080E),
                    0.45f to Color(0xFF0B0715),
                    1f to Color(0xFF05060A),
                ),
            )
            .safeDrawingPadding(),
    ) {
        // Ambient light, drawn behind everything.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Rose.copy(alpha = 0.10f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset.Unspecified,
                        radius = 900f,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))

            // Transient "what she's doing" strip — not a chat log.
            Box(modifier = Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                if (lastTool != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.055f))
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Cyan),
                        )
                        Text(lastTool, color = Ink.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            ZoyaOrb(
                state = state,
                micLevel = micLevel,
                outputLevel = outputLevel,
                onTap = onOrbTap,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(360.dp),
            )

            Spacer(Modifier.height(22.dp))

            AnimatedContent(
                targetState = statusLine(state, micLevel, wakeWord),
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                label = "status",
            ) { line ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = line,
                        color = Ink.copy(alpha = 0.62f),
                        fontSize = 13.5.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (error != null) {
                        Text(
                            text = error,
                            color = RoseSoft.copy(alpha = 0.85f),
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else if (!PermissionState.has(context, PermissionState.READ_CONTACTS)) {
                        // A quiet, honest nudge: tools that need contacts are off.
                        Text(
                            text = "contacts access off · calls and WhatsApp unavailable",
                            color = Ink.copy(alpha = 0.22f),
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "GEMINI 3.1 FLASH LIVE",
                    color = Ink.copy(alpha = 0.2f),
                    fontSize = 9.sp,
                    letterSpacing = 2.2.sp,
                )
                Text(
                    text = "settings",
                    color = Ink.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .clickable { onSettings() }
                        .padding(6.dp),
                )
            }
        }
    }
}

private fun statusLine(state: ZoyaState, micLevel: Float, wakeWord: Boolean): String = when (state) {
    ZoyaState.Asleep ->
        if (wakeWord) "Asleep. Say “Zoya” or tap me." else "Asleep. Tap the orb."
    ZoyaState.WaitingForWake ->
        if (wakeWord) "Waiting for “Zoya”…" else "Standing by — tap to talk"
    ZoyaState.Connecting -> "Connecting…"
    ZoyaState.Listening -> if (micLevel > 0.08f) "I hear you" else "Go on, I'm listening"
    ZoyaState.Thinking -> "On it…"
    ZoyaState.Speaking -> "Tap me to cut in"
    ZoyaState.Error -> "Something broke. Tap to retry."
}
