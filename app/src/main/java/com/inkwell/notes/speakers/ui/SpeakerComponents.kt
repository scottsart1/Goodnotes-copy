package com.inkwell.notes.speakers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.speakers.ui.logic.NameSuggestions
import com.inkwell.notes.speakers.ui.logic.SpeakerNumbers
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import com.inkwell.notes.ui.theme.Inkwell

private val R8 = RoundedCornerShape(8.dp)

/**
 * The voice's colour as a small dot. A name without a voice record (an older transcript)
 * gets a quiet outlined ring instead. Decorative: the name next to it carries the meaning.
 */
@Composable
internal fun SpeakerDot(colorIndex: Int?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val c = Inkwell.colors
    if (colorIndex == null) {
        Box(modifier.size(size).clip(CircleShape).border(1.dp, c.inkMuted.copy(alpha = 0.6f), CircleShape))
    } else {
        Box(modifier.size(size).clip(CircleShape).background(c.speaker(colorIndex)))
    }
}

/** "Priya", "Speaker 2", or "Priya?" for an automatic match not confirmed yet. */
@Composable
internal fun voiceName(v: SpeakerTranscript.Voice): String =
    if (v.tentative) stringResource(R.string.spk_tentative_name, v.name) else v.name

@Composable
internal fun voiceName(s: RecordingSpeaker): String = voiceName(SpeakerTranscript.voice(s))

/** What TalkBack says for a voice, including how sure an automatic match is. */
@Composable
internal fun voiceDescription(v: SpeakerTranscript.Voice): String {
    val confidence = v.confidence
    return when {
        v.tentative && confidence != null -> stringResource(R.string.spk_voice_match_cd, v.name, SpeakerNumbers.percent(confidence))
        v.tentative -> stringResource(R.string.spk_voice_guess_cd, v.name)
        else -> v.name
    }
}

/** "4 min", or "45 s" for less than half a minute. */
@Composable
internal fun speechDuration(ms: Long): String {
    val minutes = SpeakerNumbers.minutes(ms)
    return if (minutes > 0) pluralStringResource(R.plurals.spk_minutes, minutes, minutes)
    else SpeakerNumbers.seconds(ms).let { pluralStringResource(R.plurals.spk_seconds, it, it) }
}

/** Dot and name in one line; tentative names are muted and italic. */
@Composable
internal fun SpeakerLabel(
    voice: SpeakerTranscript.Voice,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val c = Inkwell.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SpeakerDot(voice.colorIndex, size = 8.dp)
        Text(
            voiceName(voice),
            style = if (voice.tentative) style.copy(fontStyle = FontStyle.Italic) else style,
            color = if (voice.tentative) c.inkMuted else c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun speakerFieldColors() = Inkwell.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.outline,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.inkMuted,
        focusedPlaceholderColor = c.inkMuted, unfocusedPlaceholderColor = c.inkMuted,
    )
}

/**
 * A name field that suggests known people while it has focus (prefix match, the owner first).
 * Picking a suggestion reports the person; typing reports the text.
 */
@Composable
internal fun PersonNameField(
    value: String,
    onValueChange: (String) -> Unit,
    people: List<Person>,
    onPick: (Person) -> Unit,
    modifier: Modifier = Modifier,
    pickedId: String? = null,
    label: String = stringResource(R.string.spk_name_hint),
    excludeIds: Set<String> = emptySet(),
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val suggestions = remember(people, value, pickedId, excludeIds) {
        NameSuggestions.rank(people, value, limit = 4, excludeIds = excludeIds + listOfNotNull(pickedId))
            // An exact, already picked name needs no suggestion.
            .filterNot { pickedId != null && it.id == pickedId }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.take(NameSuggestions.MAX_NAME * 2)) },
            singleLine = true,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onImeAction() }, onNext = { onImeAction() }),
            shape = R8,
            colors = speakerFieldColors(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
        )
        if (focused && suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in suggestions) SuggestionChip(p, onClick = { onPick(p) })
            }
        }
    }
}

@Composable
private fun SuggestionChip(p: Person, onClick: () -> Unit) {
    val c = Inkwell.colors
    val label = if (p.isMe) stringResource(R.string.spk_me_label, p.name) else p.name
    val cd = stringResource(R.string.spk_use_person, label)
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(R8)
            .clickable(role = Role.Button, onClickLabel = cd, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(R8)
                .background(c.surfaceAlt)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpeakerDot(p.colorIndex, size = 8.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
