package com.inkwell.notes.whiteboard

import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.whiteboard.logic.PasteSource
import com.inkwell.notes.whiteboard.logic.RecordControl
import com.inkwell.notes.whiteboard.logic.RecordLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordLogicTest {

    private fun active(docId: String, elapsedMs: Long, paused: Boolean = false) =
        RecordingState.Active(docId, "rec", startedAt = 0L, elapsedMs = elapsedMs, paused = paused, level = 0.4f)

    @Test fun controlFollowsTheRecordingState() {
        assertEquals(RecordControl.Idle, RecordLogic.control(RecordingState.Idle, "board"))
        assertEquals(RecordControl.Idle, RecordLogic.control(RecordingState.Error("mic busy"), "board"))
        assertEquals(RecordControl.Here(62, paused = false), RecordLogic.control(active("board", 62_999), "board"))
        assertEquals(RecordControl.Here(5, paused = true), RecordLogic.control(active("board", 5_000, paused = true), "board"))
        assertEquals(RecordControl.Elsewhere("notebook"), RecordLogic.control(active("notebook", 1_000), "board"))
        assertEquals(RecordControl.Here(0, false), RecordLogic.control(active("board", -40), "board"))
    }

    @Test fun levelUpdatesWithinASecondGiveAnEqualControl() {
        val a = RecordLogic.control(active("board", 12_100).copy(level = 0.1f), "board")
        val b = RecordLogic.control(active("board", 12_900).copy(level = 0.9f), "board")
        assertEquals(a, b)
    }

    @Test fun clockFormats() {
        assertEquals("0:00", RecordLogic.clock(0))
        assertEquals("0:07", RecordLogic.clock(7))
        assertEquals("18:40", RecordLogic.clock(18 * 60 + 40))
        assertEquals("1:02:03", RecordLogic.clock(3_723))
        assertEquals("0:00", RecordLogic.clock(-5))
        assertEquals("1:01", RecordLogic.clockMs(61_999))
    }

    @Test fun pastePrefersTheNewerClipboard() {
        assertEquals(PasteSource.NONE, RecordLogic.pasteSource(null, systemHasText = false, systemClipAt = null))
        assertEquals(PasteSource.BOARD, RecordLogic.pasteSource(100L, false, null))
        assertEquals(PasteSource.TEXT, RecordLogic.pasteSource(null, true, null))
        // An AI summary copied after the board items wins.
        assertEquals(PasteSource.TEXT, RecordLogic.pasteSource(100L, true, 200L))
        assertEquals(PasteSource.BOARD, RecordLogic.pasteSource(300L, true, 200L))
        // Unknown or equal times favour the board's own clipboard.
        assertEquals(PasteSource.BOARD, RecordLogic.pasteSource(300L, true, null))
        assertEquals(PasteSource.BOARD, RecordLogic.pasteSource(300L, true, 300L))
    }
}
