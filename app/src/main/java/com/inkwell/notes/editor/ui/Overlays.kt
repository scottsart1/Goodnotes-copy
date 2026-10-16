package com.inkwell.notes.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.editor.ui.components.EditorIcons
import com.inkwell.notes.editor.ui.toolbar.toolLabel
import com.inkwell.notes.ui.theme.Inkwell

/**
 * "Side button held · Eraser": a small dark pill near the top while the S Pen button
 * temporarily switches the tool. Fades in and out in 120ms.
 */
@Composable
fun TemporaryToolToast(tool: ToolType?, modifier: Modifier = Modifier) {
    // Keep the last tool while fading out so the label does not jump (plain holder, not state).
    val last = remember { arrayOfNulls<ToolType>(1) }
    if (tool != null) last[0] = tool
    val shown = tool ?: last[0] ?: ToolType.ERASER
    val c = Inkwell.colors
    val bg = if (c.isDark) c.surfaceAlt else c.ink
    val fg = if (c.isDark) c.ink else c.surface
    AnimatedVisibility(
        visible = tool != null,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .then(if (c.isDark) Modifier.border(1.dp, c.outline, RoundedCornerShape(16.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(EditorIcons.tool(shown), null, Modifier.size(16.dp), tint = fg)
            Text(
                stringResource(R.string.editor_side_button_held, stringResource(toolLabel(shown))),
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Progress of background work (OCR, AI, imports). Never blocks the canvas. */
@Composable
fun BusyPill(labels: List<String>, modifier: Modifier = Modifier) {
    val label = labels.lastOrNull()
    val holder = remember { arrayOf("") }
    if (label != null) holder[0] = label
    val last = holder[0]
    val c = Inkwell.colors
    AnimatedVisibility(visible = label != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)), modifier = modifier) {
        Row(
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .border(1.dp, c.outline, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent, trackColor = c.surfaceAlt)
            Text(
                if (labels.size > 1) "$last (+${labels.size - 1})" else last,
                style = MaterialTheme.typography.labelMedium,
                color = c.ink,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Measures [content] and places it at the offset [place] returns for its size and the
 * container size. Fills the container but does not consume touches outside the content.
 */
@Composable
fun PlacedOverlay(
    place: (contentW: Int, contentH: Int, containerW: Int, containerH: Int) -> IntOffset,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        layout(w, h) {
            placeables.forEach { p -> p.place(place(p.width, p.height, w, h)) }
        }
    }
}

/** A zero-size anchor at a point, for menus that open where the user pressed. */
@Composable
fun PointAnchor(x: Int, y: Int, content: @Composable () -> Unit) {
    PlacedOverlay(place = { _, _, _, _ -> IntOffset(x, y) }) {
        Box(Modifier.size(1.dp)) { content() }
    }
}
