package com.inkwell.notes.typed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.typed.TypedDocController
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/**
 * A text block: paragraph, heading, list item (bullet, number or checkbox), quote or code.
 * Inline spans are drawn through [SpanTransformation]; every edit goes to the controller.
 */
@Composable
fun TextBlock(block: TypedBlock, number: Int, controller: TypedDocController, onlyBlock: Boolean) {
    val c = Inkwell.colors
    val density = LocalDensity.current
    val holder = controller.fieldFor(block)
    val raw = holder.value
    val value = if (raw.text == block.text) raw else TextFieldValue(block.text, TextRange(raw.selection.end.coerceIn(0, block.text.length)))
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val request = controller.focusRequest
    LaunchedEffect(request) {
        if (request != null && request.blockId == block.id && request.cell == null) {
            runCatching { focus.requestFocus() }
            controller.consumeFocus(request)
        }
    }
    val style = blockTextStyle(block)
    val lineHeightDp = with(density) { style.lineHeight.toDp() }
    val supports = BlockOps.supportsSpans(block.type)
    val spanColors = SpanColors(c.accent, c.surfaceAlt)
    val transformation = remember(block.spans, spanColors, supports) {
        if (supports && block.spans.isNotEmpty()) SpanTransformation(block.spans, spanColors) else VisualTransformation.None
    }
    val placeholder = when {
        onlyBlock && block.type == BlockType.PARAGRAPH -> stringResource(R.string.td_placeholder_first)
        !focused -> null
        block.type == BlockType.HEADING -> stringResource(R.string.td_placeholder_heading)
        BlockOps.isList(block.type) -> stringResource(R.string.td_placeholder_list)
        block.type == BlockType.QUOTE -> stringResource(R.string.td_placeholder_quote)
        block.type == BlockType.CODE -> stringResource(R.string.td_placeholder_code)
        else -> stringResource(R.string.td_placeholder_paragraph)
    }
    val checkboxTarget = Dimens.minTouch
    val topPad = if (block.type == BlockType.CHECKLIST) ((checkboxTarget - lineHeightDp) / 2).coerceAtLeast(0.dp) else 0.dp

    val field: @Composable (Modifier) -> Unit = { modifier ->
        BasicTextField(
            value = value,
            onValueChange = { controller.onFieldChange(block.id, it) },
            textStyle = style,
            visualTransformation = transformation,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = if (block.type == BlockType.CODE) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                autoCorrectEnabled = block.type != BlockType.CODE,
            ),
            onTextLayout = { layout = it },
            modifier = modifier
                .focusRequester(focus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) controller.onFocused(block.id)
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val sel = controller.selectionOf(block.id)
                    val l = layout
                    when (e.key) {
                        Key.Backspace -> sel.collapsed && sel.start == 0 && controller.backspaceAtStart(block.id)
                        Key.DirectionUp -> !e.isShiftPressed && l != null && l.lineCount > 0 &&
                            l.getLineForOffset(sel.start.coerceIn(0, block.text.length)) == 0 && controller.focusAdjacent(block.id, -1)
                        Key.DirectionDown -> !e.isShiftPressed && l != null && l.lineCount > 0 &&
                            l.getLineForOffset(sel.end.coerceIn(0, block.text.length)) == l.lineCount - 1 && controller.focusAdjacent(block.id, 1)
                        Key.DirectionLeft -> !e.isShiftPressed && sel.collapsed && sel.start == 0 && controller.focusAdjacent(block.id, -1)
                        Key.DirectionRight -> !e.isShiftPressed && sel.collapsed && sel.start == block.text.length && controller.focusAdjacent(block.id, 1)
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box {
                    if (block.text.isEmpty() && placeholder != null) {
                        Text(placeholder, style = style.copy(color = c.inkMuted.copy(alpha = 0.6f), textDecoration = null), maxLines = 2)
                    }
                    inner()
                }
            },
        )
    }

    when (block.type) {
        BlockType.CODE -> CodeFrame(block, controller) { field(Modifier.fillMaxWidth()) }
        BlockType.QUOTE -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.outline))
            Spacer(Modifier.width(14.dp))
            field(Modifier.weight(1f))
        }
        BlockType.BULLET, BlockType.NUMBERED, BlockType.CHECKLIST -> Row(
            Modifier.fillMaxWidth().padding(start = 24.dp * block.level.coerceIn(0, BlockOps.MAX_INDENT)),
            verticalAlignment = Alignment.Top,
        ) {
            when (block.type) {
                BlockType.CHECKLIST -> CheckBox(block.checked, checkboxTarget) { controller.toggleChecked(block.id) }
                BlockType.NUMBERED -> Box(Modifier.widthIn(min = 28.dp).height(lineHeightDp).padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(BlockOps.numberLabel(number.coerceAtLeast(1), block.level), style = style.copy(color = c.inkMuted, textDecoration = null))
                }
                else -> Box(Modifier.width(28.dp).height(lineHeightDp), contentAlignment = Alignment.Center) {
                    Text(BlockOps.bulletGlyph(block.level), style = style.copy(color = c.inkMuted))
                }
            }
            Spacer(Modifier.width(4.dp))
            field(Modifier.weight(1f).padding(top = topPad))
        }
        else -> field(Modifier.fillMaxWidth())
    }
}

@Composable
private fun CheckBox(checked: Boolean, target: androidx.compose.ui.unit.Dp, onToggle: () -> Unit) {
    val c = Inkwell.colors
    val desc = stringResource(if (checked) R.string.td_checked else R.string.td_unchecked)
    Box(
        Modifier
            .size(target)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { stateDescription = desc; contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) c.accent else c.surface)
                .border(1.5.dp, if (checked) c.accent else c.inkMuted, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Outlined.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
        }
    }
}

/** Code block chrome: tinted rounded panel with an editable language label. */
@Composable
private fun CodeFrame(block: TypedBlock, controller: TypedDocController, content: @Composable () -> Unit) {
    val c = Inkwell.colors
    var lang by remember(block.id) { mutableStateOf(block.language.orEmpty()) }
    LaunchedEffect(block.language) { if ((block.language ?: "") != lang.trim()) lang = block.language.orEmpty() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.surfaceAlt)
            .border(1.dp, c.outline, RoundedCornerShape(8.dp))
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            BasicTextField(
                value = lang,
                onValueChange = { v ->
                    val clean = v.replace("\n", "").take(24)
                    lang = clean
                    controller.setLanguage(block.id, clean)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(color = c.inkMuted),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.widthIn(min = 64.dp, max = 160.dp).padding(vertical = 4.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (lang.isEmpty()) Text(stringResource(R.string.td_language_placeholder), style = MaterialTheme.typography.labelMedium, color = c.inkMuted.copy(alpha = 0.6f))
                        inner()
                    }
                },
            )
        }
        content()
    }
}
