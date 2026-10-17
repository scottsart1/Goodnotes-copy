package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.logic.BoardHighlight
import com.inkwell.notes.whiteboard.logic.PlaybackHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHighlighterTest {
    private val rec = "rec"

    @Test fun followsPlaybackThrottled() {
        val h = PlaybackHighlighter(throttleMs = 150, holdMs = 6_000)
        assertTrue(h.onPlayback(rec, playing = true, positionMs = 1_000, enabled = true, nowMs = 0))
        assertEquals(BoardHighlight(rec, 1_000), h.current)
        // Within the throttle window: ignored.
        assertFalse(h.onPlayback(rec, true, 1_100, true, nowMs = 100))
        assertEquals(1_000L, h.current!!.positionMs)
        assertTrue(h.onPlayback(rec, true, 1_200, true, nowMs = 160))
        assertEquals(1_200L, h.current!!.positionMs)
        // Same position later: nothing to push.
        assertFalse(h.onPlayback(rec, true, 1_200, true, nowMs = 400))
    }

    @Test fun anotherRecordingIsNotThrottled() {
        val h = PlaybackHighlighter(throttleMs = 150)
        h.onPlayback(rec, true, 1_000, true, 0)
        assertTrue(h.onPlayback("other", true, 5_000, true, 10))
        assertEquals("other", h.current!!.recordingId)
    }

    @Test fun clearsWhenPlaybackStopsLeavesOrIsDisabled() {
        val h = PlaybackHighlighter()
        h.onPlayback(rec, true, 1_000, true, 0)
        assertTrue(h.onPlayback(rec, playing = false, positionMs = 1_000, enabled = true, nowMs = 10))
        assertNull(h.current)
        assertFalse(h.onPlayback(rec, false, 1_000, true, 20))

        h.onPlayback(rec, true, 1_000, true, 1_000)
        assertTrue(h.onPlayback(null, true, 1_000, true, 1_010)) // a recording of another document
        assertNull(h.current)

        h.onPlayback(rec, true, 1_000, true, 2_000)
        assertTrue(h.onPlayback(rec, true, 1_000, enabled = false, nowMs = 2_010))
        assertNull(h.current)
    }

    @Test fun restartAfterStopIsImmediate() {
        val h = PlaybackHighlighter(throttleMs = 150)
        h.onPlayback(rec, true, 1_000, true, 0)
        h.onPlayback(rec, false, 1_000, true, 10)
        assertTrue(h.onPlayback(rec, true, 1_050, true, 20))
    }

    @Test fun revealIsHeldWhilePausedThenClears() {
        val h = PlaybackHighlighter(holdMs = 6_000)
        val shown = BoardHighlight(rec, 42_000, 1_500)
        assertTrue(h.onReveal(shown, nowMs = 0))
        assertTrue(h.isHeld)
        // A paused player does not clear a revealed moment.
        assertFalse(h.onPlayback(rec, playing = false, positionMs = 10_000, enabled = true, nowMs = 100))
        assertEquals(shown, h.current)
        assertFalse(h.onTick(5_999))
        assertTrue(h.onTick(6_000))
        assertNull(h.current)
        assertFalse(h.isHeld)
        assertFalse(h.onTick(7_000))
    }

    @Test fun revealWinsOverPlaybackUntilTheHoldEnds() {
        val h = PlaybackHighlighter(throttleMs = 150, holdMs = 6_000)
        h.onPlayback(rec, true, 1_000, true, 0)
        val shown = BoardHighlight(rec, 90_000, 0)
        assertTrue(h.onReveal(shown, nowMs = 500))
        assertFalse(h.onPlayback(rec, true, 1_500, true, 1_000))
        assertEquals(shown, h.current)
        // When the hold ends, the latest playback position takes over.
        assertFalse(h.onPlayback(rec, true, 6_400, true, 6_400))
        assertTrue(h.onTick(6_500))
        assertEquals(BoardHighlight(rec, 6_400), h.current)
        // ...and playback updates flow again (throttled from the takeover).
        assertFalse(h.onPlayback(rec, true, 6_500, true, 6_550))
        assertTrue(h.onPlayback(rec, true, 6_700, true, 6_700))
    }

    @Test fun playbackAfterAnExpiredHoldTakesOverWithoutATick() {
        val h = PlaybackHighlighter(holdMs = 1_000)
        h.onReveal(BoardHighlight(rec, 5_000, 0), nowMs = 0)
        assertTrue(h.onPlayback(rec, true, 2_000, true, 1_500))
        assertEquals(BoardHighlight(rec, 2_000), h.current)
        assertFalse(h.isHeld)
    }

    @Test fun repeatedRevealOfTheSameMomentOnlyExtendsTheHold() {
        val h = PlaybackHighlighter(holdMs = 1_000)
        val shown = BoardHighlight(rec, 5_000, 0)
        assertTrue(h.onReveal(shown, 0))
        assertFalse(h.onReveal(shown, 900))
        assertFalse(h.onTick(1_000))
        assertTrue(h.onTick(1_900))
    }

    @Test fun clearForgetsEverything() {
        val h = PlaybackHighlighter()
        assertFalse(h.clear())
        h.onReveal(BoardHighlight(rec, 1, 0), 0)
        assertTrue(h.clear())
        assertNull(h.current)
        assertFalse(h.isHeld)
        assertFalse(h.onTick(Long.MAX_VALUE))
    }
}
