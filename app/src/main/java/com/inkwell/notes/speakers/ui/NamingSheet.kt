package com.inkwell.notes.speakers.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.ui.panel.AudioIconButton
import com.inkwell.notes.audio.ui.panel.AudioPrimaryButton
import com.inkwell.notes.audio.ui.panel.AudioSecondaryButton
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.speakers.ui.logic.NamingDraft
import com.inkwell.notes.speakers.ui.logic.NamingOp
import com.inkwell.notes.speakers.ui.logic.NamingPlan
import com.inkwell.notes.speakers.ui.logic.SpeakerNumbers
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val R16 = RoundedCornerShape(16.dp)
private val R8 = RoundedCornerShape(8.dp)

/**
 * "Who was speaking?" after a recording: every voice with its colour, how long it spoke, a
 * sample to listen to, and a name field that suggests known people. Automatic matches are
 * offered as "Sounds like Priya" with Confirm / Not them. Nothing is written until Done (or an
 * explicit Confirm / Not them); Skip stops asking about this recording.
 */
@Composable
fun NamingSheet(recordingId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val vm: NamingViewModel = viewModel(key = "inkwell-naming:$recordingId") {
        NamingViewModel(graph, recordingId, runCatching { createSavedStateHandle() }.getOrElse { SavedStateHandle() })
    }
    LaunchedEffect(vm) { vm.begin() }
    val close: () -> Unit = {
        vm.end()
        onClose()
    }
    Dialog(
        onDismissRequest = close,
        // Typing names must not be lost to a stray tap beside the sheet.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        NamingContent(vm, close)
    }
}

internal class NamingViewModel(private val graph: AppGraph, val recordingId: String, private val handle: SavedStateHandle) : ViewModel() {
    val speakers: StateFlow<List<RecordingSpeaker>?> = graph.speakers.speakers(recordingId)
        .map<List<RecordingSpeaker>, List<RecordingSpeaker>?> { SpeakerTranscript.inOrder(it) }
        .catch { emit(emptyList()) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    val people: StateFlow<List<Person>> = graph.speakers.people()
        .catch { emit(emptyList()) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    val recording: StateFlow<Recording?> = graph.db.recordings().observe(recordingId)
        .map { it?.toModel() }
        .catch { emit(null) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    val prefs: StateFlow<SpeakerPrefs> = graph.settings.settings.map { it.speakers }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), graph.settings.current.speakers)

    var drafts by mutableStateOf(restore())
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** Voices ticked "This is me" without a name; "Done" asks for one instead of dropping the tick. */
    var invalid by mutableStateOf<Set<String>>(emptySet())
        private set

    val sample = SamplePlayer(graph, viewModelScope)

    private val editor = SpeakerEditor(graph) { error = graph.context.getString(R.string.spk_action_failed) }

    /**
     * Follows voices arriving or changing while the sheet is open. Only while it is open: the view
     * model outlives the sheet (it is scoped to the screen), and a closed sheet must not keep
     * observing the database.
     */
    private var follow: Job? = null

    /** The sheet opened: start from the stored names unless it is coming back after process death. */
    fun begin() {
        if (follow == null) {
            follow = viewModelScope.launch {
                combine(speakers.filterNotNull(), people) { s, p -> s to p.firstOrNull { it.isMe }?.id }.collect { (s, meId) ->
                    drafts = NamingPlan.merge(drafts, s, meId)
                    persist()
                }
            }
        }
        if (handle.get<Boolean>(KEY_OPEN) == true) return
        handle[KEY_OPEN] = true
        drafts = NamingPlan.initial(speakers.value.orEmpty(), people.value.firstOrNull { it.isMe }?.id)
        error = null
        persist()
    }

    /** The sheet closed: drop unsaved edits and stop a sample. */
    fun end() {
        follow?.cancel()
        follow = null
        sample.stop()
        handle[KEY_OPEN] = false
        handle.remove<String>(KEY_DRAFTS)
        drafts = emptyMap()
        error = null
        invalid = emptySet()
    }

    fun edit(label: String, text: String) = update(NamingPlan.edit(drafts, label, text, people.value))

    fun pick(label: String, person: Person) = update(NamingPlan.pick(drafts, label, person))

    fun toggleMe(label: String, on: Boolean) = update(NamingPlan.toggleMe(drafts, label, on, people.value.firstOrNull { it.isMe }))

    /** Accepts an automatic match right away. */
    fun confirm(s: RecordingSpeaker) {
        editor.confirm(recordingId, s.label)
    }

    /** "Not them": the voice goes back to unnamed and its field opens. */
    fun reject(s: RecordingSpeaker) {
        editor.assign(recordingId, s.label, null)
        update(drafts + (s.label to NamingDraft(touched = true)))
    }

    fun playSample(s: RecordingSpeaker) {
        val rec = recording.value ?: return
        sample.toggle(rec, s.label, s.sampleStartMs, s.sampleEndMs)
    }

    fun done(onClosed: () -> Unit) {
        if (saving) return
        val list = speakers.value.orEmpty()
        val missing = NamingPlan.missingNames(list, drafts)
        if (missing.isNotEmpty()) {
            invalid = missing
            return
        }
        apply(NamingPlan.operations(list, drafts), onClosed)
    }

    /** Whether the secondary action saves anything before dismissing ("Save, skip the rest"). */
    val hasChanges: Boolean get() = NamingPlan.hasChanges(speakers.value.orEmpty(), drafts)

    /**
     * "Skip" / "Save, skip the rest": names the user already entered are saved, only the voices
     * left untouched are dismissed. A "This is me" tick without a name still needs one.
     */
    fun skip(onClosed: () -> Unit) {
        if (saving) return
        val list = speakers.value.orEmpty()
        val missing = NamingPlan.missingNames(list, drafts)
        if (missing.isNotEmpty()) {
            invalid = missing
            return
        }
        apply(NamingPlan.skipOperations(list, drafts), onClosed)
    }

    private fun apply(ops: List<NamingOp>, onClosed: () -> Unit) {
        sample.stop()
        if (ops.isEmpty()) {
            onClosed()
            return
        }
        saving = true
        error = null
        editor.apply(
            recordingId, ops,
            onDone = {
                saving = false
                onClosed()
            },
            onFailure = {
                saving = false
                error = graph.context.getString(R.string.spk_naming_failed)
            },
        )
    }

    private fun update(next: Map<String, NamingDraft>) {
        drafts = next
        invalid = emptySet()
        persist()
    }

    private fun persist() {
        if (handle.get<Boolean>(KEY_OPEN) != true) return
        handle[KEY_DRAFTS] = runCatching { JSON.encodeToString(SERIALIZER, drafts) }.getOrNull()
    }

    private fun restore(): Map<String, NamingDraft> {
        val raw = handle.get<String>(KEY_DRAFTS) ?: return emptyMap()
        return runCatching { JSON.decodeFromString(SERIALIZER, raw) }.getOrDefault(emptyMap())
    }

    override fun onCleared() {
        sample.stop()
    }

    private companion object {
        /** Keep observing briefly after the sheet stops collecting (rotation), then let go. */
        const val STOP_MS = 5_000L
        const val KEY_OPEN = "open"
        const val KEY_DRAFTS = "drafts"
        val SERIALIZER = MapSerializer(String.serializer(), NamingDraft.serializer())
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Composable
private fun NamingContent(vm: NamingViewModel, close: () -> Unit) {
    val c = Inkwell.colors
    val context = LocalContext.current
    val speakers by vm.speakers.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val playing by vm.sample.playing.collectAsStateWithLifecycle()
    var explain by remember { mutableStateOf(!SpeakerUiMemory.explained(context)) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    val list = speakers
    val unnamed = list.orEmpty().any { it.personId == null }

    fun finish() {
        // The explainer counts as seen only if it was shown (it is replaced while voices aren't remembered).
        if (explain && prefs.rememberVoices) {
            SpeakerUiMemory.markExplained(context)
            explain = false
        }
        close()
    }

    Surface(
        Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
        shape = R16,
        color = c.surface,
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.spk_naming_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    recording?.title?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                AudioIconButton(Icons.Outlined.Close, stringResource(R.string.spk_naming_close), close)
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!prefs.rememberVoices) {
                    SheetNote(Icons.Outlined.Info, stringResource(R.string.spk_not_remembered))
                } else if (explain) {
                    SheetNote(Icons.Outlined.Lock, stringResource(R.string.spk_explainer))
                }
            }
            HorizontalDivider(color = c.outline)
            Box(Modifier.weight(1f, fill = false)) {
                when {
                    list == null -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.spk_naming_loading), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                    }
                    list.isEmpty() -> Text(
                        stringResource(R.string.spk_naming_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.inkMuted,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(list, key = { it.label }) { s ->
                            VoiceRow(
                                s = s,
                                draft = vm.drafts[s.label] ?: NamingDraft(),
                                people = people,
                                nameError = if (s.label in vm.invalid) stringResource(R.string.spk_enter_your_name) else null,
                                playing = playing == s.label,
                                canPlay = recording != null,
                                onPlay = { vm.playSample(s) },
                                onEdit = { vm.edit(s.label, it) },
                                onPick = { vm.pick(s.label, it) },
                                onMe = { vm.toggleMe(s.label, it) },
                                onConfirm = { vm.confirm(s) },
                                onReject = { vm.reject(s) },
                            )
                            HorizontalDivider(color = c.outline, modifier = Modifier.padding(start = 24.dp))
                        }
                    }
                }
            }
            vm.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (unnamed) {
                    // Typed names are saved by this button too; only the untouched voices are skipped.
                    AudioSecondaryButton(
                        stringResource(if (vm.hasChanges) R.string.spk_save_skip_rest else R.string.spk_skip),
                        onClick = { vm.skip(::finish) },
                        enabled = !vm.saving && list != null,
                    )
                }
                Spacer(Modifier.weight(1f))
                AudioPrimaryButton(
                    stringResource(if (vm.saving) R.string.spk_saving else R.string.spk_done),
                    onClick = { vm.done(::finish) },
                    enabled = !vm.saving && list != null,
                )
            }
        }
    }
}

@Composable
private fun SheetNote(icon: ImageVector, text: String) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().clip(R8).background(c.surfaceAlt).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = c.inkMuted)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
    }
}

@Composable
private fun VoiceRow(
    s: RecordingSpeaker,
    draft: NamingDraft,
    people: List<Person>,
    nameError: String?,
    playing: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onEdit: (String) -> Unit,
    onPick: (Person) -> Unit,
    onMe: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    val c = Inkwell.colors
    val voiceTitle = SpeakerTranscript.labelNumber(s.label)?.let { stringResource(R.string.spk_voice_number, it) } ?: s.label
    val tentative = SpeakerTranscript.isTentative(s) && !draft.touched
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeakerDot(s.colorIndex, size = 12.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.spk_voice_line, voiceTitle, speechDuration(s.speechMs)),
                style = MaterialTheme.typography.titleSmall,
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            AudioIconButton(
                if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                if (playing) stringResource(R.string.spk_stop_sample) else stringResource(R.string.spk_play_sample, voiceTitle),
                onPlay,
                enabled = canPlay && s.sampleEndMs > s.sampleStartMs,
                tint = c.accent,
            )
        }
        if (tentative) {
            val confidence = s.confidence
            Text(
                if (confidence != null) stringResource(R.string.spk_sounds_like_pct, s.displayName, SpeakerNumbers.percent(confidence))
                else stringResource(R.string.spk_sounds_like, s.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioPrimaryButton(stringResource(R.string.spk_confirm), onClick = onConfirm)
                AudioSecondaryButton(stringResource(R.string.spk_not_them), onClick = onReject)
            }
        } else {
            PersonNameField(
                value = draft.text,
                onValueChange = onEdit,
                people = people,
                onPick = onPick,
                pickedId = draft.personId,
                modifier = Modifier.padding(end = 12.dp),
            )
            if (nameError != null) {
                Text(
                    nameError,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.danger,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            MeToggle(checked = draft.isMe, onChange = onMe)
        }
    }
}

@Composable
private fun MeToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier.clip(R8).clickable(role = Role.Checkbox) { onChange(!checked) }.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.inkMuted, checkmarkColor = c.onAccent),
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
        Text(stringResource(R.string.spk_this_is_me), style = MaterialTheme.typography.bodyMedium, color = c.ink)
    }
}
