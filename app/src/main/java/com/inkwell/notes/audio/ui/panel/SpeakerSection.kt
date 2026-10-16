package com.inkwell.notes.audio.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.speakers.DiarizationPhase
import com.inkwell.notes.speakers.SpeakerModelState
import com.inkwell.notes.speakers.ui.SpeakerDot
import com.inkwell.notes.speakers.ui.voiceDescription
import com.inkwell.notes.speakers.ui.voiceName
import com.inkwell.notes.speakers.ui.logic.SpeakerNumbers
import com.inkwell.notes.speakers.ui.logic.SpeakerStatus
import com.inkwell.notes.speakers.ui.logic.SpeakerStatusLogic
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** Everything the speaker part of a recording's detail shows. */
internal data class SpeakerSectionState(
    val prefs: SpeakerPrefs,
    val speakers: List<RecordingSpeaker>,
    val shares: Map<String, Int>,
    val phase: DiarizationPhase,
    val models: SpeakerModelState,
    val filter: String?,
    val note: String?,
) {
    val enabled: Boolean get() = prefs.engine != SpeakerEngine.OFF
}

/** Who's speaking: identification status, the voices with their share of talk time, and the voice filter. */
internal fun LazyListScope.speakerItems(vm: RecordingsViewModel, rec: Recording, s: SpeakerSectionState) {
    if (!s.enabled) return
    val hasAudio = rec.segmentFiles.isNotEmpty() && rec.durationMs > 0
    val status = SpeakerStatusLogic.of(s.prefs.engine, s.phase, s.models, s.speakers.isNotEmpty(), hasAudio, s.prefs.downloadOnUnmeteredOnly)
    if (status != SpeakerStatus.Hidden) {
        item(key = "speaker-status") { SpeakerStatusCard(vm, rec, status) }
    }
    if (s.speakers.isNotEmpty()) {
        item(key = "speaker-chips") { SpeakerChips(vm, rec, s) }
    }
    s.note?.let { note ->
        item(key = "speaker-note") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, Modifier.padding(top = 2.dp).size(14.dp), tint = Inkwell.colors.inkMuted)
                Spacer(Modifier.width(6.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted)
            }
        }
    }
}

/** "3 new voices · Name them" at the top of a recording. */
@Composable
internal fun NamingBanner(unnamed: Int, onName: () -> Unit, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val voices = pluralStringResource(R.plurals.spk_new_voices, unnamed, unnamed)
    val cd = stringResource(R.string.spk_banner_cd, voices)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouch + 8.dp)
            .clip(R12)
            .background(c.accentContainer)
            .clickable(role = Role.Button, onClickLabel = cd, onClick = onName)
            .padding(start = 14.dp, end = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.RecordVoiceOver, null, Modifier.size(Dimens.icon), tint = c.ink)
        Spacer(Modifier.width(10.dp))
        Text(voices, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.spk_name_them),
            style = MaterialTheme.typography.labelLarge,
            color = c.accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SpeakerStatusCard(vm: RecordingsViewModel, rec: Recording, status: SpeakerStatus) {
    val c = Inkwell.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(R12)
            .border(1.dp, c.outline, R12)
            .padding(16.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (status) {
            is SpeakerStatus.Analysing -> {
                Text(
                    if (status.total > 0) stringResource(R.string.spk_identifying_progress, (status.done + 1).coerceAtMost(status.total), status.total)
                    else stringResource(R.string.spk_identifying),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
                Progress(if (status.total > 0) status.done.toFloat() / status.total else null)
            }
            SpeakerStatus.Queued -> {
                Text(stringResource(R.string.spk_identify_queued), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                Progress(null)
            }
            is SpeakerStatus.Downloading -> {
                val done = SpeakerNumbers.megabytes(status.bytesDone).toInt()
                val total = SpeakerNumbers.megabytes(status.bytesTotal).toInt()
                Text(
                    if (status.bytesTotal > 0) stringResource(R.string.spk_models_downloading, done, total)
                    else stringResource(R.string.spk_models_downloading_unknown, done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
                Progress(SpeakerNumbers.fraction(status.bytesDone, status.bytesTotal))
            }
            is SpeakerStatus.WaitingForModels -> {
                Text(
                    stringResource(if (status.wifiOnly) R.string.spk_models_waiting_wifi else R.string.spk_models_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
                Progress(null)
            }
            is SpeakerStatus.ModelsFailed -> {
                Text(stringResource(R.string.spk_models_failed, status.message), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                AudioSecondaryButton(stringResource(R.string.spk_try_again), onClick = { vm.identifySpeakers(rec, replace = true) })
            }
            is SpeakerStatus.Failed -> {
                Text(stringResource(R.string.spk_identify_failed), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkMuted) }
                if (status.canRetry) AudioSecondaryButton(stringResource(R.string.spk_try_again), onClick = { vm.identifySpeakers(rec, replace = true) })
            }
            is SpeakerStatus.NoneFound -> {
                Text(stringResource(R.string.spk_identify_none), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                if (status.canRetry) AudioSecondaryButton(stringResource(R.string.spk_identify_again), onClick = { vm.identifySpeakers(rec, replace = true) })
            }
            is SpeakerStatus.Offer -> {
                Text(stringResource(R.string.spk_identify_title), style = MaterialTheme.typography.titleSmall, color = c.ink)
                Text(stringResource(R.string.spk_identify_body), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                if (status.needsDownload) {
                    Text(
                        stringResource(R.string.spk_identify_needs_models, SpeakerStatusLogic.MODEL_DOWNLOAD_MB),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkMuted,
                    )
                }
                // Secondary: transcription stays the recording's main action.
                AudioSecondaryButton(
                    stringResource(if (status.needsDownload) R.string.spk_identify_download_action else R.string.spk_identify_action),
                    onClick = { vm.identifySpeakers(rec, replace = false) },
                    color = c.accent,
                )
            }
            SpeakerStatus.Hidden -> Unit
        }
        if (status.cancellable) AudioSecondaryButton(stringResource(R.string.spk_cancel), onClick = { vm.cancelIdentify(rec) })
    }
}

@Composable
private fun Progress(fraction: Float?) {
    val c = Inkwell.colors
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
    }
}

@Composable
private fun SpeakerChips(vm: RecordingsViewModel, rec: Recording, s: SpeakerSectionState) {
    val c = Inkwell.colors
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.spk_speakers_title), style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
            AudioIconButton(Icons.Outlined.Edit, stringResource(R.string.spk_naming_title), { vm.namingFor = rec.id })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (sp in s.speakers) {
                SpeakerChip(
                    vm = vm,
                    s = sp,
                    share = s.shares[sp.label],
                    selected = s.filter == sp.label,
                    canMerge = s.speakers.size > 1,
                )
            }
        }
        val only = s.filter?.let { f -> s.speakers.firstOrNull { it.label == f } }
        if (only != null) FilterBar(vm, rec, only)
    }
}

@Composable
private fun SpeakerChip(vm: RecordingsViewModel, s: RecordingSpeaker, share: Int?, selected: Boolean, canMerge: Boolean) {
    val c = Inkwell.colors
    var menu by remember { mutableStateOf(false) }
    val voice = SpeakerTranscript.voice(s)
    val name = voiceName(voice)
    val shareText = share?.let { if (it == 0) stringResource(R.string.spk_share_small) else stringResource(R.string.spk_share, it) }
    val description = voiceDescription(voice).let { d -> if (shareText != null) stringResource(R.string.spk_chip_cd, d, shareText) else d }
    val clickLabel = stringResource(if (selected) R.string.spk_chip_filter_on else R.string.spk_chip_filter_off, voice.name)
    val menuLabel = stringResource(R.string.spk_chip_menu, voice.name)
    Box {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(R8)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = clickLabel,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menu = true },
                    onClick = { vm.toggleSpeakerFilter(s.label) },
                )
                .semantics {
                    contentDescription = description
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(R8)
                    .background(if (selected) c.accentContainer else c.surface)
                    .border(1.dp, if (selected) c.accent else c.outline, R8)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpeakerDot(s.colorIndex, size = 8.dp)
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium.let { if (voice.tentative) it.copy(fontStyle = FontStyle.Italic) else it },
                    color = if (voice.tentative) c.inkMuted else c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (shareText != null) {
                    Text(shareText, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = c.inkMuted, maxLines = 1)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
            MenuItem(
                if (s.personId != null) stringResource(R.string.spk_rename_voice) else stringResource(R.string.spk_name_voice),
                Icons.Outlined.Edit,
            ) {
                menu = false
                vm.speakerDialog = SpeakerDialog.Name(s)
            }
            if (voice.tentative) {
                MenuItem(stringResource(R.string.spk_confirm_person, voice.name), Icons.Outlined.Check) {
                    menu = false
                    vm.confirmVoice(s)
                }
            }
            if (s.personId != null) {
                MenuItem(stringResource(R.string.spk_not_person, voice.name), Icons.Outlined.PersonOff) {
                    menu = false
                    vm.assignVoice(s, null)
                }
            }
            if (canMerge) {
                MenuItem(stringResource(R.string.spk_merge_voice), Icons.AutoMirrored.Outlined.MergeType) {
                    menu = false
                    vm.speakerDialog = SpeakerDialog.Merge(s)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    val c = Inkwell.colors
    DropdownMenuItem(
        text = { Text(label, color = c.ink) },
        leadingIcon = { Icon(icon, null, Modifier.size(Dimens.icon), tint = c.inkMuted) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = Dimens.minTouch),
    )
}

/** "Only Priya" with previous / next passage of that voice and "Show everyone". */
@Composable
private fun FilterBar(vm: RecordingsViewModel, rec: Recording, s: RecordingSpeaker) {
    val c = Inkwell.colors
    val name = s.displayName
    Row(
        Modifier.fillMaxWidth().clip(R8).background(c.surfaceAlt).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerDot(s.colorIndex, size = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.spk_filter_label, voiceName(s)),
            style = MaterialTheme.typography.labelLarge,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AudioIconButton(Icons.Outlined.SkipPrevious, stringResource(R.string.spk_prev_block, name), { vm.seekToVoice(rec, forward = false) }, tint = c.ink)
        AudioIconButton(Icons.Outlined.SkipNext, stringResource(R.string.spk_next_block, name), { vm.seekToVoice(rec, forward = true) }, tint = c.ink)
        AudioChip(label = stringResource(R.string.spk_filter_clear), onClick = { vm.speakerFilter.value = null }, modifier = Modifier.padding(end = 4.dp))
    }
}
