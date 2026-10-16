package com.inkwell.notes.audio.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.logic.TranscriptAction
import com.inkwell.notes.audio.logic.TranscriptFilter
import com.inkwell.notes.audio.transcription.TranscriptPhase
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.ui.SpeakerLabel
import com.inkwell.notes.speakers.ui.voiceDescription
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal fun actionLabelRes(a: TranscriptAction): Int = when (a) {
    TranscriptAction.SUMMARIZE -> R.string.audio_ai_summarize
    TranscriptAction.DECISIONS -> R.string.audio_ai_decisions
    TranscriptAction.KEY_TOPICS -> R.string.audio_ai_key_topics
    TranscriptAction.ACTION_ITEMS -> R.string.audio_ai_action_items
    TranscriptAction.BY_SPEAKER -> R.string.audio_ai_by_speaker
    TranscriptAction.REVISION_NOTES -> R.string.audio_ai_revision_notes
    TranscriptAction.EXPLAIN -> R.string.audio_ai_explain
    TranscriptAction.COMPARE -> R.string.audio_ai_compare
}

/**
 * Transcript part of the recording detail: transcribe / progress / AI actions / search / blocks.
 * Blocks show who is speaking once per stretch of speech; with [speakersEnabled] a long press
 * changes a block's speaker ([onChangeSpeaker]).
 */
internal fun LazyListScope.transcriptItems(
    vm: RecordingsViewModel,
    rec: Recording,
    phase: TranscriptPhase,
    availability: AiAvailability,
    hasTranscript: Boolean,
    rows: List<SpeakerTranscript.Row>,
    query: String,
    activeIndex: Int,
    onSegment: (index: Int) -> Unit,
    speakersEnabled: Boolean = false,
    filtered: Boolean = false,
    onChangeSpeaker: (TranscriptSegment) -> Unit = {},
) {
    item(key = "transcript-title") {
        Text(
            stringResource(R.string.audio_transcript),
            style = MaterialTheme.typography.titleSmall,
            color = Inkwell.colors.ink,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
    }
    when {
        phase.busy -> item(key = "transcript-progress") { ProgressCard(phase, onCancel = { vm.cancelTranscription(rec) }) }
        !hasTranscript -> item(key = "transcript-start") {
            TranscribeCard(
                vm, rec, availability,
                failure = when (phase) {
                    is TranscriptPhase.Failed -> phase.message ?: stringResource(R.string.audio_transcribe_failed)
                    TranscriptPhase.Interrupted -> stringResource(R.string.audio_transcribe_interrupted)
                    else -> null
                },
                emptyResult = phase == TranscriptPhase.Done,
            )
        }
    }
    if (!hasTranscript) return

    if (availability.summarize) {
        item(key = "ai-actions") { AiActions(vm) }
    }
    item(key = "search") { TranscriptSearch(query, rows.size, onQuery = { vm.query.value = it }) }
    if (rows.isEmpty() && (query.isNotBlank() || filtered)) {
        item(key = "no-matches") {
            Text(
                stringResource(R.string.audio_transcript_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = Inkwell.colors.inkMuted,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
    items(rows, key = { "seg-${it.match.index}" }) { r ->
        SegmentRow(
            r,
            active = r.match.index == activeIndex,
            onClick = { onSegment(r.match.index) },
            onLongClick = if (speakersEnabled) ({ onChangeSpeaker(r.match.segment) }) else null,
        )
    }
    if (phase == TranscriptPhase.Done || phase is TranscriptPhase.Failed || phase == TranscriptPhase.Interrupted) {
        if (availability.transcribe) {
            item(key = "retranscribe") {
                AudioChip(
                    label = stringResource(R.string.audio_transcribe_again),
                    onClick = { vm.requestTranscription(rec) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TranscribeCard(vm: RecordingsViewModel, rec: Recording, availability: AiAvailability, failure: String?, emptyResult: Boolean) {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxWidth().clip(R12).border(1.dp, c.outline, R12).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (emptyResult) {
            Text(stringResource(R.string.audio_transcript_empty), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        if (failure != null) {
            Text(failure, style = MaterialTheme.typography.bodyMedium, color = c.danger)
        }
        if (availability.transcribe) {
            if (!emptyResult) Text(stringResource(R.string.audio_transcribe_title), style = MaterialTheme.typography.titleSmall, color = c.ink)
            val minutes = vm.transcriptionMinutes(rec)
            val cost = vm.transcriptionCost(rec)
            val model = availability.transcriptionModelName ?: availability.transcriptionModel?.modelId.orEmpty()
            Text(
                if (cost != null) stringResource(R.string.audio_transcribe_estimate, minutes, cost, model)
                else stringResource(R.string.audio_transcribe_estimate_unknown, minutes, stringResource(R.string.audio_price_unknown)),
                style = MaterialTheme.typography.bodySmall,
                color = c.inkMuted,
            )
            if (availability.cloudSpeakers) {
                Text(stringResource(R.string.spk_cloud_speakers_note), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            }
            Spacer(Modifier.height(4.dp))
            AudioPrimaryButton(
                stringResource(if (failure != null) R.string.audio_transcribe_retry else if (emptyResult) R.string.audio_transcribe_again else R.string.audio_transcribe),
                onClick = { vm.requestTranscription(rec) },
            )
        } else {
            Text(
                stringResource(if (availability.aiEnabled) R.string.audio_transcribe_needs_key else R.string.audio_transcribe_ai_off),
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
            )
        }
    }
}

@Composable
private fun ProgressCard(phase: TranscriptPhase, onCancel: () -> Unit) {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxWidth().clip(R12).border(1.dp, c.outline, R12).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val label = when (phase) {
            is TranscriptPhase.Running -> if (phase.total > 0) stringResource(R.string.audio_transcribing_progress, (phase.done + 1).coerceAtMost(phase.total), phase.total)
            else stringResource(R.string.audio_transcribing_start)
            TranscriptPhase.Queued -> stringResource(if (rememberOnline()) R.string.audio_status_queued else R.string.audio_transcribing_waiting)
            else -> ""
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        if (phase is TranscriptPhase.Running && phase.total > 0) {
            LinearProgressIndicator(
                progress = { (phase.done.toFloat() / phase.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = c.accent,
                trackColor = c.surfaceAlt,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
        }
        AudioSecondaryButton(stringResource(R.string.audio_cancel), onClick = onCancel)
    }
}

@Composable
private fun AiActions(vm: RecordingsViewModel) {
    val c = Inkwell.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = c.inkMuted)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.audio_ai_title), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in TranscriptAction.entries) {
                AudioChip(
                    label = stringResource(actionLabelRes(a)),
                    selected = a == TranscriptAction.EXPLAIN && vm.pickingPassage,
                    onClick = {
                        if (a == TranscriptAction.EXPLAIN && vm.pickingPassage) vm.pickingPassage = false else vm.runAction(a)
                    },
                )
            }
        }
        if (vm.pickingPassage) {
            Text(stringResource(R.string.audio_ai_pick_passage), style = MaterialTheme.typography.bodySmall, color = c.accent)
        }
    }
}

@Composable
private fun TranscriptSearch(query: String, count: Int, onQuery: (String) -> Unit) {
    val c = Inkwell.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.audio_transcript_search), style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(Dimens.icon), tint = c.inkMuted) },
            trailingIcon = if (query.isNotEmpty()) {
                { AudioIconButton(Icons.Outlined.Close, stringResource(R.string.audio_transcript_clear), { onQuery("") }) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = R8,
            colors = audioFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.isNotBlank() && count > 0) {
            Text(
                pluralStringResource(R.plurals.audio_transcript_matches, count, count),
                style = MaterialTheme.typography.labelSmall,
                color = c.inkMuted,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/**
 * One transcript block. The speaker's dot and name head each stretch of one voice; TalkBack
 * hears the name with every block. Long press (when speakers are on) changes the speaker.
 */
@Composable
private fun SegmentRow(r: SpeakerTranscript.Row, active: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val c = Inkwell.colors
    val m = r.match
    val time = AudioText.clock(m.segment.startMs)
    val label = stringResource(R.string.audio_segment_cd, time)
    val voice = r.voice
    val spoken = voice?.let { stringResource(R.string.spk_block_cd, voiceDescription(it), time, m.segment.text) }
    val changeLabel = stringResource(R.string.spk_change_speaker)
    Column(Modifier.fillMaxWidth().padding(top = if (r.startsGroup) 10.dp else 0.dp)) {
        if (r.startsGroup && voice != null) {
            SpeakerLabel(voice, Modifier.padding(start = 8.dp, bottom = 2.dp).clearAndSetSemantics { })
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(R8)
                .background(if (active) c.accentContainer else c.surface)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClickLabel = if (onLongClick != null) changeLabel else null,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                time,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = c.accent,
                modifier = Modifier.width(56.dp).padding(top = 2.dp),
            )
            Text(highlighted(m, c.accent.copy(alpha = 0.22f), c.ink), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
    }
}

private fun highlighted(m: TranscriptFilter.Match, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color): AnnotatedString {
    val text = m.segment.text
    if (m.ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (r in m.ranges) {
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            addStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.SemiBold), start, end)
        }
    }
}

/** Whether the device has a usable network right now (read when the card is composed). */
@Composable
private fun rememberOnline(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
    }
}

@Composable
internal fun audioFieldColors() = Inkwell.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.outline,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
        focusedPlaceholderColor = c.inkMuted, unfocusedPlaceholderColor = c.inkMuted,
    )
}
