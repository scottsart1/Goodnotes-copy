package com.inkwell.notes.audio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The recording control overlay for the editor. Fill the editor's area with it (it only
 * draws at the docked edge). Shows a compact capsule while recording that can be dragged
 * or flung into the left/right edge, collapsing to a slim sliver with a recording dot and a
 * larger invisible touch target; tap/swipe brings it back. Collapsing never affects recording.
 * Renders nothing while idle. SCAFFOLD STUB — replaced by the audio implementation.
 */
@Composable
fun RecordingDock(docId: String, modifier: Modifier = Modifier) {
    Box(modifier)
}

/**
 * Recordings of a document: list, rename, export, waveform playback with speed, transcript
 * with search, "jump to notes written here" ([onShowNotesAt]) and transcription.
 * SCAFFOLD STUB — replaced by the audio implementation.
 */
@Composable
fun RecordingsPanel(
    docId: String,
    onShowNotesAt: (recordingId: String, positionMs: Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) { Text("Recordings") }
}
