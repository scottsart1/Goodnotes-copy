package com.inkwell.notes.whiteboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.ink.InkSelection
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.LongPressRequest
import com.inkwell.notes.whiteboard.kit.ColorSwatch
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.PillDivider
import com.inkwell.notes.whiteboard.kit.PillSurface
import kotlin.math.roundToInt

class SelectionActions(
    val onCopy: () -> Unit,
    val onCut: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onRecolor: (Int) -> Unit,
    val onFront: () -> Unit,
    val onBack: () -> Unit,
    val onGroup: () -> Unit,
    val onUngroup: () -> Unit,
    val onSendToNotebook: () -> Unit,
    val onAskAi: () -> Unit,
    /** Set when the selection was written during a recording: plays it from that moment. */
    val onPlayFromHere: (() -> Unit)? = null,
)

/** Context pill for a selection, placed above it (or below when there is no room). */
@Composable
fun SelectionBar(selection: InkSelection, actions: SelectionActions, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    val topReserve = with(density) { 64.dp.roundToPx() } // keep clear of the tool pill
    val bounds = selection.boundsView
    var colorsOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        PillSurface(
            Modifier.layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val cx = bounds.centerX().roundToInt() - p.width / 2
                    val above = bounds.top.roundToInt() - gap - p.height
                    val below = bounds.bottom.roundToInt() + gap
                    val y = when {
                        above >= topReserve -> above
                        below + p.height <= constraints.maxHeight - margin -> below
                        else -> (constraints.maxHeight - p.height - margin * 10).coerceAtLeast(topReserve)
                    }
                    val x = cx.coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin))
                    p.place(x, y)
                }
            },
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                KitIconButton(Icons.Outlined.ContentCopy, stringResource(R.string.wb_sel_copy), actions.onCopy)
                KitIconButton(Icons.Outlined.ContentCut, stringResource(R.string.wb_sel_cut), actions.onCut)
                KitIconButton(Icons.Outlined.ControlPointDuplicate, stringResource(R.string.wb_sel_duplicate), actions.onDuplicate)
                KitIconButton(Icons.Outlined.DeleteOutline, stringResource(R.string.wb_sel_delete), actions.onDelete)
                PillDivider()
                Box {
                    KitIconButton(Icons.Outlined.Palette, stringResource(R.string.wb_sel_color), { colorsOpen = true })
                    DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }, containerColor = Inkwell.colors.surface) {
                        Row(Modifier.padding(horizontal = 6.dp)) {
                            BoardPalettes.pen.forEach { nc ->
                                ColorSwatch(nc.argb, false, stringResource(nc.name), { colorsOpen = false; actions.onRecolor(nc.argb) }, diameter = 22.dp)
                            }
                        }
                    }
                }
                actions.onPlayFromHere?.let { play ->
                    KitIconButton(Icons.Outlined.PlayArrow, stringResource(R.string.wb_audio_play_from_here), play)
                }
                KitIconButton(Icons.Outlined.AutoAwesome, stringResource(R.string.wb_ai), actions.onAskAi)
                Box {
                    KitIconButton(Icons.Outlined.MoreHoriz, stringResource(R.string.wb_sel_more), { moreOpen = true })
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, containerColor = Inkwell.colors.surface) {
                        MenuRow(Icons.Outlined.FlipToFront, R.string.wb_sel_front) { moreOpen = false; actions.onFront() }
                        MenuRow(Icons.Outlined.FlipToBack, R.string.wb_sel_back) { moreOpen = false; actions.onBack() }
                        MenuRow(Icons.Outlined.GroupWork, R.string.wb_sel_group) { moreOpen = false; actions.onGroup() }
                        MenuRow(Icons.Outlined.LayersClear, R.string.wb_sel_ungroup) { moreOpen = false; actions.onUngroup() }
                        MenuRow(Icons.AutoMirrored.Outlined.NoteAdd, R.string.wb_send_to_notebook) { moreOpen = false; actions.onSendToNotebook() }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector?, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Inkwell.colors
    DropdownMenuItem(
        text = { Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = c.ink) },
        leadingIcon = icon?.let { { Icon(it, null, tint = c.inkMuted, modifier = Modifier.size(Dimens.icon)) } },
        onClick = onClick,
        enabled = enabled,
    )
}

/** Long-press menu on the empty board: paste, add a sticky/text/image there, select all. */
@Composable
fun LongPressMenu(
    request: LongPressRequest,
    canPaste: Boolean,
    onPaste: () -> Unit,
    onSticky: () -> Unit,
    onText: () -> Unit,
    onImage: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.offset { IntOffset(request.viewX.roundToInt(), request.viewY.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss, containerColor = Inkwell.colors.surface) {
            MenuRow(Icons.Outlined.ContentPaste, R.string.wb_paste, enabled = canPaste) { onDismiss(); onPaste() }
            MenuRow(Icons.AutoMirrored.Outlined.StickyNote2, R.string.wb_add_sticky_here) { onDismiss(); onSticky() }
            MenuRow(Icons.Outlined.TextFields, R.string.wb_add_text_here) { onDismiss(); onText() }
            MenuRow(Icons.Outlined.Image, R.string.wb_add_image_here) { onDismiss(); onImage() }
            MenuRow(Icons.Outlined.SelectAll, R.string.wb_select_all) { onDismiss(); onSelectAll() }
        }
    }
}
