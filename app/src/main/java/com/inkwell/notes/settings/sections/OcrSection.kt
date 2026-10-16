package com.inkwell.notes.settings.sections

import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.notes.app.SettingsSection
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.OcrPrefs
import com.inkwell.notes.ocr.ModelStatus
import com.inkwell.notes.settings.logic.HandwritingLanguage
import com.inkwell.notes.settings.logic.HandwritingLanguages
import com.inkwell.notes.settings.logic.SettingsFormat
import com.inkwell.notes.settings.ui.ActionRow
import com.inkwell.notes.settings.ui.Note
import com.inkwell.notes.settings.ui.NoteTone
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SliderRow
import com.inkwell.notes.settings.ui.SwitchRow
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val THRESHOLD_MIN = 0.3f
private const val THRESHOLD_MAX = 0.9f
private const val THRESHOLD_STEP = 0.05f

@Composable
internal fun OcrSection(s: AppSettings, actions: SettingsActions, openSection: (SettingsSection) -> Unit) {
    val ocr = s.ocr
    val assist by rememberAiTaskStatus(actions.graph, AiTask.OCR_ASSIST, s.ai)
    val downloading = remember { mutableStateMapOf<String, Boolean>() }
    fun set(transform: (OcrPrefs) -> OcrPrefs) = actions.update { it.copy(ocr = transform(it.ocr)) }

    SectionColumn {
        SettingsGroup("Recognition") {
            SwitchRow(
                "Recognise handwriting",
                ocr.recognizeHandwriting,
                { v -> set { it.copy(recognizeHandwriting = v) } },
                subtitle = "Turns your handwriting into searchable text on this device, in the background.",
            )
            RowDivider()
            SwitchRow(
                "Recognise text in imports",
                ocr.autoOcrImports,
                { v -> set { it.copy(autoOcrImports = v) } },
                subtitle = "Runs OCR on imported PDFs and images that have no text layer, so they become searchable.",
            )
        }

        SettingsGroup(
            "Handwriting languages",
            description = "Each language uses a model that is downloaded once and then works offline. The first language is the primary one. " +
                "Selected: ${HandwritingLanguages.summary(ocr.handwritingLanguages)}.",
        ) {
            LanguageList(ocr, actions, downloading, enabled = ocr.recognizeHandwriting)
        }

        SettingsGroup("AI assist") {
            SwitchRow(
                "Use AI for hard-to-read pages",
                ocr.aiAssistLowConfidence,
                { v -> set { it.copy(aiAssistLowConfidence = v) } },
                subtitle = "Pages recognised with low confidence are sent as images to your OCR assist model. Unclear words are marked, never guessed.",
            )
            val status = assist
            if (ocr.aiAssistLowConfidence && status != null && !status.available) {
                Note(
                    "No vision model is set up, so pages are recognised on this device only.",
                    tone = NoteTone.WARNING,
                    icon = Icons.Outlined.WarningAmber,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    QuietButton("Open AI providers", onClick = { openSection(SettingsSection.AI) })
                }
            }
            RowDivider()
            SliderRow(
                title = "Confidence threshold",
                subtitle = "Pages below this confidence are sent for AI assist.",
                value = ocr.lowConfidenceThreshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX),
                onCommit = { v -> set { it.copy(lowConfidenceThreshold = snapThreshold(v)) } },
                valueRange = THRESHOLD_MIN..THRESHOLD_MAX,
                steps = ((THRESHOLD_MAX - THRESHOLD_MIN) / THRESHOLD_STEP).roundToInt() - 1,
                snap = ::snapThreshold,
                valueLabel = { SettingsFormat.percent(it) },
                enabled = ocr.aiAssistLowConfidence,
                startLabel = "Fewer pages",
                endLabel = "More pages",
            )
        }

        SettingsGroup("Search index") {
            ActionRow(
                title = "Re-index all documents",
                subtitle = "Recognises every page again and rebuilds search. Runs in the background; you can keep writing.",
                actionLabel = "Re-index",
                onAction = {
                    actions.background("Couldn't start re-indexing. Please try again.") {
                        actions.graph.indexing.reindexAll()
                        actions.message("Re-indexing started. Search results update as pages are processed.")
                    }
                },
            )
        }
    }
}

private fun snapThreshold(v: Float): Float =
    ((v.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX) / THRESHOLD_STEP).roundToInt() * THRESHOLD_STEP).coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)

@Composable
private fun LanguageList(ocr: OcrPrefs, actions: SettingsActions, downloading: SnapshotStateMap<String, Boolean>, enabled: Boolean) {
    val selection = ocr.handwritingLanguages
    val rows = HandwritingLanguages.rows(selection)
    val primary = HandwritingLanguages.primary(selection)

    fun download(lang: HandwritingLanguage) {
        if (downloading[lang.tag] == true) return
        downloading[lang.tag] = true
        actions.graph.appScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { actions.graph.recognition.downloadHandwritingModel(lang.tag) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w("Settings", "Model download failed: ${e.javaClass.simpleName}")
                false
            }
            withContext(Dispatchers.Main) { downloading.remove(lang.tag) }
            actions.message(
                if (ok) "${lang.name} handwriting is ready."
                else "Couldn't download ${lang.name}. Check your connection and try again.",
            )
        }
    }

    fun toggle(lang: HandwritingLanguage, status: ModelStatus) {
        when (val change = HandwritingLanguages.toggle(selection, lang.tag)) {
            is HandwritingLanguages.Change.Refused -> actions.message(change.reason)
            is HandwritingLanguages.Change.Updated -> {
                actions.update { cur ->
                    when (val c = HandwritingLanguages.toggle(cur.ocr.handwritingLanguages, lang.tag)) {
                        is HandwritingLanguages.Change.Updated -> cur.copy(ocr = cur.ocr.copy(handwritingLanguages = c.tags))
                        is HandwritingLanguages.Change.Refused -> cur
                    }
                }
                val added = change.tags.any { it.equals(lang.tag, ignoreCase = true) }
                if (added && status == ModelStatus.NOT_DOWNLOADED) download(lang)
            }
        }
    }

    fun makePrimary(lang: HandwritingLanguage) {
        actions.update { cur -> cur.copy(ocr = cur.ocr.copy(handwritingLanguages = HandwritingLanguages.makePrimary(cur.ocr.handwritingLanguages, lang.tag))) }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 640.dp) 2 else 1
        val perColumn = (rows.size + columns - 1) / columns
        Row(Modifier.fillMaxWidth()) {
            for (col in 0 until columns) {
                val slice = rows.drop(col * perColumn).take(perColumn)
                Column(Modifier.weight(1f)) {
                    slice.forEachIndexed { i, lang ->
                        if (i > 0) RowDivider()
                        LanguageRow(
                            lang = lang,
                            selected = HandwritingLanguages.isSelected(selection, lang.tag),
                            primary = lang.tag.equals(primary, ignoreCase = true),
                            statusFlow = rememberModelStatus(actions, lang.tag),
                            downloading = downloading[lang.tag] == true,
                            enabled = enabled,
                            onToggle = { status -> toggle(lang, status) },
                            onDownload = { download(lang) },
                            onMakePrimary = { makePrimary(lang) },
                        )
                    }
                }
            }
        }
    }
    if (!enabled) {
        Note(
            "Handwriting recognition is off. Turn on \"Recognise handwriting\" to use these languages.",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun rememberModelStatus(actions: SettingsActions, tag: String): StateFlow<ModelStatus> = remember(tag) {
    try {
        actions.graph.recognition.handwritingModelStatus(tag)
    } catch (e: Exception) {
        Log.w("Settings", "Model status unavailable: ${e.javaClass.simpleName}")
        MutableStateFlow(ModelStatus.UNAVAILABLE)
    }
}

@Composable
private fun LanguageRow(
    lang: HandwritingLanguage,
    selected: Boolean,
    primary: Boolean,
    statusFlow: StateFlow<ModelStatus>,
    downloading: Boolean,
    enabled: Boolean,
    onToggle: (ModelStatus) -> Unit,
    onDownload: () -> Unit,
    onMakePrimary: () -> Unit,
) {
    val c = Inkwell.colors
    val status by statusFlow.collectAsState()
    val busy = downloading || status == ModelStatus.DOWNLOADING
    val statusText = when {
        busy -> "Downloading…"
        !lang.supported -> "Not in the supported list"
        status == ModelStatus.READY -> "Downloaded"
        status == ModelStatus.NOT_DOWNLOADED -> "Not downloaded"
        else -> "Not available on this device"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(status) })
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.inkMuted, checkmarkColor = c.onAccent),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lang.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (enabled) c.ink else c.inkMuted,
                )
                if (primary && selected) {
                    Spacer(Modifier.width(8.dp))
                    Text("Primary", style = MaterialTheme.typography.labelSmall, color = c.accent)
                }
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (status == ModelStatus.READY && !busy) c.success else c.inkMuted,
            )
        }
        when {
            busy -> CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(20.dp), color = c.accent, strokeWidth = 2.dp)
            status == ModelStatus.NOT_DOWNLOADED && lang.supported -> QuietButton("Download", onClick = onDownload, enabled = enabled)
            selected && !primary -> QuietButton("Make primary", onClick = onMakePrimary, enabled = enabled, color = c.inkMuted)
        }
    }
}
