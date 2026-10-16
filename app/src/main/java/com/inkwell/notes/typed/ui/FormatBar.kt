package com.inkwell.notes.typed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.typed.TypedDocController
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.typed.model.InlineStyle
import com.inkwell.notes.typed.model.OutlineItem
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.PillDivider

/**
 * Formatting toolbar: block type, inline styles (applied to the selection, or to what is typed
 * next when nothing is selected), link, indentation and inserts.
 */
@Composable
fun FormatBar(
    controller: TypedDocController,
    onLink: () -> Unit,
    onOpenLink: (String) -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val focused = controller.focusedBlock
    val textual = focused != null && BlockOps.isTextual(focused.type)
    val spans = focused != null && BlockOps.supportsSpans(focused.type)
    // Read the focused field so active states follow the selection.
    focused?.let { controller.fieldFor(it).value }
    controller.pendingMarks
    var typeMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                val label = focused?.let { stringResource(blockLabel(it.type, it.level)) } ?: stringResource(R.string.td_block_type)
                Row(
                    Modifier
                        .height(Dimens.minTouch)
                        .widthIn(min = 120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = textual, role = Role.DropdownList) { typeMenu = true }
                        .alpha(if (textual) 1f else 0.4f)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = c.inkMuted, modifier = Modifier.size(Dimens.icon))
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }, containerColor = c.surface) {
                    blockChoices.forEach { choice ->
                        val current = focused != null && choice.matches(focused)
                        MenuItem(if (current) Icons.Outlined.Check else null, stringResource(choice.label)) {
                            typeMenu = false
                            focused?.let { controller.turnInto(it.id, choice.type, choice.level) }
                        }
                    }
                }
            }
            PillDivider()
            StyleButton(controller, InlineStyle.BOLD, Icons.Outlined.FormatBold, R.string.td_bold, spans)
            StyleButton(controller, InlineStyle.ITALIC, Icons.Outlined.FormatItalic, R.string.td_italic, spans)
            StyleButton(controller, InlineStyle.UNDERLINE, Icons.Outlined.FormatUnderlined, R.string.td_underline, spans)
            StyleButton(controller, InlineStyle.STRIKE, Icons.Outlined.FormatStrikethrough, R.string.td_strike, spans)
            StyleButton(controller, InlineStyle.CODE, Icons.Outlined.Code, R.string.td_code_inline, spans)
            val link = if (spans) controller.linkAtCaret() else null
            KitIconButton(Icons.Outlined.Link, stringResource(R.string.td_link), onLink, enabled = spans, selected = link != null)
            if (link != null) KitIconButton(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.td_open_link), { onOpenLink(link) })
            PillDivider()
            val list = focused != null && (BlockOps.isList(focused.type) || focused.type == BlockType.CODE)
            KitIconButton(Icons.AutoMirrored.Outlined.FormatIndentDecrease, stringResource(R.string.td_outdent), { controller.indent(-1) }, enabled = list && focused.type != BlockType.CODE && focused.level > 0)
            KitIconButton(Icons.AutoMirrored.Outlined.FormatIndentIncrease, stringResource(R.string.td_indent), { controller.indent(1) }, enabled = list)
            PillDivider()
            KitIconButton(Icons.Outlined.TableChart, stringResource(R.string.td_insert_table), { controller.insertBlock(BlockType.TABLE) })
            KitIconButton(Icons.Outlined.Image, stringResource(R.string.td_insert_image), onPickImage)
            KitIconButton(Icons.Outlined.HorizontalRule, stringResource(R.string.td_insert_divider), { controller.insertBlock(BlockType.DIVIDER) })
        }
        HorizontalDivider(thickness = 1.dp, color = c.outline)
    }
}

@Composable
private fun StyleButton(controller: TypedDocController, style: InlineStyle, icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean) {
    KitIconButton(icon, stringResource(label), { controller.toggleStyle(style) }, enabled = enabled, selected = enabled && controller.isStyleActive(style))
}

/** Headings of the document; tapping one jumps to it. */
@Composable
fun OutlinePanel(entries: List<OutlineItem>, onJump: (OutlineItem) -> Unit, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Column(modifier.background(c.surface)) {
        Text(
            stringResource(R.string.td_outline),
            style = MaterialTheme.typography.titleSmall,
            color = c.inkMuted,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        if (entries.isEmpty()) {
            Text(stringResource(R.string.td_outline_empty), style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(entries, key = { it.blockId }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.minTouch)
                        .clickable(role = Role.Button) { onJump(e) }
                        .padding(start = 20.dp + 14.dp * (e.level - 1), end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.text,
                        style = if (e.level == 1) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyMedium,
                        color = if (e.level == 1) c.ink else c.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Edits the link of the selection (or of the link under the caret). */
@Composable
fun LinkDialog(initialUrl: String?, onApply: (String) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun apply() {
        if (!TypedDocController.isPlausibleUrl(url)) { invalid = true; return }
        onApply(url.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.ink,
        title = { Text(stringResource(R.string.td_link_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.replace("\n", ""); invalid = false },
                label = { Text(stringResource(R.string.td_link_url)) },
                singleLine = true,
                isError = invalid,
                supportingText = if (invalid) { { Text(stringResource(R.string.td_link_invalid)) } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent,
                    focusedLabelColor = c.accent, unfocusedLabelColor = c.inkMuted, focusedTextColor = c.ink, unfocusedTextColor = c.ink,
                    errorBorderColor = c.danger, errorLabelColor = c.danger, errorSupportingTextColor = c.danger,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { apply() }) { Text(stringResource(R.string.td_link_apply), color = c.accent) } },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.td_link_remove), color = c.danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_cancel), color = c.inkMuted) }
            }
        },
    )
}
