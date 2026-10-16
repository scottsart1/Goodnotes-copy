package com.inkwell.notes.whiteboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.kit.DarkToast
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.PillDivider
import com.inkwell.notes.whiteboard.kit.PillSurface
import com.inkwell.notes.whiteboard.logic.Box as BoardBox
import androidx.compose.material3.Icon
import com.inkwell.notes.ui.theme.Dimens

enum class ExportAction { SHARE, SAVE }

/**
 * Covers the canvas while choosing an area to export: drag a rectangle (finger or pen),
 * then share or save it as PNG. The chosen rectangle is in canvas-view pixels.
 */
@Composable
fun ExportAreaOverlay(busy: Boolean, onExport: (BoardBox, ExportAction) -> Unit, onCancel: () -> Unit) {
    val c = Inkwell.colors
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    BackHandler(onBack = onCancel)
    val rect = run {
        val s = start
        val e = end
        if (s == null || e == null) null else BoardBox.of(s.x, s.y, e.x, e.y)
    }
    val valid = rect != null && rect.width > 24f && rect.height > 24f
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        start = down.position
                        end = down.position
                        drag(down.id) { change ->
                            change.consume()
                            end = change.position
                        }
                    }
                },
        ) {
            val scrim = c.ink.copy(alpha = 0.28f)
            val r = rect
            if (r == null) {
                drawRect(scrim)
            } else {
                clipRect(r.left, r.top, r.right, r.bottom, clipOp = ClipOp.Difference) { drawRect(scrim) }
                drawRect(
                    c.accent, Offset(r.left, r.top), Size(r.width, r.height),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))),
                )
            }
        }
        DarkToast(stringResource(R.string.wb_export_area_hint), Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
        PillSurface(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                KitIconButton(Icons.Outlined.Close, stringResource(R.string.cd_cancel), onCancel)
                PillDivider()
                ActionLabel(Icons.Outlined.Share, stringResource(R.string.wb_export_share), enabled = valid && !busy) {
                    rect?.let { onExport(it, ExportAction.SHARE) }
                }
                ActionLabel(Icons.Outlined.SaveAlt, stringResource(R.string.wb_export_save), enabled = valid && !busy) {
                    rect?.let { onExport(it, ExportAction.SAVE) }
                }
            }
        }
    }
}

@Composable
private fun ActionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier
            .height(Dimens.minTouch)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.padding(end = 6.dp).width(Dimens.icon).height(Dimens.icon), tint = c.accent)
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink)
        Spacer(Modifier.width(2.dp))
    }
}
