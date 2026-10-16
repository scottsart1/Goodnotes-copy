package com.inkwell.notes.audio.recorder

import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.core.model.AudioAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level recording state shared by the controller, the foreground service, the
 * recorder dock and the audio clock, so all of them always agree with what the microphone
 * is actually doing.
 */
internal object RecordingRuntime {
    val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()

    /** The recording in progress, or null. */
    @Volatile var session: RecordingSession? = null

    @Volatile private var engine: RecordingEngine? = null

    fun engine(graph: AppGraph): RecordingEngine =
        engine ?: synchronized(this) { engine ?: RecordingEngine(graph).also { engine = it } }

    /** The engine if one was created in this process (the service never creates one on its own). */
    fun existingEngine(): RecordingEngine? = engine

    /** "Now" on [docId]'s recording timeline while it is recording or paused; else null. */
    fun anchorFor(docId: String): AudioAnchor? {
        val s = session ?: return null
        if (s.docId != docId || s.finishing) return null
        return AudioAnchor(s.recordingId, s.timelineMs())
    }
}
