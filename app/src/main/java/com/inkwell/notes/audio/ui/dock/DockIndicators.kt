package com.inkwell.notes.audio.ui.dock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.PI
import kotlin.math.sin

/** Live recording dot: pulsing red while recording, a hollow amber ring while paused. */
@Composable
internal fun RecordingDot(paused: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val c = Inkwell.colors
    val pulse = rememberInfiniteTransition(label = "dot")
    val alpha = pulse.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Canvas(modifier.size(size).graphicsLayer { this.alpha = if (paused) 1f else alpha.value }) {
        val r = this.size.minDimension / 2f
        if (paused) {
            val w = 2.dp.toPx()
            drawCircle(c.warning, radius = r - w / 2f, style = Stroke(width = w))
        } else {
            drawCircle(c.record, radius = r)
        }
    }
}

/** Blinking alpha for the paused time read-out (1 when not blinking). */
@Composable
internal fun rememberBlink(enabled: Boolean): State<Float> {
    val t = rememberInfiniteTransition(label = "blink")
    val v = t.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1200
                1f at 0
                1f at 650
                0.25f at 760
                0.25f at 1090
                1f at 1200
            },
        ),
        label = "blinkAlpha",
    )
    return if (enabled) v else FullAlpha
}

private object FullAlpha : State<Float> {
    override val value: Float = 1f
}

/**
 * Five-bar input meter. [level] is read in the draw phase only, so the 20 Hz level updates
 * redraw this canvas without recomposing anything.
 */
@Composable
internal fun LevelBars(level: () -> Float, paused: Boolean, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val wobble = rememberInfiniteTransition(label = "meter")
    val phase = wobble.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "meterPhase",
    )
    Canvas(modifier.size(width = 27.dp, height = 24.dp)) {
        val bars = WEIGHTS.size
        val barW = 3.dp.toPx()
        val gap = (size.width - bars * barW) / (bars - 1)
        val minH = 3.dp.toPx()
        val l = if (paused) 0f else level().coerceIn(0f, 1f)
        val ph = phase.value
        val color = if (paused) c.inkMuted.copy(alpha = 0.45f) else c.record
        for (i in 0 until bars) {
            val w = WEIGHTS[i] * (0.82f + 0.18f * sin(ph + i * 1.3f))
            val h = (minH + (size.height - minH) * (l * w).coerceIn(0f, 1f))
            val x = i * (barW + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

private val WEIGHTS = floatArrayOf(0.5f, 0.8f, 1f, 0.75f, 0.55f)
