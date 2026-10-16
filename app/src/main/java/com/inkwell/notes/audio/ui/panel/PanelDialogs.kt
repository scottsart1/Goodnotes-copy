package com.inkwell.notes.audio.ui.panel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.inkwell.notes.R
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.logic.MarkdownBlocks
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.ui.theme.Inkwell
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString

/** All dialogs of the panel, driven by the view model. */
@Composable
internal fun PanelDialogs(vm: RecordingsViewModel) {
    vm.pendingDelete?.let { rec ->
        AudioConfirmDialog(
            title = stringResource(R.string.audio_delete_title),
            body = stringResource(R.string.audio_delete_body, rec.title),
            confirm = stringResource(R.string.audio_delete),
            danger = true,
            onConfirm = { vm.confirmDelete() },
            onDismiss = { vm.pendingDelete = null },
        )
    }
    vm.consent?.let { c ->
        val provider = c.provider.displayName
        when (c) {
            is Consent.Transcribe -> AudioConfirmDialog(
                title = stringResource(R.string.audio_send_audio_title, provider),
                body = stringResource(
                    R.string.audio_send_audio_body, c.minutes, provider,
                    c.cost?.let { stringResource(R.string.audio_estimate_sentence, it) }.orEmpty(),
                ).trim(),
                confirm = stringResource(R.string.audio_send),
                offerDontAskAgain = true,
                onConfirm = { vm.acceptConsent(it) },
                onDismiss = { vm.dismissConsent() },
            )
            is Consent.Ask -> AudioConfirmDialog(
                title = stringResource(R.string.audio_ai_send_title, provider),
                body = stringResource(if (c.withNotes) R.string.audio_ai_send_body_notes else R.string.audio_ai_send_body, provider),
                confirm = stringResource(R.string.audio_send),
                offerDontAskAgain = true,
                onConfirm = { vm.acceptConsent(it) },
                onDismiss = { vm.dismissConsent() },
            )
        }
    }
    vm.largeRequest?.let { p ->
        AudioConfirmDialog(
            title = stringResource(R.string.audio_ai_large_title),
            body = stringResource(
                R.string.audio_ai_large_body,
                AudioText.tokens(p.estimatedTokens),
                AudioText.usd(p.estimatedCostUsd) ?: stringResource(R.string.audio_price_unknown),
            ),
            confirm = stringResource(R.string.audio_send),
            onConfirm = { vm.confirmLarge() },
            onDismiss = { vm.dismissLarge() },
        )
    }
    vm.aiResult?.let { AiResultDialog(vm, it) }
}

@Composable
private fun AiResultDialog(vm: RecordingsViewModel, r: AiResultState) {
    val c = Inkwell.colors
    val context = LocalContext.current
    val title = stringResource(actionLabelRes(r.action))
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    AlertDialog(
        onDismissRequest = { vm.dismissAi() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 640.dp).fillMaxWidth(),
        containerColor = c.surface,
        shape = R16,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text(r.recording.title, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                    when {
                        r.error != null && r.text.isBlank() -> Text(r.error, style = MaterialTheme.typography.bodyMedium, color = c.danger)
                        r.text.isBlank() && r.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.audio_ai_working),
                                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                        else -> SelectionContainer { MarkdownText(r.text) }
                    }
                    if (r.error != null && r.text.isNotBlank()) {
                        Text(r.error, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                HorizontalDivider(color = c.outline)
                Text(footer(r), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
                r.savedTitle?.let { Text(stringResource(R.string.audio_ai_saved, it), style = MaterialTheme.typography.bodySmall, color = c.success) }
                r.saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (r.running) {
                    TextButton(onClick = { vm.stopAi() }) { Text(stringResource(R.string.audio_ai_stop), color = c.inkMuted) }
                }
                val canUse = r.text.isNotBlank() && !r.running
                TextButton(enabled = canUse, onClick = { copy(context, title, r.text) }) {
                    Text(stringResource(R.string.audio_ai_copy), color = if (canUse) c.accent else c.inkMuted)
                }
                TextButton(enabled = canUse && !r.saving && r.savedTitle == null, onClick = { vm.saveAiAsTyped(title) }) {
                    Text(stringResource(R.string.audio_ai_save_typed), color = if (canUse && r.savedTitle == null) c.accent else c.inkMuted)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissAi() }) { Text(stringResource(R.string.audio_close), color = c.inkMuted) }
        },
    )
}

@Composable
private fun footer(r: AiResultState): String {
    val u = r.usage
    val tokens = if (u != null) u.inputTokens + u.outputTokens else r.estimatedTokens
    val cost = (u?.estimatedCostUsd ?: if (u == null) r.estimatedCostUsd else null)
    return stringResource(R.string.audio_ai_footer, AudioText.tokens(tokens), AudioText.usd(cost) ?: stringResource(R.string.audio_price_unknown))
}

private fun copy(context: Context, label: String, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, context.getString(R.string.audio_ai_copied), Toast.LENGTH_SHORT).show()
    }
}

/** Renders the answer's light Markdown with the same parser used when saving it. */
@Composable
private fun MarkdownText(markdown: String) {
    val c = Inkwell.colors
    val blocks = androidx.compose.runtime.remember(markdown) { MarkdownBlocks.parse(markdown) { "" } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var number = 0
        for (b in blocks) {
            number = if (b.type == BlockType.NUMBERED) number + 1 else 0
            val text = styled(b.text, b.spans.map { Triple(it, it.start, it.end) })
            val indent = Modifier.padding(start = (12 * b.level).dp)
            when (b.type) {
                BlockType.HEADING -> Text(
                    text,
                    style = if (b.level <= 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = c.ink,
                    modifier = Modifier.padding(top = 4.dp),
                )
                BlockType.BULLET -> Text(prefixed("\u2022  ", text), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = indent)
                BlockType.NUMBERED -> Text(prefixed("$number.  ", text), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = indent)
                BlockType.CHECKLIST -> Text(prefixed(if (b.checked) "\u2713  " else "\u2610  ", text), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = indent)
                BlockType.QUOTE -> Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = c.inkMuted, modifier = Modifier.padding(start = 12.dp))
                BlockType.CODE -> Text(b.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.ink)
                BlockType.DIVIDER -> HorizontalDivider(color = c.outline)
                else -> Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
    }
}

private fun prefixed(prefix: String, text: AnnotatedString): AnnotatedString = buildAnnotatedString {
    append(prefix)
    append(text)
}

private fun styled(text: String, spans: List<Triple<com.inkwell.notes.core.model.TextSpan, Int, Int>>): AnnotatedString = buildAnnotatedString {
    append(text)
    for ((s, start, end) in spans) {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(a, text.length)
        addStyle(
            SpanStyle(
                fontWeight = if (s.bold) FontWeight.SemiBold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
            ),
            a, b,
        )
    }
}
