package com.inkwell.notes.export.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.export.ExportOptions
import com.inkwell.notes.export.ExportServiceImpl
import com.inkwell.notes.export.format.ExportChoices
import com.inkwell.notes.export.format.ExportErrors
import com.inkwell.notes.export.format.ExportNaming
import com.inkwell.notes.export.format.PageRange
import com.inkwell.notes.export.format.PdfQuality
import com.inkwell.notes.export.format.RangeMode
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Export sheet for one document: format, page range, PDF options (flatten, background,
 * searchable text, outline, image quality), image resolution and JPEG quality, audio in
 * packages; Share (export, then the share sheet) and Save to… (a location the user picks).
 */
@Composable
fun ExportDialog(docId: String, initialPageIndices: List<Int>? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<DocInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(ExportChoices()) }
    var running by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<ExportOptions?>(null) }

    LaunchedEffect(docId) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                val doc = graph.library.getDocument(docId) ?: graph.db.documents().get(docId)?.toModel()
                    ?: throw ExportErrors.of(context, R.string.export_err_not_found)
                val pages = graph.library.getPages(docId).size.takeIf { it > 0 } ?: graph.db.pages().count(docId)
                DocInfo(doc.title, doc.kind, pages) to ExportChoices.decode(prefs(context).getString(KEY_CHOICES, null))
            }
            info = loaded.first
            choices = ExportChoices.initial(loaded.second, initialPageIndices)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            loadError = ExportErrors.wrap(context, t).message
        }
    }

    fun update(c: ExportChoices) {
        choices = c
        error = null
        done = false
        scope.launch(Dispatchers.IO) { prefs(context).edit().putString(KEY_CHOICES, ExportChoices.encode(c)).apply() }
    }

    val saveLauncher = rememberLauncherForActivityResult(CreateDocumentOfType()) { uri: Uri? ->
        val options = pendingSave
        pendingSave = null
        if (uri == null || options == null) return@rememberLauncherForActivityResult
        running = scope.launch {
            error = null
            progress = null
            try {
                val service = graph.export
                if (service is ExportServiceImpl) service.exportTo(docId, options, uri) { d, t -> progress = d to t }
                else service.exportTo(docId, options, uri)
                done = true
                delay(900)
                onDismiss()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = ExportErrors.wrap(context, t).message
            } finally {
                running = null
                progress = null
            }
        }
    }

    fun optionsOrNull(i: DocInfo): ExportOptions? =
        when (val r = choices.toOptions(i.pageCount, initialPageIndices, i.paged)) {
            is ExportChoices.Result.Valid -> r.options
            is ExportChoices.Result.Invalid -> null
        }

    fun share(i: DocInfo) {
        val options = optionsOrNull(i) ?: return
        running = scope.launch {
            error = null
            progress = null
            try {
                val service = graph.export
                val file: File = if (service is ExportServiceImpl) service.exportFile(docId, options) { d, t -> progress = d to t }
                else service.export(docId, options)
                val mime = if (file.extension.equals("zip", true)) "application/zip" else options.format.mime
                val send = service.shareIntent(file, mime)
                val chooser = Intent.createChooser(send, context.getString(R.string.export_share_chooser, file.name))
                if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(chooser)
                    onDismiss()
                } catch (e: ActivityNotFoundException) {
                    error = context.getString(R.string.export_err_no_share)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = ExportErrors.wrap(context, t).message
            } finally {
                running = null
                progress = null
            }
        }
    }

    fun save(i: DocInfo) {
        val options = optionsOrNull(i) ?: return
        running = scope.launch {
            error = null
            try {
                val service = graph.export
                val (name, mime) = if (service is ExportServiceImpl) service.targetName(docId, options)
                else ExportNaming.fileName(i.title, options.format, 1) to ExportNaming.safMime(options.format, 1)
                pendingSave = options
                try {
                    saveLauncher.launch(name to mime)
                } catch (e: ActivityNotFoundException) {
                    pendingSave = null
                    error = context.getString(R.string.export_err_write)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = ExportErrors.wrap(context, t).message
            } finally {
                running = null
            }
        }
    }

    Dialog(
        onDismissRequest = { running?.cancel(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = running == null),
    ) {
        BoxWithConstraints(Modifier.padding(Dimens.gutter)) {
            val width = if (maxWidth < 600.dp) maxWidth else 560.dp
            Surface(
                modifier = Modifier.width(width).heightIn(max = maxHeight),
                shape = RoundedCornerShape(16.dp),
                color = Inkwell.colors.surface,
                border = BorderStroke(1.dp, Inkwell.colors.outline),
            ) {
                Column {
                    Header(title = info?.title, onClose = { running?.cancel(); onDismiss() })
                    HorizontalDivider(color = Inkwell.colors.outline)
                    val i = info
                    when {
                        loadError != null -> Message(loadError!!, isError = true, modifier = Modifier.padding(24.dp))
                        i == null -> LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = Inkwell.colors.accent,
                            trackColor = Inkwell.colors.surfaceAlt,
                        )
                        else -> {
                            Column(
                                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                FormatSection(choices.format) { update(choices.copy(format = it)) }
                                PagesSection(i, choices, initialPageIndices, enabled = running == null) { update(it) }
                                OptionsSection(i, choices, enabled = running == null) { update(it) }
                            }
                            HorizontalDivider(color = Inkwell.colors.outline)
                            Footer(
                                busy = running != null,
                                progress = progress,
                                error = error,
                                done = done,
                                canExport = optionsOrNull(i) != null && running == null,
                                onSave = { save(i) },
                                onShare = { share(i) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class DocInfo(val title: String, val kind: DocKind, val pageCount: Int) {
    /** Typed documents paginate at export time and boards are one page: no page choice. */
    val paged: Boolean get() = kind == DocKind.NOTEBOOK || kind == DocKind.PDF
}

private const val PREFS = "inkwell_export_dialog"
private const val KEY_CHOICES = "choices"

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** ACTION_CREATE_DOCUMENT with the file name and MIME type chosen at launch time. */
private class CreateDocumentOfType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

// ---------------------------------------------------------------------- Sections

@Composable
private fun Header(title: String?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge, color = Inkwell.colors.ink)
            if (title != null) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(Dimens.minTouch)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.export_close), modifier = Modifier.size(Dimens.icon), tint = Inkwell.colors.inkMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatSection(selected: ExportFormat, onSelect: (ExportFormat) -> Unit) {
    Section(stringResource(R.string.export_format_label)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in ExportFormat.entries) {
                val isSelected = f == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(f) },
                    label = { Text(stringResource(formatLabel(f)), style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.heightIn(min = Dimens.minTouch),
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Inkwell.colors.surface,
                        labelColor = Inkwell.colors.ink,
                        selectedContainerColor = Inkwell.colors.accentContainer,
                        selectedLabelColor = Inkwell.colors.ink,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Inkwell.colors.outline,
                        selectedBorderColor = Inkwell.colors.accent,
                        borderWidth = 1.dp,
                        selectedBorderWidth = 1.dp,
                    ),
                )
            }
        }
        Text(stringResource(formatDescription(selected)), style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted)
    }
}

@Composable
private fun PagesSection(info: DocInfo, choices: ExportChoices, selection: List<Int>?, enabled: Boolean, onChange: (ExportChoices) -> Unit) {
    if (!info.paged) {
        if (choices.format != ExportFormat.INKWELL) {
            Section(stringResource(R.string.export_pages_label)) {
                Text(
                    stringResource(if (info.kind == DocKind.TYPED) R.string.export_typed_pages else R.string.export_board_pages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Inkwell.colors.inkMuted,
                )
            }
        }
        return
    }
    if (choices.format == ExportFormat.INKWELL) return // packages always hold the whole document
    Section(stringResource(R.string.export_pages_label)) {
        val modes = buildList {
            add(RangeMode.ALL)
            if (!selection.isNullOrEmpty()) add(RangeMode.SELECTED)
            add(RangeMode.CUSTOM)
        }
        val current = if (choices.range in modes) choices.range else RangeMode.ALL
        Segmented(
            options = modes,
            selected = current,
            label = {
                when (it) {
                    RangeMode.ALL -> stringResource(R.string.export_pages_all)
                    RangeMode.SELECTED -> stringResource(R.string.export_pages_selected, selection?.size ?: 0)
                    RangeMode.CUSTOM -> stringResource(R.string.export_pages_custom)
                }
            },
            enabled = enabled,
            onSelect = { onChange(choices.copy(range = it)) },
        )
        if (current == RangeMode.CUSTOM) {
            val parsed = PageRange.parse(choices.custom, info.pageCount)
            val errorText = (parsed as? PageRange.Result.Error)?.takeIf { choices.custom.isNotBlank() }?.let { rangeError(it, info.pageCount) }
            OutlinedTextField(
                value = choices.custom,
                onValueChange = { onChange(choices.copy(custom = it.take(200))) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.export_pages_hint), color = Inkwell.colors.inkMuted) },
                isError = errorText != null,
                supportingText = errorText?.let { msg -> { Text(msg, color = Inkwell.colors.danger) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Inkwell.colors.accent,
                    unfocusedBorderColor = Inkwell.colors.outline,
                    errorBorderColor = Inkwell.colors.danger,
                    focusedTextColor = Inkwell.colors.ink,
                    unfocusedTextColor = Inkwell.colors.ink,
                    cursorColor = Inkwell.colors.accent,
                ),
            )
        }
        val count = choices.copy(range = current).pageCountFor(info.pageCount, selection)
        if (count != null && count > 0) {
            Text(
                pluralStringResource(R.plurals.export_pages_summary, count, count, info.pageCount),
                style = MaterialTheme.typography.bodySmall,
                color = Inkwell.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun OptionsSection(info: DocInfo, c: ExportChoices, enabled: Boolean, onChange: (ExportChoices) -> Unit) {
    val content: (@Composable () -> Unit)? = when (c.format) {
        ExportFormat.PDF -> {
            {
                if (info.kind != DocKind.TYPED) {
                    Label(stringResource(R.string.export_pdf_annotations))
                    Segmented(
                        options = listOf(true, false),
                        selected = c.flatten,
                        label = { stringResource(if (it) R.string.export_pdf_flattened else R.string.export_pdf_editable) },
                        enabled = enabled,
                        onSelect = { onChange(c.copy(flatten = it)) },
                    )
                    Text(
                        stringResource(if (c.flatten) R.string.export_pdf_flattened_hint else R.string.export_pdf_editable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Inkwell.colors.inkMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(stringResource(R.string.export_include_background), null, c.background, enabled) { onChange(c.copy(background = it)) }
                    ToggleRow(stringResource(R.string.export_searchable), stringResource(R.string.export_searchable_hint), c.searchable, enabled) { onChange(c.copy(searchable = it)) }
                }
                ToggleRow(stringResource(R.string.export_outline), null, c.outline, enabled) { onChange(c.copy(outline = it)) }
                Spacer(Modifier.height(4.dp))
                Label(stringResource(R.string.export_image_quality))
                Segmented(
                    options = PdfQuality.entries,
                    selected = c.pdfQuality,
                    label = {
                        stringResource(
                            when (it) {
                                PdfQuality.HIGH -> R.string.export_quality_high
                                PdfQuality.BALANCED -> R.string.export_quality_balanced
                                PdfQuality.SMALL -> R.string.export_quality_small
                            },
                        )
                    },
                    enabled = enabled,
                    onSelect = { onChange(c.copy(pdfQuality = it)) },
                )
            }
        }
        ExportFormat.PNG, ExportFormat.JPEG -> {
            {
                Label(stringResource(R.string.export_resolution))
                Segmented(
                    options = listOf(150, 200, 300),
                    selected = c.imageDpi.takeIf { it in setOf(150, 200, 300) } ?: 200,
                    label = { stringResource(R.string.export_dpi, it) },
                    enabled = enabled,
                    onSelect = { onChange(c.copy(imageDpi = it)) },
                )
                if (info.kind != DocKind.TYPED) {
                    ToggleRow(stringResource(R.string.export_include_background), null, c.background, enabled) { onChange(c.copy(background = it)) }
                }
                if (c.format == ExportFormat.JPEG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(stringResource(R.string.export_jpeg_quality), Modifier.weight(1f))
                        Text(stringResource(R.string.export_jpeg_quality_value, c.jpegQuality), style = MaterialTheme.typography.labelLarge, color = Inkwell.colors.ink)
                    }
                    Slider(
                        value = c.jpegQuality.toFloat(),
                        onValueChange = { onChange(c.copy(jpegQuality = (it / 5f).toInt() * 5)) },
                        valueRange = 50f..100f,
                        steps = 9,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = Inkwell.colors.accent,
                            activeTrackColor = Inkwell.colors.accent,
                            inactiveTrackColor = Inkwell.colors.surfaceAlt,
                        ),
                    )
                }
            }
        }
        ExportFormat.TEXT, ExportFormat.MARKDOWN, ExportFormat.DOCX -> {
            { ToggleRow(stringResource(R.string.export_include_transcripts), null, c.includeTranscripts, enabled) { onChange(c.copy(includeTranscripts = it)) } }
        }
        ExportFormat.INKWELL -> {
            { ToggleRow(stringResource(R.string.export_include_audio), stringResource(R.string.export_include_audio_hint), c.includeAudio, enabled) { onChange(c.copy(includeAudio = it)) } }
        }
    }
    if (content != null) Section(stringResource(R.string.export_section_options)) { content() }
}

@Composable
private fun Footer(
    busy: Boolean,
    progress: Pair<Int, Int>?,
    error: String?,
    done: Boolean,
    canExport: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            busy -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = progress
                Text(
                    if (p != null && p.second > 1) stringResource(R.string.export_working_pages, p.first.coerceAtMost(p.second), p.second)
                    else stringResource(R.string.export_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = Inkwell.colors.inkMuted,
                )
                if (p != null && p.second > 1) {
                    LinearProgressIndicator(
                        progress = { p.first.toFloat() / p.second },
                        modifier = Modifier.fillMaxWidth(),
                        color = Inkwell.colors.accent,
                        trackColor = Inkwell.colors.surfaceAlt,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Inkwell.colors.accent, trackColor = Inkwell.colors.surfaceAlt)
                }
            }
            error != null -> Message(error, isError = true)
            done -> Message(stringResource(R.string.export_saved), isError = false)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            OutlinedButton(
                onClick = onSave,
                enabled = canExport,
                modifier = Modifier.heightIn(min = Dimens.minTouch),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Inkwell.colors.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Inkwell.colors.ink),
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(Dimens.icon))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_save), style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onShare,
                enabled = canExport,
                modifier = Modifier.heightIn(min = Dimens.minTouch),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Inkwell.colors.accent, contentColor = Inkwell.colors.onAccent),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(Dimens.icon))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_share), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------------- Building blocks

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Inkwell.colors.ink)
        content()
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Inkwell.colors.inkMuted, modifier = modifier)
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size, RoundedCornerShape(8.dp)),
                modifier = Modifier.heightIn(min = Dimens.minTouch),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Inkwell.colors.accentContainer,
                    activeContentColor = Inkwell.colors.ink,
                    activeBorderColor = Inkwell.colors.outline,
                    inactiveContainerColor = Inkwell.colors.surface,
                    inactiveContentColor = Inkwell.colors.inkMuted,
                    inactiveBorderColor = Inkwell.colors.outline,
                ),
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouch)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Inkwell.colors.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Inkwell.colors.accent,
                checkedThumbColor = Inkwell.colors.onAccent,
                uncheckedTrackColor = Inkwell.colors.surfaceAlt,
                uncheckedThumbColor = Inkwell.colors.inkMuted,
                uncheckedBorderColor = Inkwell.colors.outline,
            ),
        )
    }
}

@Composable
private fun Message(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val color = if (isError) Inkwell.colors.danger else Inkwell.colors.success
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, Inkwell.colors.outline, RoundedCornerShape(8.dp))
            .background(Inkwell.colors.surfaceAlt, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Check, contentDescription = null, tint = color, modifier = Modifier.size(Dimens.icon))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.ink)
    }
}

@Composable
private fun rangeError(e: PageRange.Result.Error, pageCount: Int): String = when (e.reason) {
    PageRange.Reason.EMPTY -> stringResource(R.string.export_range_empty)
    PageRange.Reason.SYNTAX -> stringResource(R.string.export_range_syntax, e.token.trim())
    PageRange.Reason.OUT_OF_RANGE -> stringResource(R.string.export_range_out, e.token.trim(), pageCount)
    PageRange.Reason.REVERSED -> stringResource(R.string.export_range_reversed, e.token.trim())
}

private fun formatLabel(f: ExportFormat): Int = when (f) {
    ExportFormat.PDF -> R.string.export_format_pdf
    ExportFormat.PNG -> R.string.export_format_png
    ExportFormat.JPEG -> R.string.export_format_jpeg
    ExportFormat.TEXT -> R.string.export_format_text
    ExportFormat.MARKDOWN -> R.string.export_format_markdown
    ExportFormat.DOCX -> R.string.export_format_docx
    ExportFormat.INKWELL -> R.string.export_format_inkwell
}

private fun formatDescription(f: ExportFormat): Int = when (f) {
    ExportFormat.PDF -> R.string.export_format_pdf_desc
    ExportFormat.PNG -> R.string.export_format_png_desc
    ExportFormat.JPEG -> R.string.export_format_jpeg_desc
    ExportFormat.TEXT -> R.string.export_format_text_desc
    ExportFormat.MARKDOWN -> R.string.export_format_markdown_desc
    ExportFormat.DOCX -> R.string.export_format_docx_desc
    ExportFormat.INKWELL -> R.string.export_format_inkwell_desc
}
