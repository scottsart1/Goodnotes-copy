package com.inkwell.notes.audio.ui.dock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.core.model.DockSide
import com.inkwell.notes.core.model.ToolbarPosition
import com.inkwell.notes.speakers.ui.NamingSheet
import com.inkwell.notes.speakers.ui.SpeakerUiMemory
import com.inkwell.notes.speakers.ui.logic.NamingPromptLogic
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val R12 = RoundedCornerShape(12.dp)

/**
 * Once the voices of a recently finished recording of this document are known, a small card in
 * the bottom corner on the recorder's side offers to name the new voices. It stays clear of the
 * page centre and any docked toolbar, takes touches only on itself, never shows while anything
 * is recording, and opens the naming sheet as a dialog.
 *
 * The recording to ask about comes from the stored rows (the most recent one that finished in
 * the last 24 h and still needs naming), not from a stop seen live on this screen: so the offer
 * is also there after leaving the board before identification finished, after process death,
 * and when the "voices to name" notification opens the document. Closing the card hides it for
 * that recording until the process restarts; Skip in the sheet hides it for good.
 */
@Composable
internal fun BoxWithConstraintsScope.DockNamingPrompt(graph: AppGraph, docId: String, recording: State<RecordingState>) {
    var sheetFor by rememberSaveable(docId) { mutableStateOf<String?>(null) }

    // Recent finished recordings of this document, most recent first, with whether each still needs naming.
    val candidates by remember(docId) { namingCandidates(graph, docId) }.collectAsStateWithLifecycle(emptyList())
    val id = NamingPromptLogic.pick(candidates.map { it.first }, candidates.toMap(), SpeakerUiMemory.dismissedPrompts.toSet())

    if (id != null) {
        val settings by graph.settings.settings.collectAsStateWithLifecycle()
        val speakers by remember(id) {
            graph.speakers.speakers(id).catch { emit(emptyList()) }.flowOn(Dispatchers.IO)
        }.collectAsStateWithLifecycle(emptyList())
        val recordingActive by remember { derivedStateOf { recording.value is RecordingState.Active } }
        val unnamed = SpeakerTranscript.unnamedCount(speakers)
        val visible = NamingPromptLogic.visible(
            candidate = id,
            recordingActive = recordingActive,
            needsNaming = unnamed > 0,
            dismissed = id in SpeakerUiMemory.dismissedPrompts,
            prefs = settings.speakers,
        )

        val side = settings.recording.dockSide
        val toolbar = settings.toolbar.position
        val bottom = 16.dp + if (toolbar == ToolbarPosition.BOTTOM) 64.dp else 0.dp
        val sideClear = (toolbar == ToolbarPosition.LEFT && side == DockSide.LEFT) || (toolbar == ToolbarPosition.RIGHT && side == DockSide.RIGHT)
        val inset = 16.dp + if (sideClear) 64.dp else 0.dp
        val alignment = if (side == DockSide.RIGHT) Alignment.BottomEnd else Alignment.BottomStart
        val maxCardWidth = minOf(320.dp, maxWidth * 0.45f)

        AnimatedVisibility(
            visible = visible && sheetFor == null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(alignment)
                .padding(bottom = bottom, start = if (side == DockSide.LEFT) inset else 0.dp, end = if (side == DockSide.RIGHT) inset else 0.dp),
        ) {
            PromptCard(
                unnamed = unnamed,
                maxWidth = maxCardWidth,
                onOpen = { sheetFor = id },
                onDismiss = { if (id !in SpeakerUiMemory.dismissedPrompts) SpeakerUiMemory.dismissedPrompts += id },
            )
        }
    }
    sheetFor?.let { NamingSheet(it, onClose = { sheetFor = null }) }
}

/**
 * Recordings of [docId] that finished within the last 24 h (most recent first), each with
 * whether it still has voices to name. Empty while nothing recent exists.
 */
private fun namingCandidates(graph: AppGraph, docId: String): Flow<List<Pair<String, Boolean>>> =
    graph.db.recordings().observeForDocument(docId)
        .map { rows -> NamingPromptLogic.recent(rows.map { it.toModel() }, System.currentTimeMillis()) }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map { id -> graph.speakers.needsNaming(id).catch { emit(false) }.map { id to it } }) { it.toList() }
        }
        .catch { emit(emptyList()) }
        .flowOn(Dispatchers.IO)

@Composable
private fun PromptCard(unnamed: Int, maxWidth: androidx.compose.ui.unit.Dp, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    val voices = pluralStringResource(R.plurals.spk_new_voices, unnamed, unnamed)
    val description = stringResource(R.string.spk_prompt_cd, voices)
    val dismiss = stringResource(R.string.spk_dismiss)
    Surface(
        modifier = Modifier.widthIn(max = maxWidth),
        shape = R12,
        color = c.surface,
        border = BorderStroke(1.dp, c.outline),
        shadowElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = Dimens.minTouch + 12.dp)
                    .clickable(role = Role.Button, onClickLabel = description, onClick = onOpen)
                    .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.RecordVoiceOver, null, Modifier.size(Dimens.icon), tint = c.accent)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(voices, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.spk_prompt_body), style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 2)
                }
            }
            Box(
                Modifier
                    .size(Dimens.minTouch)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = dismiss, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, dismiss, Modifier.size(18.dp), tint = c.inkMuted)
            }
        }
    }
}
