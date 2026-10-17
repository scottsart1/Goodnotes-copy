package com.inkwell.notes.editor

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.editor.logic.AnchorHit
import com.inkwell.notes.editor.logic.AnchorLocator
import com.inkwell.notes.editor.logic.Bounds
import com.inkwell.notes.ink.TestInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Show notes written here" in a notebook: the revealed notes must also be the highlighted ones. */
class AnchorLocatorHighlightTest {
    private val rec = "rec"
    private fun hit(offsetMs: Long) = AnchorHit(0, Bounds(0f, 0f, 1f, 1f), offsetMs)

    /** The canvas emphasises anchors in [positionMs - windowMs, positionMs]. */
    private fun covers(positionMs: Long, offsetMs: Long, windowMs: Long = 4_000) = offsetMs <= positionMs && offsetMs >= positionMs - windowMs

    @Test fun `notes inside the window keep the playback position`() {
        assertEquals(10_000L, AnchorLocator.highlightPosition(hit(7_000), 10_000))
        assertEquals(10_000L, AnchorLocator.highlightPosition(hit(6_000), 10_000)) // window edge
        assertEquals(10_000L, AnchorLocator.highlightPosition(hit(10_000), 10_000))
    }

    @Test fun `the closest notes outside the window are highlighted at their own moment`() {
        val past = AnchorLocator.highlightPosition(hit(2_000), 60_000)
        assertEquals(2_000L, past)
        assertEquals(true, covers(past, 2_000))
        val future = AnchorLocator.highlightPosition(hit(75_000), 60_000)
        assertEquals(75_000L, future)
        assertEquals(true, covers(future, 75_000))
        // The old behaviour (highlighting at the playback position) would miss both.
        assertEquals(false, covers(60_000, 2_000))
        assertEquals(false, covers(60_000, 75_000))
    }

    @Test fun `find walks every anchored item type including stickies and prefers the window hit`() {
        val p0 = PageContent(stickies = listOf(StickyItem("n", 10f, 10f, 20f, 20f, audio = AudioAnchor(rec, 100_000))))
        val p1 = PageContent(strokes = listOf(TestInk.stroke("s", 0f, 0f, 5f, 5f, audio = AudioAnchor(rec, 58_000))))
        // The sticky on page 0 is 40 s away; the stroke on page 1 was being written at 60 s.
        val h = AnchorLocator.find(listOf(0 to p0, 1 to p1), rec, 60_000)!!
        assertEquals(1, h.pageIndex)
        assertEquals(58_000L, h.offsetMs)
        assertEquals(60_000L, AnchorLocator.highlightPosition(h, 60_000))
        // Nothing in the window: the sticky is the closest, so it is what gets highlighted.
        val far = AnchorLocator.find(listOf(0 to p0, 1 to p1), rec, 90_000)!!
        assertEquals(0, far.pageIndex)
        assertEquals(Bounds(10f, 10f, 30f, 30f), far.bounds)
        assertEquals(100_000L, AnchorLocator.highlightPosition(far, 90_000))
        assertNull(AnchorLocator.find(listOf(0 to p0), "other", 100_000))
    }
}
