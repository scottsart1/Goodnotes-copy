package com.inkwell.notes.audio.ui.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.inkwell.notes.audio.logic.Waveform
import com.inkwell.notes.speakers.ui.logic.LaneGeometry
import com.inkwell.notes.speakers.ui.logic.SpeakerLanes
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.max

/**
 * The recording's amplitude envelope as bars; the played part is drawn in the accent
 * colour. Tap or drag to seek (the position follows the finger and is applied on release).
 */
@Composable
internal fun WaveformBar(
    values: FloatArray?,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val cache = remember(values) { BarCache(values) }
    val played = scrubFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val f = (o.x / size.width).coerceIn(0f, 1f)
                    seek((f * duration).toLong())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrubFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        scrubFraction?.let { seek((it * duration).toLong()) }
                        scrubFraction = null
                    },
                    onDragCancel = { scrubFraction = null },
                )
            }
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
                setProgress { target ->
                    seek((target.coerceIn(0f, 1f) * duration).toLong())
                    true
                }
            },
    ) {
        val barW = 2.dp.toPx()
        val gap = 1.5.dp.toPx()
        val count = max(1, ((size.width + gap) / (barW + gap)).toInt())
        val bars = cache.bars(count)
        val minH = 2.dp.toPx()
        val split = played * size.width
        for (i in 0 until count) {
            val v = bars?.get(i) ?: 0.18f
            val h = minH + (size.height - minH) * v.coerceIn(0f, 1f)
            val x = i * (barW + gap)
            val color = if (x + barW / 2f <= split) c.accent else c.inkMuted.copy(alpha = 0.35f)
            drawRoundRect(color, Offset(x, (size.height - h) / 2f), Size(barW, h), CornerRadius(barW / 2f))
        }
        // Playhead.
        drawRect(c.accent, Offset((split - 1.dp.toPx() / 2f).coerceIn(0f, size.width - 1.dp.toPx()), 0f), Size(1.dp.toPx(), size.height))
    }
}

/**
 * Who spoke when, as thin coloured segments under the waveform (same timeline). Tapping a
 * segment seeks to the start of that stretch of speech. With [focusLabel] set (the transcript
 * is filtered to one voice) the other voices are dimmed. Colours are resolved once per change,
 * so drawing allocates nothing.
 */
@Composable
internal fun SpeakerLaneBar(
    geometry: LaneGeometry,
    focusLabel: String?,
    onSeek: (Long) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val seek by rememberUpdatedState(onSeek)
    val colors = remember(geometry, c, focusLabel) {
        Array(geometry.size) { i ->
            val base = c.speaker(geometry.colors[i])
            if (focusLabel != null && geometry.labels[i] != focusLabel) base.copy(alpha = 0.22f) else base
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(geometry) {
                detectTapGestures { o ->
                    if (size.width <= 0) return@detectTapGestures
                    val i = SpeakerLanes.hit(geometry, o.x / size.width, slop = 8.dp.toPx() / size.width)
                    if (i >= 0) seek(geometry.startMs[i])
                }
            }
            .semantics { contentDescription = description },
    ) {
        val h = 6.dp.toPx()
        val top = (size.height - h) / 2f
        val minW = 2.dp.toPx()
        val gap = 1.dp.toPx()
        val radius = CornerRadius(h / 2f)
        for (i in 0 until geometry.size) {
            val x0 = geometry.starts[i] * size.width
            val x1 = max(geometry.ends[i] * size.width - gap, x0 + minW)
            drawRoundRect(colors[i], Offset(x0, top), Size(x1 - x0, h), radius)
        }
    }
}

/** Downsampled bars for the last width drawn, so playback ticks never recompute them. */
private class BarCache(private val values: FloatArray?) {
    private var count = -1
    private var bars: FloatArray? = null

    fun bars(n: Int): FloatArray? {
        val v = values?.takeIf { it.isNotEmpty() } ?: return null
        if (n != count) {
            bars = Waveform.downsample(v, n)
            count = n
        }
        return bars
    }
}
