package com.inkwell.notes.speakers.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.speakers.ui.logic.NameSuggestions
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

private val R16 = RoundedCornerShape(16.dp)
private val R8 = RoundedCornerShape(8.dp)

/**
 * Names one voice of a recording: a known person picked from the suggestions ([onPick]) or a
 * typed name ([onName], which reuses a person of that name or creates one).
 */
@Composable
fun NameVoiceDialog(
    speaker: RecordingSpeaker,
    people: List<Person>,
    onPick: (Person) -> Unit,
    onName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    val initial = if (speaker.personId != null) speaker.displayName else ""
    var text by rememberSaveable(speaker.recordingId, speaker.label) { mutableStateOf(initial) }
    val clean = NameSuggestions.clean(text)
    val save = {
        val picked = NameSuggestions.exact(people, clean)
        when {
            clean.isEmpty() -> Unit
            picked != null -> onPick(picked)
            else -> onName(clean)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = R16,
        title = { Text(stringResource(R.string.spk_rename_title, voiceName(speaker)), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            PersonNameField(
                value = text,
                onValueChange = { text = it },
                people = people,
                onPick = onPick,
                autoFocus = true,
                onImeAction = save,
            )
        },
        confirmButton = {
            TextButton(onClick = save, enabled = clean.isNotEmpty()) {
                Text(stringResource(R.string.spk_save), color = if (clean.isNotEmpty()) c.accent else c.inkMuted, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.spk_cancel), color = c.inkMuted, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

/**
 * "Change speaker" for one passage: another voice of this recording, a known person, or a
 * typed name. [timeLabel] is the passage's timestamp ("12:34").
 */
@Composable
fun ChangeSpeakerDialog(
    timeLabel: String,
    currentLabel: String?,
    speakers: List<RecordingSpeaker>,
    people: List<Person>,
    onVoice: (RecordingSpeaker) -> Unit,
    onPerson: (Person) -> Unit,
    onName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    var text by rememberSaveable { mutableStateOf("") }
    val clean = NameSuggestions.clean(text)
    val voices = SpeakerTranscript.inOrder(speakers).filter { it.label != currentLabel }
    // People already heard here are offered as voices above.
    val heard = speakers.mapNotNull { it.personId }.toSet()
    val others = NameSuggestions.rank(people, "", limit = 8, excludeIds = heard)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = R16,
        title = { Text(stringResource(R.string.spk_change_title, timeLabel), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (voices.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.spk_change_in_recording))
                    for (s in voices) ChoiceRow(SpeakerTranscript.voice(s).let { voiceName(it) }, s.colorIndex) { onVoice(s) }
                }
                if (others.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.spk_change_people))
                    for (p in others) ChoiceRow(if (p.isMe) stringResource(R.string.spk_me_label, p.name) else p.name, p.colorIndex) { onPerson(p) }
                }
                SectionTitle(stringResource(R.string.spk_change_someone_else))
                PersonNameField(
                    value = text,
                    onValueChange = { text = it },
                    people = people,
                    onPick = onPerson,
                    label = stringResource(R.string.spk_change_name_hint),
                    onImeAction = { if (clean.isNotEmpty()) onName(clean) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onName(clean) }, enabled = clean.isNotEmpty()) {
                Text(stringResource(R.string.spk_save), color = if (clean.isNotEmpty()) c.accent else c.inkMuted, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.spk_cancel), color = c.inkMuted, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

/** Merges voice [from] into another voice of the same recording. */
@Composable
fun MergeVoiceDialog(
    from: RecordingSpeaker,
    targets: List<RecordingSpeaker>,
    onMerge: (RecordingSpeaker) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Inkwell.colors
    val name = voiceName(from)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = R16,
        title = { Text(stringResource(R.string.spk_merge_title, name), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spk_merge_body, name), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                Spacer(Modifier.width(4.dp))
                for (t in SpeakerTranscript.inOrder(targets)) ChoiceRow(voiceName(t), t.colorIndex) { onMerge(t) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.spk_cancel), color = c.inkMuted, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Inkwell.colors.inkMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun ChoiceRow(label: String, colorIndex: Int?, onClick: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouch)
            .clip(R8)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerDot(colorIndex)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
