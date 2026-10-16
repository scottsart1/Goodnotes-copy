package com.inkwell.notes.whiteboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import com.inkwell.notes.R
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.MinimapImage
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.PillDivider
import com.inkwell.notes.whiteboard.kit.PillSurface
import com.inkwell.notes.whiteboard.logic.Box as BoardBox
import com.inkwell.notes.whiteboard.logic.MinimapMath
import kotlin.math.roundToInt

/**
 * Bottom-right overview: all content plus the current viewport rectangle. Tap or drag to
 * move around the board. Collapses to a single map button.
 */
@Composable
fun Minimap(
    image: MinimapImage?,
    contentBox: BoardBox?,
    visible: BoardBox?,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onCenterOn: (Float, Float) -> Unit,
    onSizePx: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (collapsed) {
        PillSurface(modifier, shape = RoundedCornerShape(12.dp)) {
            KitIconButton(Icons.Outlined.Map, stringResource(R.string.wb_minimap_show), { onCollapsedChange(false) })
        }
        return
    }
    val c = Inkwell.colors
    val current by rememberUpdatedState(Triple(image, contentBox, visible))
    val centerOn by rememberUpdatedState(onCenterOn)
    val description = stringResource(R.string.wb_minimap)
    PillSurface(modifier.size(200.dp, 140.dp), shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceAlt)
                    .onSizeChanged(onSizePx)
                    .semantics { contentDescription = description }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val (_, content, vis) = current
                            // Freeze the mapping for the whole gesture: the world grows with the viewport.
                            val t = MinimapMath.transform(MinimapMath.world(content, vis), size.width.toFloat(), size.height.toFloat(), INSET_PX)
                            val v = vis?.let { MinimapMath.viewportOnMap(t, it) }
                            val startX: Float
                            val startY: Float
                            if (vis != null && v != null && v.contains(down.position.x, down.position.y)) {
                                startX = vis.centerX; startY = vis.centerY
                            } else {
                                val (x, y) = MinimapMath.panTarget(t, down.position.x, down.position.y)
                                startX = x; startY = y
                                centerOn(x, y)
                            }
                            down.consume()
                            var dx = 0f
                            var dy = 0f
                            drag(down.id) { change ->
                                val d = change.positionChange()
                                dx += d.x; dy += d.y
                                change.consume()
                                val (cx, cy) = MinimapMath.dragCenter(t, startX, startY, dx, dy)
                                centerOn(cx, cy)
                            }
                        }
                    },
            ) {
                val t = MinimapMath.transform(MinimapMath.world(contentBox ?: image?.bounds, visible), size.width, size.height, INSET_PX)
                val img = image
                if (img != null) {
                    val r = t.toMap(img.bounds)
                    drawImage(
                        img.image,
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                    )
                }
                if (visible != null) {
                    val r = MinimapMath.viewportOnMap(t, visible)
                    val left = r.left.coerceIn(0f, size.width)
                    val top = r.top.coerceIn(0f, size.height)
                    val right = r.right.coerceIn(0f, size.width)
                    val bottom = r.bottom.coerceIn(0f, size.height)
                    if (right > left && bottom > top) {
                        drawRect(c.accent.copy(alpha = 0.10f), Offset(left, top), Size(right - left, bottom - top))
                        drawRect(c.accent, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
            KitIconButton(
                Icons.Outlined.CloseFullscreen,
                stringResource(R.string.wb_minimap_hide),
                { onCollapsedChange(true) },
                modifier = Modifier.align(Alignment.TopEnd),
                size = 36.dp,
                tint = c.inkMuted,
            )
        }
    }
}

private const val INSET_PX = 10f

/** Bottom-left zoom controls: minus, percentage (tap for 100%), plus, fit content. */
@Composable
fun ZoomControls(
    zoom: Float,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    PillSurface(modifier, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            KitIconButton(Icons.Outlined.Remove, stringResource(R.string.wb_zoom_out), onZoomOut)
            val label = stringResource(R.string.wb_zoom_value, MinimapMath.percent(zoom))
            val resetLabel = stringResource(R.string.wb_zoom_reset)
            Box(
                Modifier
                    .widthIn(min = 52.dp)
                    .size(height = Dimens.minTouch, width = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = resetLabel, onClick = onReset)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink)
            }
            KitIconButton(Icons.Outlined.Add, stringResource(R.string.wb_zoom_in), onZoomIn)
            PillDivider()
            KitIconButton(Icons.Outlined.FitScreen, stringResource(R.string.wb_fit_content), onFit)
        }
    }
}
