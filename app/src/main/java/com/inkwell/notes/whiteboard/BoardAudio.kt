package com.inkwell.notes.whiteboard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.inkwell.notes.R
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.ItemRef
import com.inkwell.notes.ink.ItemType
import com.inkwell.notes.whiteboard.kit.UiMessage
import com.inkwell.notes.whiteboard.logic.AnchorSearch
import com.inkwell.notes.whiteboard.logic.BoardAnchorHit
import com.inkwell.notes.whiteboard.logic.BoardAnchors
import com.inkwell.notes.whiteboard.logic.BoardHighlight
import com.inkwell.notes.whiteboard.logic.PlaybackHighlighter
import com.inkwell.notes.whiteboard.logic.RecordControl
import com.inkwell.notes.whiteboard.logic.RecordLogic
import com.inkwell.notes.whiteboard.logic.RevealMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one recording dialog the board shows at a time. */
sealed interface RecordingDialog {
    data class MicRationale(val permanentlyDenied: Boolean) : RecordingDialog
    data object Controls : RecordingDialog
    data class Elsewhere(val otherDocId: String) : RecordingDialog
}

/**
 * Audio on the board, as notebooks have it: the Record control, playback highlighting of the
 * notes written at the playing moment, "show notes written here" and "play from here".
 * Recording itself runs in the foreground service, so it outlives this screen. Owned by
 * [WhiteboardController]; main thread only.
 */
class BoardAudio(private val c: WhiteboardController) {
    private val graph get() = c.graph

    var dialog by mutableStateOf<RecordingDialog?>(null)

    /** One event per microphone permission request; the screen shows the system prompt. */
    val micRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One event per request to open the recordings panel (e.g. after "play from here"). */
    val panelRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Canvas pixels at the right edge an overlay panel covers; reveals centre in the rest. */
    var panelCoverPx: Float = 0f

    private val highlighter = PlaybackHighlighter()
    private var holdJob: Job? = null
    private var observing = false
    private var lastTapped: Pair<String, Long>? = null

    /** Starts following recording errors and playback. Idempotent; runs until the controller's scope ends. */
    fun observe() {
        if (observing) return
        observing = true
        c.scope.launch {
            // Report errors that happen while the board is open, not a stale one from before.
            val initial = graph.recording.state.value
            graph.recording.state.collect { st ->
                if (st is RecordingState.Error && st !== initial && st.message.isNotBlank()) {
                    c.messages.tryEmit(UiMessage(R.string.wb_audio_message, listOf(st.message), long = true))
                }
            }
        }
        c.scope.launch {
            val enabled = graph.settings.settings.map { it.recording.highlightDuringPlayback }.distinctUntilChanged()
            combine(graph.playback.state, enabled) { st, on -> st to on }.collect { (st, on) ->
                val rec = st.recording?.takeIf { it.documentId == c.docId }
                if (highlighter.onPlayback(rec?.id, st.playing, st.positionMs, on, now())) applyHighlight()
            }
        }
    }

    // ------------------------------------------------------------------ Record control

    fun onRecord() {
        when (val control = RecordLogic.control(graph.recording.state.value, c.docId)) {
            is RecordControl.Here -> dialog = RecordingDialog.Controls
            is RecordControl.Elsewhere -> dialog = RecordingDialog.Elsewhere(control.otherDocId)
            RecordControl.Idle -> startOrAsk()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(graph.context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startOrAsk() {
        if (hasMicPermission()) startRecording() else requestMic()
    }

    /** Asks the screen to show the system microphone (and notification) prompt. */
    fun requestMic() {
        dialog = null
        micRequests.tryEmit(Unit)
    }

    fun onMicResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (granted) startRecording() else dialog = RecordingDialog.MicRationale(permanentlyDenied)
    }

    fun startRecording() {
        dialog = null
        try {
            graph.recording.start(c.docId, null)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Recording failed to start: ${t.javaClass.simpleName}")
            c.messages.tryEmit(UiMessage(R.string.wb_audio_start_failed))
        }
    }

    fun stopOtherAndRecordHere() {
        dialog = null
        runCatching { graph.recording.stop() }.onFailure { Log.w(TAG, "Stopping the other recording failed: ${it.javaClass.simpleName}") }
        startOrAsk()
    }

    fun togglePause(paused: Boolean) {
        dialog = null
        runCatching { if (paused) graph.recording.resume() else graph.recording.pause() }
            .onFailure { Log.w(TAG, "Pause/resume failed: ${it.javaClass.simpleName}") }
    }

    fun stop() {
        dialog = null
        runCatching { graph.recording.stop() }.onFailure { Log.w(TAG, "Stopping failed: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------ Highlight

    /** Pushes the current highlight to the canvas (after changes, or to a re-attached canvas). */
    fun applyHighlight() {
        val h = highlighter.current
        runCatching { c.canvas?.setPlaybackHighlight(h?.recordingId, h?.positionMs ?: 0L, h?.windowMs ?: BoardAnchors.WINDOW_MS) }
    }

    // ------------------------------------------------------------------ Show notes written here

    /**
     * Pans (and zooms when needed) to the notes written at [positionMs] of [recordingId] and
     * highlights them; when only distant notes exist, offers them instead.
     */
    fun showNotesAt(recordingId: String, positionMs: Long) {
        val canvas = c.canvas ?: return
        val content = canvas.pageContent(0) ?: c.latestContent
        c.scope.launch {
            val result = withContext(Dispatchers.Default) { BoardAnchors.find(content, recordingId, positionMs) }
            when (result) {
                is AnchorSearch.Found -> reveal(recordingId, result.hit)
                is AnchorSearch.OnlyFar -> c.messages.tryEmit(
                    UiMessage(R.string.wb_audio_no_notes_at_time, action = R.string.wb_audio_show_nearest, onAction = { reveal(recordingId, result.nearest) }),
                )
                AnchorSearch.NoNotes -> c.messages.tryEmit(UiMessage(R.string.wb_audio_no_notes_in_recording))
            }
        }
    }

    private fun reveal(recordingId: String, hit: BoardAnchorHit) {
        val canvas = c.canvas ?: return
        val b = hit.bounds
        val zoom = canvas.viewState().zoom
        val visible = canvas.visibleRect(0)?.toBox()
        val plan = if (visible != null) {
            RevealMath.plan(visible, canvas.width.toFloat(), canvas.height.toFloat(), zoom, b, panelCoverPx)
        } else {
            null
        }
        if (plan != null && plan.changesZoom(zoom)) canvas.setZoom(plan.zoom)
        canvas.revealOnPage(0, plan?.centerX ?: b.centerX, plan?.centerY ?: b.centerY, RectF(b.left, b.top, b.right, b.bottom))
        if (highlighter.onReveal(BoardHighlight(recordingId, hit.lastOffsetMs, hit.spanMs), now())) applyHighlight()
        holdJob?.cancel()
        holdJob = c.scope.launch {
            delay(highlighter.holdMs)
            if (highlighter.onTick(now())) applyHighlight()
        }
    }

    // ------------------------------------------------------------------ Play from here

    /** The anchor of the selection's first-written item, or null. */
    fun selectionAnchor(): AudioAnchor? = c.selection?.content?.let { BoardAnchors.firstAnchor(it) }

    /** Tap on an item written during a recording: offers to play the recording from that moment. */
    fun onItemTapped(ref: ItemRef) {
        // A lasso tap selects the item instead, and the selection bar offers "play from here".
        if (c.tools.tool == ToolType.LASSO || c.selection != null) return
        val anchor = anchorOf(ref) ?: return
        val now = now()
        val last = lastTapped
        if (last != null && last.first == ref.itemId && now - last.second < TAP_REPEAT_MS) return
        lastTapped = ref.itemId to now
        c.messages.tryEmit(
            UiMessage(
                R.string.wb_audio_written_at, listOf(RecordLogic.clockMs(anchor.offsetMs)),
                action = R.string.wb_audio_play_from_here, onAction = { playFrom(anchor) },
            ),
        )
    }

    private fun anchorOf(ref: ItemRef): AudioAnchor? {
        val content = c.canvas?.pageContent(0) ?: c.latestContent
        return when (ref.type) {
            ItemType.STROKE -> content.strokes.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.SHAPE -> content.shapes.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.TEXT -> content.texts.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.IMAGE -> content.images.firstOrNull { it.id == ref.itemId }?.audio
            ItemType.STICKY, ItemType.CONNECTOR -> null
        }
    }

    fun playFrom(anchor: AudioAnchor) {
        c.scope.launch {
            val rec = withContext(Dispatchers.IO) { runCatching { graph.db.recordings().get(anchor.recordingId)?.toModel() }.getOrNull() }
            if (rec == null) {
                c.messages.tryEmit(UiMessage(R.string.wb_audio_recording_missing))
                return@launch
            }
            val ok = runCatching {
                graph.playback.load(rec)
                graph.playback.seekTo(anchor.offsetMs)
                graph.playback.play()
            }.isSuccess
            if (!ok) {
                c.messages.tryEmit(UiMessage(R.string.wb_audio_play_failed))
                return@launch
            }
            c.messages.tryEmit(
                UiMessage(
                    R.string.wb_audio_playing_from, listOf(RecordLogic.clockMs(anchor.offsetMs)),
                    action = R.string.wb_audio_recordings, onAction = { panelRequests.tryEmit(Unit) },
                ),
            )
        }
    }

    // ------------------------------------------------------------------ Lifecycle

    /** The screen is leaving: drop the highlight (playback itself keeps going). */
    fun dispose() {
        holdJob?.cancel()
        if (highlighter.clear()) applyHighlight()
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    private companion object {
        const val TAG = "BoardAudio"
        const val TAP_REPEAT_MS = 5_000L
    }
}
