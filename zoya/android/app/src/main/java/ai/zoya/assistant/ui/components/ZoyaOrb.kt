package ai.zoya.assistant.ui.components

import ai.zoya.assistant.domain.ZoyaState
import ai.zoya.assistant.ui.theme.Cyan
import ai.zoya.assistant.ui.theme.Rose
import ai.zoya.assistant.ui.theme.RoseSoft
import ai.zoya.assistant.ui.theme.Violet
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ZoyaOrb
 * -------
 * The whole app in one control. A Canvas, an Animatable, and the real audio
 * envelopes — no fake sine waves standing in for "listening".
 *
 *  Asleep / WaitingForWake : slow breathing glow (4 s loop)
 *  Connecting              : a sweeping arc, ring contracts on each attempt
 *  Listening               : rim waveform driven by mic RMS, smoothed
 *  Thinking                : tight neon pulse ring
 *  Speaking                : rim waveform driven by the playback RMS
 *  Error                   : dimmed, desaturated, steady
 */
@Composable
fun ZoyaOrb(
    state: ZoyaState,
    micLevel: Float,
    outputLevel: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    // One continuous clock for every animated element.
    val infinite = rememberInfiniteTransition(label = "orb")
    val clock by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "clock",
    )
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    // Press feedback: a real spring, so the orb feels like an object.
    val press = remember { Animatable(1f) }
    val targetScale = if (state == ZoyaState.Speaking) 1f + outputLevel * 0.04f else 1f
    LaunchedEffect(state, outputLevel) {
        press.animateTo(
            targetValue = targetScale,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 220f),
        )
    }

    // Smooth the envelopes so the rim does not jitter frame to frame.
    val mic = rememberSmooth(micLevel)
    val out = rememberSmooth(outputLevel)

    Box(
        modifier = modifier
            .semantics {
                contentDescription = when (state) {
                    ZoyaState.Asleep -> "Start Zoya"
                    else -> "Zoya is ${state.name.lowercase()}"
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.29f * press.value

            val energy = when (state) {
                ZoyaState.Speaking -> 0.35f + out * 1.5f
                ZoyaState.Listening -> 0.18f + mic * 1.4f
                ZoyaState.Thinking -> 0.72f
                ZoyaState.Connecting -> 0.5f
                else -> 0.22f
            }.coerceIn(0f, 1f)
            val pulse = if (state == ZoyaState.Asleep || state == ZoyaState.WaitingForWake) {
                0.35f + breath * 0.35f
            } else {
                energy
            }

            drawAura(cx, cy, r, pulse, state)
            if (state != ZoyaState.Asleep && state != ZoyaState.Error) {
                drawOrbits(cx, cy, r, pulse, clock, state)
            }
            when (state) {
                ZoyaState.Listening -> drawMicWave(cx, cy, r, mic, clock)
                ZoyaState.Speaking -> drawOutWave(cx, cy, r, out, clock)
                ZoyaState.Thinking -> drawPulseRing(cx, cy, r, clock)
                ZoyaState.Connecting -> drawSweep(cx, cy, r, sweep)
                else -> Unit
            }
            drawCore(cx, cy, r, pulse)
            drawGlyph(cx, cy, r, state, clock)
        }

        if (caption != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 160.dp),
            ) {
                Text(
                    text = caption,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                )
            }
        }
    }
}

/* ------------------------------------------------------------- primitives -- */

private fun DrawScope.drawAura(cx: Float, cy: Float, r: Float, pulse: Float, state: ZoyaState) {
    val outer = r * 2.1f
    if (outer <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to Rose.copy(alpha = 0.30f * pulse),
            0.34f to Violet.copy(alpha = 0.18f * pulse),
            0.72f to Cyan.copy(alpha = 0.05f * pulse),
            1.0f to Color.Transparent,
            center = Offset(cx, cy),
            radius = outer,
        ),
        radius = outer,
        center = Offset(cx, cy),
    )
    if (state == ZoyaState.Error) {
        drawCircle(
            color = Color(0xFFFF6B6B).copy(alpha = 0.10f),
            radius = r * 1.5f,
            center = Offset(cx, cy),
        )
    }
}

private fun DrawScope.drawOrbits(
    cx: Float,
    cy: Float,
    r: Float,
    pulse: Float,
    clock: Float,
    state: ZoyaState,
) {
    val speed = if (state == ZoyaState.Thinking) 2.4f else 0.6f
    listOf(1.36f to 1f, 1.53f to -0.62f).forEachIndexed { index, (scale, dir) ->
        val rr = r * scale
        val start = clock * speed * dir * (180f / PI.toFloat())
        val arc = if (index == 0) 158f else 108f
        drawArc(
            color = if (index == 0) {
                RoseSoft.copy(alpha = 0.25f + 0.35f * pulse)
            } else {
                Violet.copy(alpha = 0.2f + 0.3f * pulse)
            },
            startAngle = start,
            sweepAngle = arc,
            useCenter = false,
            style = Stroke(width = 1.6f, cap = StrokeCap.Round),
            topLeft = Offset(cx - rr, cy - rr),
            size = Size(rr * 2, rr * 2),
        )
    }
}

/** 96 bars around the rim, amplitude from the real mic envelope. */
private fun DrawScope.drawMicWave(cx: Float, cy: Float, r: Float, level: Float, clock: Float) {
    val bars = 96
    val inner = r * 1.06f
    for (i in 0 until bars) {
        val a = (i.toFloat() / bars) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val interference =
            0.5f + 0.5f * abs(
                sin(a * 3f + clock) * 0.6f + sin(a * 7f - clock * 0.55f) * 0.4f,
            )
        val amp = (level * interference * 1.35f).coerceIn(0f, 1f)
        val len = r * (0.07f + amp * 0.42f)
        drawLine(
            color = Cyan.copy(alpha = 0.25f + amp * 0.7f),
            start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
            end = Offset(cx + cos(a) * (inner + len), cy + sin(a) * (inner + len)),
            strokeWidth = r * 0.016f,
            cap = StrokeCap.Round,
        )
    }
}

/** Same ring, but driven by what is coming out of the speaker. */
private fun DrawScope.drawOutWave(cx: Float, cy: Float, r: Float, level: Float, clock: Float) {
    val bars = 96
    val inner = r * 1.06f
    for (i in 0 until bars) {
        val a = (i.toFloat() / bars) * 2f * PI.toFloat() - PI.toFloat() / 2f
        // A travelling wave reads as "sound leaving her" rather than a meter.
        val travelling = 0.55f + 0.45f * sin(a * 5f - clock * 3f)
        val amp = (level * travelling * 1.6f).coerceIn(0f, 1f)
        val len = r * (0.05f + amp * 0.5f)
        drawLine(
            color = RoseSoft.copy(alpha = 0.2f + amp * 0.75f),
            start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
            end = Offset(cx + cos(a) * (inner + len), cy + sin(a) * (inner + len)),
            strokeWidth = r * 0.018f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawPulseRing(cx: Float, cy: Float, r: Float, clock: Float) {
    val inner = r * 1.07f
    val segments = 48
    for (i in 0 until segments) {
        val a = (i.toFloat() / segments) * 2f * PI.toFloat()
        val wave = 0.5f + 0.5f * sin(clock * 3f + i * 0.6f)
        val len = r * (0.03f + wave * 0.13f)
        drawLine(
            color = Violet.copy(alpha = 0.25f + wave * 0.6f),
            start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
            end = Offset(cx + cos(a) * (inner + len), cy + sin(a) * (inner + len)),
            strokeWidth = r * 0.018f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawSweep(cx: Float, cy: Float, r: Float, sweep: Float) {
    val rr = r * 1.12f
    drawArc(
        color = Rose.copy(alpha = 0.55f),
        startAngle = sweep * 360f,
        sweepAngle = 70f,
        useCenter = false,
        style = Stroke(width = r * 0.03f, cap = StrokeCap.Round),
        topLeft = Offset(cx - rr, cy - rr),
        size = Size(rr * 2, rr * 2),
    )
}

private fun DrawScope.drawCore(cx: Float, cy: Float, r: Float, pulse: Float) {
    // A soft shadow stand-in: a slightly larger dim disc under the orb.
    drawCircle(
        brush = Brush.radialGradient(
            0f to Rose.copy(alpha = 0.20f + 0.25f * pulse),
            1f to Color.Transparent,
            center = Offset(cx, cy),
            radius = r * 1.28f,
        ),
        radius = r * 1.28f,
        center = Offset(cx, cy),
    )

    drawCircle(
        brush = Brush.radialGradient(
            0.0f to Color.White,
            0.16f to Color(0xFFFFC4D6),
            0.46f to Rose,
            0.78f to Violet,
            1.0f to Color(0xFF140C28),
            center = Offset(cx - r * 0.28f, cy - r * 0.34f),
            radius = r * 1.15f,
        ),
        radius = r,
        center = Offset(cx, cy),
    )

    // Glass overlay for depth.
    drawCircle(
        brush = Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.26f),
            0.45f to Color.White.copy(alpha = 0.02f),
            1.0f to Color(0xFF05060A).copy(alpha = 0.34f),
            startY = cy - r,
            endY = cy + r,
        ),
        radius = r,
        center = Offset(cx, cy),
    )
}

private fun DrawScope.drawGlyph(cx: Float, cy: Float, r: Float, state: ZoyaState, clock: Float) {
    val ink = Color(0xFF0A0614).copy(alpha = 0.72f)
    val w = r * 0.11f
    when (state) {
        ZoyaState.Speaking -> {
            // Five bars, animated by the clock.
            for (i in 0 until 5) {
                val h = r * 0.16f + r * 0.2f * abs(sin(clock * 3f + i * 0.9f))
                drawLine(
                    color = ink,
                    start = Offset(cx + (i - 2) * r * 0.16f, cy - h / 2f),
                    end = Offset(cx + (i - 2) * r * 0.16f, cy + h / 2f),
                    strokeWidth = r * 0.07f,
                    cap = StrokeCap.Round,
                )
            }
        }

        ZoyaState.Thinking -> {
            for (i in 0 until 3) {
                drawCircle(
                    color = ink.copy(alpha = 0.35f + 0.4f * abs(sin(clock * 2f + i))),
                    radius = r * 0.075f,
                    center = Offset(cx + (i - 1) * r * 0.24f, cy),
                )
            }
        }

        else -> {
            // Microphone
            drawLine(
                color = ink,
                start = Offset(cx, cy - r * 0.30f),
                end = Offset(cx, cy + r * 0.06f),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
            drawArc(
                color = ink,
                startAngle = 15f,
                sweepAngle = 150f,
                useCenter = false,
                style = Stroke(width = r * 0.035f, cap = StrokeCap.Round),
                topLeft = Offset(cx - r * 0.22f, cy - r * 0.18f),
                size = Size(r * 0.44f, r * 0.44f),
            )
            drawLine(
                color = ink,
                start = Offset(cx, cy + r * 0.33f),
                end = Offset(cx, cy + r * 0.46f),
                strokeWidth = r * 0.035f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Exponential smoothing for an audio envelope. Recomposes only when the value
 * actually moves enough to be visible, which keeps the canvas cheap.
 */
@Composable
private fun rememberSmooth(target: Float): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) {
        anim.animateTo(
            targetValue = target.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 110, easing = LinearEasing),
        )
    }
    return anim.value
}
