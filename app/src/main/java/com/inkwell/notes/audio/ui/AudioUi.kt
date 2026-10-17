package com.inkwell.notes.audio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inkwell.notes.audio.ui.dock.RecordingDockImpl
import com.inkwell.notes.audio.ui.panel.RecordingsPanelImpl

/**
 * The recording control overlay for the editor. Fill the editor's area with it (it only
 * draws at the docked edge). Shows a compact capsule while recording that can be dragged
 * or flung into the left/right edge, collapsing to a slim sliver with a recording dot and a
 * larger invisible touch target; tap/swipe brings it back. Collapsing never affects recording.
 * While idle it renders nothing except, after a recording of [docId] finished here, a small
 * dismissible card in the bottom corner offering to name new voices (opens the naming sheet).
 * It never intercepts touches outside the control or that card.
 */
@Composable
fun RecordingDock(docId: String, modifier: Modifier = Modifier) {
    RecordingDockImpl(docId, modifier)
}

/**
 * Recordings of a document: list, rename, export, waveform playback with speed, transcript
 * with search, "jump to notes written here" ([onShowNotesAt]) and transcription.
 * A 420dp side panel on tablets (or the slot it is given), full width on phones.
 * [onInsert], when given, adds an "Insert" button to finished AI results (summaries, action
 * items...) that places the text into the document; it is only ever called on that tap.
 */
@Composable
fun RecordingsPanel(
    docId: String,
    onShowNotesAt: (recordingId: String, positionMs: Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onInsert: ((String) -> Unit)? = null,
) {
    RecordingsPanelImpl(docId, onShowNotesAt, onClose, modifier, onInsert)
}
