package com.inkwell.notes.settings.sections

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.ai.AiServiceImpl
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.SettingsSection
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.settings.logic.MatchSensitivity
import com.inkwell.notes.settings.logic.SpeakerPrefsLogic
import com.inkwell.notes.settings.ui.ActionRow
import com.inkwell.notes.settings.ui.ChipsRow
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.ConfirmDialog
import com.inkwell.notes.settings.ui.Note
import com.inkwell.notes.settings.ui.NoteTone
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.RadioRow
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.RowLabel
import com.inkwell.notes.settings.ui.SegmentedRow
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SettingsIconButton
import com.inkwell.notes.settings.ui.SwitchRow
import com.inkwell.notes.speakers.SpeakerModelState
import com.inkwell.notes.speakers.ui.EnrollmentDialog
import com.inkwell.notes.speakers.ui.SpeakerDot
import com.inkwell.notes.speakers.ui.logic.NameSuggestions
import com.inkwell.notes.speakers.ui.logic.SpeakerNumbers
import com.inkwell.notes.speakers.ui.logic.SpeakerStatusLogic
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val R8 = RoundedCornerShape(8.dp)
private val R16 = RoundedCornerShape(16.dp)

/** The cloud diarization model; its per-minute price comes from the AI engine's price table. */
private val DIARIZE_MODEL = ModelRef(AiProviderId.OPENAI, "gpt-4o-transcribe-diarize")

/** Dialogs of the section (only one at a time). */
private sealed interface VoicesDialog {
    data class Rename(val person: Person) : VoicesDialog
    data class Merge(val person: Person) : VoicesDialog
    data class Delete(val person: Person) : VoicesDialog
    data class Enroll(val person: Person) : VoicesDialog
    data object NameMe : VoicesDialog
    data object ForgetAll : VoicesDialog
    data object ForgetAfterOff : VoicesDialog
    data object DeleteModels : VoicesDialog
}

/** Whether speakers can be labelled in the cloud right now, and its price per minute. */
private data class CloudStatus(val available: Boolean, val usdPerMinute: Double?)

/**
 * "Voices & speakers": which engine tells voices apart, the on-device models, naming and
 * remembering voices, and the people the app knows. Voiceprints never appear here, only names
 * and counts; every voice action goes through graph.speakers.
 */
@Composable
internal fun SpeakersSection(s: AppSettings, actions: SettingsActions, openSection: (SettingsSection) -> Unit) {
    SectionColumn { SpeakersContent(s, actions, openSection) }
}

/**
 * The section's groups, emitted into the caller's [SectionColumn]. [title] heads them when they
 * are shown inside another section (Recording, until the section has its own entry).
 */
@Composable
internal fun SpeakersContent(s: AppSettings, actions: SettingsActions, openSection: (SettingsSection) -> Unit, title: String? = null) {
    val graph = actions.graph
    val prefs = s.speakers
    val models by graph.speakers.modelState.collectAsState()
    val people by remember(graph) {
        graph.speakers.people().map<List<Person>, List<Person>?> { it }.catch { emit(emptyList()) }.flowOn(Dispatchers.IO)
    }.collectAsState(initial = null)
    val cloud by rememberCloudStatus(graph, s.ai)
    var dialog by remember { mutableStateOf<VoicesDialog?>(null) }
    val failed = stringResource(R.string.spk_set_failed)
    fun set(transform: (SpeakerPrefs) -> SpeakerPrefs) = actions.update { it.copy(speakers = transform(it.speakers)) }

    run {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = Inkwell.colors.ink,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
        }
        SettingsGroup(stringResource(R.string.spk_set_engine), description = stringResource(R.string.spk_set_engine_desc)) {
            RadioRow(
                title = stringResource(R.string.spk_set_engine_device),
                selected = prefs.engine == SpeakerEngine.DEVICE,
                onClick = { set { it.copy(engine = SpeakerEngine.DEVICE) } },
                subtitle = stringResource(R.string.spk_set_engine_device_sub, SpeakerStatusLogic.MODEL_DOWNLOAD_MB),
            )
            RowDivider()
            val cost = cloud?.usdPerMinute?.let { AudioText.usd(it) }
            RadioRow(
                title = stringResource(R.string.spk_set_engine_openai),
                selected = prefs.engine == SpeakerEngine.OPENAI,
                onClick = { set { it.copy(engine = SpeakerEngine.OPENAI) } },
                subtitle = stringResource(R.string.spk_set_engine_openai_sub) +
                    (cost?.let { " " + stringResource(R.string.spk_set_engine_openai_cost, it) } ?: ""),
            )
            if (prefs.engine == SpeakerEngine.OPENAI && cloud?.available == false) {
                Note(
                    stringResource(R.string.spk_set_engine_openai_missing),
                    tone = NoteTone.WARNING,
                    icon = Icons.Outlined.WarningAmber,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    QuietButton(stringResource(R.string.spk_set_open_ai), onClick = { openSection(SettingsSection.AI) })
                }
            }
            RowDivider()
            RadioRow(
                title = stringResource(R.string.spk_set_engine_off),
                selected = prefs.engine == SpeakerEngine.OFF,
                onClick = { set { it.copy(engine = SpeakerEngine.OFF) } },
                subtitle = stringResource(R.string.spk_set_engine_off_sub),
            )
        }

        if (prefs.engine == SpeakerEngine.DEVICE) {
            SettingsGroup(stringResource(R.string.spk_set_models)) {
                ModelsRow(models, actions, onDelete = { dialog = VoicesDialog.DeleteModels })
                RowDivider()
                SwitchRow(
                    stringResource(R.string.spk_set_wifi_only),
                    prefs.downloadOnUnmeteredOnly,
                    { v -> set { it.copy(downloadOnUnmeteredOnly = v) } },
                    subtitle = stringResource(R.string.spk_set_wifi_only_sub),
                )
            }
        }

        if (prefs.engine != SpeakerEngine.OFF) {
            SettingsGroup(stringResource(R.string.spk_set_naming)) {
                SwitchRow(
                    stringResource(R.string.spk_set_ask),
                    prefs.askToName,
                    { v -> set { it.copy(askToName = v) } },
                    subtitle = stringResource(R.string.spk_set_ask_sub),
                )
                RowDivider()
                SwitchRow(
                    stringResource(R.string.spk_set_remember),
                    prefs.rememberVoices,
                    { v ->
                        set { it.copy(rememberVoices = v) }
                        // Turning it off offers to delete what is already stored.
                        if (!v && people.orEmpty().any { it.voiceSamples > 0 }) dialog = VoicesDialog.ForgetAfterOff
                    },
                    subtitle = stringResource(R.string.spk_set_remember_sub),
                )
                RowDivider()
                val auto = stringResource(R.string.spk_set_expected_auto)
                ChipsRow(
                    title = stringResource(R.string.spk_set_expected),
                    subtitle = stringResource(R.string.spk_set_expected_sub),
                    choices = SpeakerPrefsLogic.expectedSpeakerChoices.map { Choice(it, if (it == 0) auto else it.toString()) },
                    selected = SpeakerPrefsLogic.expected(prefs),
                    onSelect = { n -> set { it.copy(expectedSpeakers = n) } },
                )
                if (prefs.engine == SpeakerEngine.DEVICE && prefs.rememberVoices) {
                    RowDivider()
                    val sensitivity = MatchSensitivity.of(prefs.matchThreshold)
                    SegmentedRow(
                        title = stringResource(R.string.spk_set_sensitivity),
                        subtitle = stringResource(R.string.spk_set_sensitivity_sub),
                        choices = listOf(
                            Choice(MatchSensitivity.STRICT, stringResource(R.string.spk_set_strict)),
                            Choice(MatchSensitivity.BALANCED, stringResource(R.string.spk_set_balanced)),
                            Choice(MatchSensitivity.LOOSE, stringResource(R.string.spk_set_loose)),
                        ),
                        selected = sensitivity,
                        onSelect = { m -> set { it.copy(matchThreshold = m.threshold) } },
                        footer = {
                            Note(
                                stringResource(
                                    when (sensitivity) {
                                        MatchSensitivity.STRICT -> R.string.spk_set_sensitivity_strict_note
                                        MatchSensitivity.BALANCED -> R.string.spk_set_sensitivity_balanced_note
                                        MatchSensitivity.LOOSE -> R.string.spk_set_sensitivity_loose_note
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }

        SettingsGroup(stringResource(R.string.spk_set_people), description = stringResource(R.string.spk_set_people_desc)) {
            val list = people
            val hasMe = list.orEmpty().any { it.isMe }
            if (!hasMe && prefs.rememberVoices && prefs.engine != SpeakerEngine.OFF) {
                ActionRow(
                    title = stringResource(R.string.spk_set_record_me),
                    subtitle = stringResource(R.string.spk_set_record_me_sub),
                    actionLabel = stringResource(R.string.spk_set_record),
                    onAction = { dialog = VoicesDialog.NameMe },
                )
                RowDivider()
            }
            when {
                list == null -> Spacer(Modifier.heightIn(min = 56.dp))
                list.isEmpty() -> Text(
                    stringResource(R.string.spk_set_people_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Inkwell.colors.inkMuted,
                    modifier = Modifier.padding(16.dp),
                )
                else -> list.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    PersonRow(
                        p = p,
                        canMerge = list.size > 1,
                        canRecord = prefs.rememberVoices,
                        onRename = { dialog = VoicesDialog.Rename(p) },
                        onToggleMe = { actions.background(failed) { graph.speakers.setMe(if (p.isMe) null else p.id) } },
                        onMerge = { dialog = VoicesDialog.Merge(p) },
                        onRecord = { dialog = VoicesDialog.Enroll(p) },
                        onDelete = { dialog = VoicesDialog.Delete(p) },
                    )
                }
            }
            if (!prefs.rememberVoices && !list.isNullOrEmpty()) {
                Note(stringResource(R.string.spk_set_voice_off_note), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }

        SettingsGroup(null) {
            ActionRow(
                title = stringResource(R.string.spk_set_forget_all),
                subtitle = stringResource(R.string.spk_set_forget_all_sub),
                actionLabel = stringResource(R.string.spk_set_forget),
                onAction = { dialog = VoicesDialog.ForgetAll },
                destructive = true,
            )
        }

        Note(stringResource(R.string.spk_explainer), icon = Icons.Outlined.Lock)
    }

    VoicesDialogs(dialog, people.orEmpty(), actions, onDismiss = { dialog = null }, onEnroll = { dialog = VoicesDialog.Enroll(it) })
}

@Composable
private fun rememberCloudStatus(graph: AppGraph, ai: AiPrefs): State<CloudStatus?> =
    produceState<CloudStatus?>(initialValue = null, graph, ai) {
        value = withContext(Dispatchers.IO) {
            try {
                val available = ai.enabled && graph.ai.canDiarize()
                val perMinute = runCatching { (graph.ai as? AiServiceImpl)?.engine?.models?.spec(DIARIZE_MODEL)?.usdPerAudioMinute }.getOrNull()
                CloudStatus(available, perMinute)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w("Settings", "Diarization availability check failed: ${e.javaClass.simpleName}")
                CloudStatus(false, null)
            }
        }
    }

@Composable
private fun ModelsRow(models: SpeakerModelState, actions: SettingsActions, onDelete: () -> Unit) {
    val graph = actions.graph
    val failed = stringResource(R.string.spk_set_failed)
    val title = stringResource(R.string.spk_set_models)
    when (models) {
        SpeakerModelState.NotDownloaded -> ActionRow(
            title = title,
            subtitle = stringResource(R.string.spk_set_models_not_downloaded, SpeakerStatusLogic.MODEL_DOWNLOAD_MB),
            actionLabel = stringResource(R.string.spk_set_download),
            onAction = { actions.background(failed) { graph.speakers.downloadModels() } },
        )
        is SpeakerModelState.Downloading -> {
            val c = Inkwell.colors
            val done = SpeakerNumbers.megabytes(models.bytesDone).toInt()
            val total = SpeakerNumbers.megabytes(models.bytesTotal).toInt()
            val fraction = SpeakerNumbers.fraction(models.bytesDone, models.bytesTotal)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowLabel(
                        title,
                        if (models.bytesTotal > 0) stringResource(R.string.spk_models_downloading, done, total)
                        else stringResource(R.string.spk_models_downloading_unknown, done),
                        true,
                        Modifier.weight(1f),
                    )
                    // Deleting the models also stops a download in progress.
                    QuietButton(stringResource(R.string.spk_cancel), onClick = { actions.background(failed) { graph.speakers.deleteModels() } }, color = c.inkMuted)
                }
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
                }
            }
        }
        SpeakerModelState.Ready -> ActionRow(
            title = title,
            subtitle = stringResource(R.string.spk_set_models_ready),
            actionLabel = stringResource(R.string.spk_set_delete),
            onAction = onDelete,
        )
        is SpeakerModelState.Failed -> ActionRow(
            title = title,
            subtitle = stringResource(R.string.spk_set_models_failed, models.message),
            actionLabel = stringResource(R.string.spk_try_again),
            onAction = { actions.background(failed) { graph.speakers.downloadModels() } },
        )
    }
}

@Composable
private fun PersonRow(
    p: Person,
    canMerge: Boolean,
    canRecord: Boolean,
    onRename: () -> Unit,
    onToggleMe: () -> Unit,
    onMerge: () -> Unit,
    onRecord: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Inkwell.colors
    var menu by remember { mutableStateOf(false) }
    val samples = if (p.voiceSamples > 0) pluralStringResource(R.plurals.spk_set_samples, p.voiceSamples, p.voiceSamples)
    else stringResource(R.string.spk_set_no_samples)
    val recordings = pluralStringResource(R.plurals.spk_set_recordings, p.recordingCount, p.recordingCount)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerDot(p.colorIndex, size = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (p.isMe) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.spk_me_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.inkMuted,
                        modifier = Modifier.border(1.dp, c.outline, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text("$samples · $recordings", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        Box {
            SettingsIconButton(Icons.Outlined.MoreVert, stringResource(R.string.spk_set_person_menu, p.name), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(c.surface)) {
                PersonMenuItem(stringResource(R.string.spk_set_rename), Icons.Outlined.Edit) { menu = false; onRename() }
                PersonMenuItem(stringResource(if (p.isMe) R.string.spk_set_unmark_me else R.string.spk_set_mark_me), Icons.Outlined.Person) {
                    menu = false
                    onToggleMe()
                }
                if (canMerge) PersonMenuItem(stringResource(R.string.spk_set_merge), Icons.AutoMirrored.Outlined.MergeType) { menu = false; onMerge() }
                if (canRecord) PersonMenuItem(stringResource(R.string.spk_set_record_sample), Icons.Outlined.Mic) { menu = false; onRecord() }
                PersonMenuItem(stringResource(R.string.spk_set_delete_person), Icons.Outlined.Delete, danger = true) { menu = false; onDelete() }
            }
        }
    }
}

@Composable
private fun PersonMenuItem(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val c = Inkwell.colors
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) c.danger else c.ink) },
        leadingIcon = { Icon(icon, null, Modifier.size(Dimens.icon), tint = if (danger) c.danger else c.inkMuted) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = Dimens.minTouch),
    )
}

@Composable
private fun VoicesDialogs(
    dialog: VoicesDialog?,
    people: List<Person>,
    actions: SettingsActions,
    onDismiss: () -> Unit,
    onEnroll: (Person) -> Unit,
) {
    val graph = actions.graph
    val failed = stringResource(R.string.spk_set_failed)
    when (dialog) {
        null -> Unit
        is VoicesDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.spk_set_rename_title, dialog.person.name),
            initial = dialog.person.name,
            label = stringResource(R.string.spk_name_hint),
            confirm = stringResource(R.string.spk_save),
            onConfirm = { name ->
                onDismiss()
                if (name != dialog.person.name) actions.background(failed) { graph.speakers.renamePerson(dialog.person.id, name) }
            },
            onDismiss = onDismiss,
        )
        is VoicesDialog.Merge -> MergePersonDialog(
            from = dialog.person,
            targets = people.filter { it.id != dialog.person.id },
            onMerge = { into ->
                onDismiss()
                actions.background(failed) { graph.speakers.mergePeople(dialog.person.id, into.id) }
            },
            onDismiss = onDismiss,
        )
        is VoicesDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.spk_set_delete_person_title, dialog.person.name),
            text = stringResource(R.string.spk_set_delete_person_body),
            confirmLabel = stringResource(R.string.spk_set_delete_person),
            onConfirm = {
                onDismiss()
                actions.background(failed) { graph.speakers.deletePerson(dialog.person.id) }
            },
            onDismiss = onDismiss,
        )
        is VoicesDialog.Enroll -> EnrollmentDialog(dialog.person, onDismiss = onDismiss)
        VoicesDialog.NameMe -> TextInputDialog(
            title = stringResource(R.string.spk_set_your_name_title),
            initial = "",
            label = stringResource(R.string.spk_set_your_name),
            confirm = stringResource(R.string.spk_set_continue),
            onConfirm = { name ->
                onDismiss()
                // Reuse a person of that name (e.g. named in a recording already) rather than a duplicate.
                actions.background(failed) {
                    val existing = NameSuggestions.exact(people, name)
                    val me = if (existing != null) {
                        graph.speakers.setMe(existing.id)
                        existing.copy(isMe = true)
                    } else {
                        graph.speakers.createPerson(name, isMe = true)
                    }
                    withContext(Dispatchers.Main) { onEnroll(me) }
                }
            },
            onDismiss = onDismiss,
        )
        VoicesDialog.ForgetAll -> ConfirmDialog(
            title = stringResource(R.string.spk_set_forget_all_title),
            text = stringResource(R.string.spk_set_forget_all_body),
            confirmLabel = stringResource(R.string.spk_set_forget),
            onConfirm = {
                onDismiss()
                val done = graph.context.getString(R.string.spk_set_forgotten)
                actions.background(failed) {
                    graph.speakers.deleteAllVoiceprints()
                    actions.message(done)
                }
            },
            onDismiss = onDismiss,
        )
        VoicesDialog.ForgetAfterOff -> ForgetAfterOffDialog(
            onDelete = {
                onDismiss()
                val done = graph.context.getString(R.string.spk_set_forgotten)
                actions.background(failed) {
                    graph.speakers.deleteAllVoiceprints()
                    actions.message(done)
                }
            },
            onKeep = onDismiss,
        )
        VoicesDialog.DeleteModels -> ConfirmDialog(
            title = stringResource(R.string.spk_set_delete_models_title),
            text = stringResource(R.string.spk_set_delete_models_body),
            confirmLabel = stringResource(R.string.spk_set_delete),
            onConfirm = {
                onDismiss()
                val done = graph.context.getString(R.string.spk_set_models_deleted)
                actions.background(failed) {
                    graph.speakers.deleteModels()
                    actions.message(done)
                }
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ForgetAfterOffDialog(onDelete: () -> Unit, onKeep: () -> Unit) {
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = c.surface,
        shape = R16,
        title = { Text(stringResource(R.string.spk_set_forget_title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = { Text(stringResource(R.string.spk_set_forget_after_off), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) },
        confirmButton = { QuietButton(stringResource(R.string.spk_set_delete_voiceprints), onClick = onDelete, color = c.danger) },
        dismissButton = { QuietButton(stringResource(R.string.spk_set_keep), onClick = onKeep, color = c.inkMuted) },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    var text by rememberSaveable { mutableStateOf(initial) }
    val clean = NameSuggestions.clean(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = R16,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(NameSuggestions.MAX_NAME * 2) },
                singleLine = true,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (clean.isNotEmpty()) onConfirm(clean) }),
                shape = R8,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.outline,
                    focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
                    focusedLabelColor = c.accent, unfocusedLabelColor = c.inkMuted,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { QuietButton(confirm, onClick = { onConfirm(clean) }, enabled = clean.isNotEmpty()) },
        dismissButton = { QuietButton(stringResource(R.string.spk_cancel), onClick = onDismiss, color = c.inkMuted) },
    )
}

@Composable
private fun MergePersonDialog(from: Person, targets: List<Person>, onMerge: (Person) -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = R16,
        title = { Text(stringResource(R.string.spk_set_merge_title, from.name), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spk_set_merge_body, from.name), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                for (t in targets) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.minTouch)
                            .clip(R8)
                            .clickable(role = Role.Button) { onMerge(t) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpeakerDot(t.colorIndex)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (t.isMe) stringResource(R.string.spk_me_label, t.name) else t.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { QuietButton(stringResource(R.string.spk_cancel), onClick = onDismiss, color = c.inkMuted) },
    )
}
