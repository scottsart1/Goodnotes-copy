package com.inkwell.notes.whiteboard.logic

import com.inkwell.notes.audio.RecordingState
import java.util.Locale

/** What the board's Record control shows, derived from the app-wide recording state. */
sealed interface RecordControl {
    /** Nothing records: a mic; tapping starts recording this board (asking for the mic first). */
    data object Idle : RecordControl

    /** This board records: a dot and the elapsed time; tapping opens pause / resume / stop. */
    data class Here(val elapsedSeconds: Long, val paused: Boolean) : RecordControl

    /** Another document records: a mic; tapping offers to stop that and record here. */
    data class Elsewhere(val otherDocId: String) : RecordControl
}

/** Where pasting on the board takes its content from. */
enum class PasteSource { BOARD, TEXT, NONE }

object RecordLogic {

    /** The control for [docId]. Elapsed time is whole seconds, so 20 Hz level updates don't recompose. */
    fun control(state: RecordingState, docId: String): RecordControl = when (state) {
        is RecordingState.Active ->
            if (state.docId == docId) RecordControl.Here(state.elapsedMs.coerceAtLeast(0) / 1000, state.paused)
            else RecordControl.Elsewhere(state.docId)
        else -> RecordControl.Idle
    }

    /** "0:07", "18:40", "1:02:03" with locale-independent digits. */
    fun clock(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun clockMs(ms: Long): String = clock(ms.coerceAtLeast(0) / 1000)

    /**
     * Paste the newer of the board's own clipboard (copied at [boardCopiedAt], null when empty)
     * and plain text on the system clipboard (set at [systemClipAt], 0 or null when unknown),
     * e.g. an AI summary copied from the recordings panel. Ties and unknown times favour the board.
     */
    fun pasteSource(boardCopiedAt: Long?, systemHasText: Boolean, systemClipAt: Long?): PasteSource = when {
        boardCopiedAt != null && systemHasText ->
            if (systemClipAt != null && systemClipAt > boardCopiedAt) PasteSource.TEXT else PasteSource.BOARD
        boardCopiedAt != null -> PasteSource.BOARD
        systemHasText -> PasteSource.TEXT
        else -> PasteSource.NONE
    }
}
