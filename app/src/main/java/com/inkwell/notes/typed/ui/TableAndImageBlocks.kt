package com.inkwell.notes.typed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.inkwell.notes.R
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.typed.TypedDocController
import com.inkwell.notes.typed.model.TableOps
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import java.io.File

/** An editable table: the first row is the header; a small toolbar appears while a cell is focused. */
@Composable
fun TableBlock(block: TypedBlock, controller: TypedDocController) {
    val c = Inkwell.colors
    val grid = remember(block.rows) { TableOps.normalize(block.rows) }
    val columns = grid[0].size
    val focusedCell = controller.focusedCell?.takeIf { it.blockId == block.id }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minCell = 120.dp
            val fits = maxWidth >= minCell * columns
            val cellWidth = if (fits) maxWidth / columns else 160.dp
            Box(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, c.outline, RoundedCornerShape(8.dp)),
                ) {
                    grid.forEachIndexed { r, row ->
                        Row(Modifier.height(IntrinsicSize.Min).background(if (r == 0) c.surfaceAlt else c.surface)) {
                            row.forEachIndexed { col, text ->
                                TableCell(block.id, r, col, text, header = r == 0, width = cellWidth, controller = controller)
                                if (col < columns - 1) VerticalDivider(thickness = 1.dp, color = c.outline, modifier = Modifier.fillMaxHeight())
                            }
                        }
                        if (r < grid.lastIndex) HorizontalDivider(thickness = 1.dp, color = c.outline, modifier = Modifier.width(cellWidth * columns + 1.dp * (columns - 1)))
                    }
                }
            }
        }
        if (focusedCell != null) {
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TableAction(Icons.Outlined.Add, stringResource(R.string.td_table_add_row)) {
                    controller.tableEdit(block.id, { TableOps.insertRow(it, focusedCell.row + 1) }, focus = focusedCell.row + 1 to focusedCell.column)
                }
                TableAction(Icons.Outlined.Add, stringResource(R.string.td_table_add_column)) {
                    controller.tableEdit(block.id, { TableOps.insertColumn(it, focusedCell.column + 1) }, focus = focusedCell.row to focusedCell.column + 1)
                }
                TableAction(Icons.Outlined.Remove, stringResource(R.string.td_table_remove_row), enabled = grid.size > 1) {
                    controller.tableEdit(block.id, { TableOps.removeRow(it, focusedCell.row) }, focus = (focusedCell.row - 1).coerceAtLeast(0) to focusedCell.column)
                }
                TableAction(Icons.Outlined.Remove, stringResource(R.string.td_table_remove_column), enabled = columns > 1) {
                    controller.tableEdit(block.id, { TableOps.removeColumn(it, focusedCell.column) }, focus = focusedCell.row to (focusedCell.column - 1).coerceAtLeast(0))
                }
            }
        }
    }
}

@Composable
private fun TableCell(blockId: String, row: Int, column: Int, text: String, header: Boolean, width: androidx.compose.ui.unit.Dp, controller: TypedDocController) {
    val c = Inkwell.colors
    var value by remember(blockId, row, column) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    }
    val focus = remember { FocusRequester() }
    val request = controller.focusRequest
    LaunchedEffect(request) {
        if (request != null && request.blockId == blockId && request.cell == (row to column)) {
            runCatching { focus.requestFocus() }
            controller.consumeFocus(request)
        }
    }
    val desc = stringResource(R.string.td_table_cell, row + 1, column + 1)
    BasicTextField(
        value = value,
        onValueChange = { v ->
            if (v.text.contains('\n')) {
                val clean = v.text.replace("\n", "")
                value = TextFieldValue(clean, TextRange(clean.length))
                controller.setCell(blockId, row, column, clean)
                controller.nextCell(blockId, row, column, downwards = true)
            } else {
                value = v
                controller.setCell(blockId, row, column, v.text)
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier
            .width(width)
            .heightIn(min = Dimens.minTouch)
            .padding(horizontal = 10.dp, vertical = 11.dp)
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) controller.onCellFocused(blockId, row, column) }
            .semantics { contentDescription = desc },
    )
}

@Composable
private fun TableAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, c.outline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink)
    }
}

/** An image block: the picture (decoded off the main thread by Coil) and an editable caption. */
@Composable
fun ImageBlock(block: TypedBlock, controller: TypedDocController) {
    val c = Inkwell.colors
    val context = LocalContext.current
    // Path only: FileStore's accessors create folders, which is disk I/O we keep off Main.
    val file = remember(block.assetId) {
        block.assetId?.let { File(File(File(controller.graph.files.root, FileStore.safe(controller.docId)), "assets"), FileStore.safe(it)) }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (file == null) {
            MissingImage()
        } else {
            SubcomposeAsyncImage(
                model = remember(file) { ImageRequest.Builder(context).data(file).crossfade(false).build() },
                contentDescription = block.text.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(8.dp)),
                loading = {
                    Box(Modifier.fillMaxWidth().height(160.dp).background(c.surfaceAlt), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.inkMuted, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                },
                error = { MissingImage() },
            )
        }
        var caption by remember(block.id) { mutableStateOf(TextFieldValue(block.text, TextRange(block.text.length))) }
        LaunchedEffect(block.text) { if (caption.text != block.text) caption = TextFieldValue(block.text, TextRange(block.text.length)) }
        val style = blockTextStyle(block).copy(textAlign = TextAlign.Center)
        BasicTextField(
            value = caption,
            onValueChange = { v ->
                val clean = v.text.replace('\n', ' ')
                caption = if (clean == v.text) v else TextFieldValue(clean, TextRange(clean.length))
                controller.setCaption(block.id, clean)
            },
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 28.dp)
                .onFocusChanged { if (it.isFocused) controller.onFocused(block.id) },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (caption.text.isEmpty()) Text(stringResource(R.string.td_caption_placeholder), style = style.copy(color = c.inkMuted.copy(alpha = 0.6f)))
                    inner()
                }
            },
        )
    }
}

@Composable
private fun MissingImage() {
    val c = Inkwell.colors
    Box(
        Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt).border(1.dp, c.outline, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.BrokenImage, null, tint = c.inkMuted, modifier = Modifier.size(Dimens.icon))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.td_image_missing), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
    }
}

/** A horizontal rule. */
@Composable
fun DividerBlock() {
    Box(Modifier.fillMaxWidth().height(Dimens.minTouch), contentAlignment = Alignment.Center) {
        HorizontalDivider(thickness = 1.dp, color = Inkwell.colors.outline)
    }
}
