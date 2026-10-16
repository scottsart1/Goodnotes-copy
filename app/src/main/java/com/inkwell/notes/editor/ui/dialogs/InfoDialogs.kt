package com.inkwell.notes.editor.ui.dialogs

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.editor.catchingNonCancel
import com.inkwell.notes.editor.ui.pages.pageSizeMm
import com.inkwell.notes.ui.theme.Inkwell

@Composable
fun DocumentInfoDialog(graph: AppGraph, doc: DocumentMeta, pages: List<PageMeta>, currentPage: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val folder by produceState<String?>(null, doc.folderId) {
        value = catchingNonCancel { graph.library.folderPath(doc.folderId) }.getOrNull()
    }
    fun date(ms: Long) = DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_doc_info)) },
        text = {
            Column(Modifier.widthIn(min = 300.dp, max = 520.dp)) {
                InfoRow(stringResource(R.string.editor_info_title), doc.title)
                InfoRow(stringResource(R.string.editor_info_kind), stringResource(if (doc.kind == DocKind.PDF) R.string.editor_kind_pdf else R.string.editor_kind_notebook))
                InfoRow(stringResource(R.string.editor_info_pages), pluralStringResource(R.plurals.editor_page_count, pages.size, pages.size))
                pages.getOrNull(currentPage)?.let { InfoRow(stringResource(R.string.editor_info_page_size), pageSizeMm(it)) }
                InfoRow(stringResource(R.string.editor_info_folder), folder?.ifBlank { null } ?: stringResource(R.string.editor_info_library_root))
                InfoRow(stringResource(R.string.editor_info_created), date(doc.createdAt))
                InfoRow(stringResource(R.string.editor_info_modified), date(doc.modifiedAt))
                if (doc.sizeBytes > 0) InfoRow(stringResource(R.string.editor_info_size), Formatter.formatShortFileSize(context, doc.sizeBytes))
                InfoRow(stringResource(R.string.editor_info_naming), stringResource(namingLabel(doc.autoNameStatus)))
                doc.originalName?.let { InfoRow(stringResource(R.string.editor_info_original), it) }
                if (doc.tags.isNotEmpty()) InfoRow(stringResource(R.string.editor_info_tags), doc.tags.joinToString(", "))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_close)) } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.ink, modifier = Modifier.weight(1f))
    }
}

fun namingLabel(s: AutoNameStatus): Int = when (s) {
    AutoNameStatus.PENDING -> R.string.editor_naming_pending
    AutoNameStatus.QUEUED -> R.string.editor_naming_queued
    AutoNameStatus.DONE -> R.string.editor_naming_done
    AutoNameStatus.INSUFFICIENT -> R.string.editor_naming_insufficient
    AutoNameStatus.MANUAL -> R.string.editor_naming_manual
    AutoNameStatus.IMPORTED -> R.string.editor_naming_imported
    AutoNameStatus.DISABLED -> R.string.editor_naming_disabled
}

/** Explains why the microphone is needed after a denial, with a way to the system settings. */
@Composable
fun MicRationaleDialog(permanentlyDenied: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_mic_title)) },
        text = { Text(stringResource(if (permanentlyDenied) R.string.editor_mic_settings_body else R.string.editor_mic_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (permanentlyDenied) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.editor_open_settings)) }
            else TextButton(onClick = onRetry) { Text(stringResource(R.string.editor_allow)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_not_now)) } },
    )
}

/** Confirms sending recognised text to an AI provider, with the estimated size and cost. */
@Composable
fun AiSendConfirmDialog(model: String?, tokens: Int, costUsd: Double?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val est = buildString {
        append(stringResource(R.string.editor_ai_tokens, formatTokens(tokens)))
        if (costUsd != null) append(" · ").append(stringResource(R.string.editor_ai_cost, "%.3f".format(costUsd)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_ai_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.editor_ai_confirm_body, model ?: stringResource(R.string.editor_ai_default_model)), style = MaterialTheme.typography.bodyMedium)
                Text(est, style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.editor_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

fun formatTokens(n: Int): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()
