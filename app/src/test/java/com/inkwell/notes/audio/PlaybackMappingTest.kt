package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.SegmentTimeline
import com.inkwell.notes.audio.logic.SegmentTimeline.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Global position <-> player item mapping at every segment boundary, with and without gaps. */
class PlaybackMappingTest {
    private val durations = listOf(120_000L, 119_950L, 120_010L, 37_000L)

    @Test
    fun boundariesBelongToTheFollowingSegmentAndRoundTrip() {
        val t = SegmentTimeline(durations)
        var start = 0L
        for ((i, d) in durations.withIndex()) {
            assertEquals(Position(i, 0), t.locateItem(start))
            assertEquals(Position(i, d - 1), t.locateItem(start + d - 1))
            assertEquals(start, t.globalOfItem(i, 0))
            assertEquals(start + d - 1, t.globalOfItem(i, d - 1))
            start += d
        }
        // The very end stays in the last segment at its full duration.
        assertEquals(Position(3, 37_000), t.locateItem(t.totalMs))
        assertEquals(t.totalMs, t.globalOfItem(3, 37_000))
        for (g in 0 until t.totalMs step 997) {
            val p = t.locateItem(g)!!
            assertEquals(g, t.globalOfItem(p.index, p.offsetMs))
        }
    }

    @Test
    fun seekingIntoAMissingSegmentLandsOnTheNextPlayableOneAtTheRightGlobalTime() {
        // Segment 1 is gone (deleted or unreadable); items are 0 -> seg 0, 1 -> seg 2, 2 -> seg 3.
        val t = SegmentTimeline(durations, playable = listOf(0, 2, 3))
        assertEquals(Position(1, 0), t.locateItem(120_000))
        assertEquals(Position(1, 0), t.locateItem(200_000))
        assertEquals(Position(1, 0), t.locateItem(239_950))
        assertEquals(Position(1, 1), t.locateItem(239_951))
        // Reported back as a global time, the start of item 1 is the start of segment 2.
        assertEquals(239_950L, t.globalOfItem(1, 0))
        assertEquals(t.totalMs, t.globalOfItem(2, 37_000))
        // Player positions slightly past an item's duration (ExoPlayer rounding) stay inside it.
        assertEquals(359_960L, t.globalOfItem(1, 120_050))
    }

    @Test
    fun missingFirstSegmentStartsPlaybackAtTheSecondOne() {
        val t = SegmentTimeline(durations, playable = listOf(1, 2, 3))
        assertEquals(Position(0, 0), t.locateItem(0))
        assertEquals(Position(0, 0), t.locateItem(60_000))
        assertEquals(120_000L, t.globalOfItem(0, 0))
        assertEquals(Position(0, 5_000), t.locateItem(125_000))
    }

    @Test
    fun playableListIsSanitised() {
        val t = SegmentTimeline(durations, playable = listOf(3, 3, 1, 9, -1))
        assertEquals(listOf(1, 3), t.playable)
        assertEquals(0L, t.globalOfItem(-1, 0))
        assertEquals(t.totalMs, t.globalOfItem(2, 0))
    }

    @Test
    fun rowsWithFewerDurationsThanFilesArePaddedLikeTheController() {
        // Mirrors PlaybackControllerImpl.load: durations are taken up to the file count and padded with 0.
        val files = 3
        val d = listOf(10_000L, 20_000L).take(files)
        val padded = d + List(files - d.size) { 0L }
        val t = SegmentTimeline(padded)
        assertEquals(30_000L, t.totalMs)
        assertEquals(Position(1, 20_000), t.locate(30_000))
        assertTrue(t.locateItem(30_000)!!.index <= 1)
    }
}
