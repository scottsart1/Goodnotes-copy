package com.inkwell.notes.typed.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.typed.TypedDocController
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** A block type choice for "Turn into" and the toolbar's type menu. */
data class BlockChoice(val type: BlockType, val level: Int, @param:StringRes val label: Int)

val blockChoices = listOf(
    BlockChoice(BlockType.PARAGRAPH, 0, R.string.td_block_paragraph),
    BlockChoice(BlockType.HEADING, 1, R.string.td_block_h1),
    BlockChoice(BlockType.HEADING, 2, R.string.td_block_h2),
    BlockChoice(BlockType.HEADING, 3, R.string.td_block_h3),
    BlockChoice(BlockType.BULLET, 0, R.string.td_block_bullet),
    BlockChoice(BlockType.NUMBERED, 0, R.string.td_block_numbered),
    BlockChoice(BlockType.CHECKLIST, 0, R.string.td_block_checklist),
    BlockChoice(BlockType.QUOTE, 0, R.string.td_block_quote),
    BlockChoice(BlockType.CODE, 0, R.string.td_block_code),
)

@StringRes
fun blockLabel(type: BlockType, level: Int): Int = when (type) {
    BlockType.HEADING -> blockChoices.firstOrNull { it.type == type && it.level == level }?.label ?: R.string.td_block_h1
    BlockType.TABLE -> R.string.td_block_table
    BlockType.IMAGE -> R.string.td_block_image
    BlockType.DIVIDER -> R.string.td_block_divider
    else -> blockChoices.first { it.type == type }.label
}

fun BlockChoice.matches(b: TypedBlock): Boolean = b.type == type && (type != BlockType.HEADING || b.level == level)

/**
 * One block in the list: a drag handle (tap for the block menu, drag to reorder) and the
 * block's content.
 */
@Composable
fun BlockRow(
    block: TypedBlock,
    index: Int,
    count: Int,
    number: Int,
    focused: Boolean,
    onlyBlock: Boolean,
    controller: TypedDocController,
    reorder: ReorderState,
    onReplaceImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topSpace = when (block.type) {
        BlockType.HEADING -> if (index == 0) 0.dp else if (block.level == 1) 18.dp else 12.dp
        BlockType.TABLE, BlockType.IMAGE, BlockType.CODE -> 8.dp
        else -> 0.dp
    }
    Row(modifier.fillMaxWidth().padding(top = topSpace, bottom = 2.dp), verticalAlignment = Alignment.Top) {
        BlockHandle(block, index, count, focused, controller, reorder, onReplaceImage)
        Box(Modifier.weight(1f).padding(top = if (block.type == BlockType.CHECKLIST) 0.dp else 9.dp, bottom = 4.dp)) {
            when (block.type) {
                BlockType.TABLE -> TableBlock(block, controller)
                BlockType.IMAGE -> ImageBlock(block, controller)
                BlockType.DIVIDER -> DividerBlock()
                else -> TextBlock(block, number, controller, onlyBlock)
            }
        }
    }
}

@Composable
private fun BlockHandle(
    block: TypedBlock,
    index: Int,
    count: Int,
    focused: Boolean,
    controller: TypedDocController,
    reorder: ReorderState,
    onReplaceImage: (String) -> Unit,
) {
    val c = Inkwell.colors
    var menu by remember { mutableStateOf(false) }
    val desc = stringResource(R.string.td_block_menu)
    Box {
        Box(
            Modifier
                .size(Dimens.minTouch)
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(block.id) {
                    detectDragGestures(
                        onDragStart = { reorder.start(block.id) },
                        onDragEnd = { reorder.end() },
                        onDragCancel = { reorder.end() },
                        onDrag = { change, amount ->
                            change.consume()
                            reorder.drag(amount.y)
                        },
                    )
                }
                .clickable(role = Role.Button) { menu = true }
                .semantics { contentDescription = desc },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DragIndicator,
                null,
                tint = c.inkMuted,
                modifier = Modifier.size(Dimens.icon).alpha(if (focused || reorder.draggingKey == block.id) 0.9f else 0.28f),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
            if (BlockOps.isTextual(block.type)) {
                Text(
                    stringResource(R.string.td_turn_into),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                blockChoices.forEach { choice ->
                    val current = choice.matches(block)
                    MenuItem(if (current) Icons.Outlined.Check else null, stringResource(choice.label)) {
                        menu = false
                        if (!current) controller.turnInto(block.id, choice.type, choice.level)
                    }
                }
                HorizontalDivider(color = c.outline, thickness = 1.dp)
            }
            if (block.type == BlockType.IMAGE) {
                MenuItem(Icons.Outlined.Image, stringResource(R.string.td_replace_image)) { menu = false; onReplaceImage(block.id) }
            }
            MenuItem(Icons.Outlined.ControlPointDuplicate, stringResource(R.string.cd_duplicate)) { menu = false; controller.duplicate(block.id) }
            MenuItem(Icons.Outlined.ArrowUpward, stringResource(R.string.td_move_up), enabled = index > 0) { menu = false; controller.moveBy(block.id, -1) }
            MenuItem(Icons.Outlined.ArrowDownward, stringResource(R.string.td_move_down), enabled = index < count - 1) { menu = false; controller.moveBy(block.id, 1) }
            MenuItem(Icons.Outlined.DeleteOutline, stringResource(R.string.cd_delete)) { menu = false; controller.delete(block.id) }
        }
    }
}

@Composable
fun MenuItem(icon: ImageVector?, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Inkwell.colors
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink) },
        leadingIcon = { if (icon != null) Icon(icon, null, tint = c.inkMuted, modifier = Modifier.size(Dimens.icon)) else Box(Modifier.size(Dimens.icon)) },
        onClick = onClick,
        enabled = enabled,
    )
}

/** Lifted look of the block being dragged. */
fun Modifier.dragOutline(active: Boolean, color: androidx.compose.ui.graphics.Color): Modifier =
    if (active) this.border(1.dp, color, RoundedCornerShape(8.dp)) else this
