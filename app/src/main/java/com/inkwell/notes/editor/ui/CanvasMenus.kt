package com.inkwell.notes.editor.ui

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.editor.ImagePlacement
import com.inkwell.notes.editor.LongPressRequest
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.logic.PageRanges
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.roundToInt

/** The menu a long press on the page opens (per Settings > Pen & input > Long press). */
@Composable
fun LongPressMenu(controller: NotebookController, req: LongPressRequest, actions: PaneActions) {
    val canPaste = remember(req) { controller.hasSomethingToPaste() }
    val dismiss = { controller.longPress = null }
    PointAnchor(req.viewX.roundToInt(), req.viewY.roundToInt()) {
        DropdownMenu(expanded = true, onDismissRequest = dismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_paste)) },
                leadingIcon = { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(Dimens.icon)) },
                enabled = canPaste,
                onClick = {
                    dismiss()
                    controller.paste(req.pageIndex, PointF(req.pageX, req.pageY))
                },
            )
            if (!req.pasteOnly) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_add_text)) },
                    leadingIcon = { Icon(Icons.Outlined.TextFields, null, Modifier.size(Dimens.icon)) },
                    onClick = {
                        dismiss()
                        actions.startTextAt(req.pageIndex, req.pageX, req.pageY, req.viewX, req.viewY)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_insert_image)) },
                    leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(Dimens.icon)) },
                    onClick = {
                        dismiss()
                        controller.imagePlacement = ImagePlacement(req.pageIndex, PointF(req.pageX, req.pageY))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_add_page_after)) },
                    leadingIcon = { Icon(Icons.Outlined.PostAdd, null, Modifier.size(Dimens.icon)) },
                    onClick = {
                        dismiss()
                        controller.addPage(PageRanges.insertionIndex(req.pageIndex, after = true, count = controller.pages.size))
                    },
                )
            }
        }
    }
}

/**
 * Ways out of focus mode: a small handle in the top-right corner and a swipe down from the
 * top edge. Both bring the controls back.
 */
@Composable
fun FocusModeExits(onExit: () -> Unit) {
    val c = Inkwell.colors
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    val label = stringResource(R.string.editor_exit_focus)
    Box(Modifier.fillMaxSize()) {
        // Thin strip at the top edge: a downward swipe exits.
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { change, amount ->
                            total += amount
                            if (total > threshold) {
                                change.consume()
                                onExit()
                            }
                        },
                    )
                },
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(Dimens.minTouch)
                .clickable(role = Role.Button, onClick = onExit)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .alpha(0.7f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.FullscreenExit, null, Modifier.size(16.dp), tint = c.inkMuted)
            }
        }
    }
}
