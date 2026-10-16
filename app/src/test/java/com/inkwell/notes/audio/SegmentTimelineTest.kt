package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.SegmentTimeline
import com.inkwell.notes.audio.logic.SegmentTimeline.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentTimelineTest {
    private val t = SegmentTimeline(listOf(120_000L, 120_000L, 45_500L))

    @Test
    fun totalIsSumOfSegments() {
        assertEquals(285_500L, t.totalMs)
        assertEquals(0L, t.startOf(0))
        assertEquals(120_000L, t.startOf(1))
        assertEquals(240_000L, t.startOf(2))
    }

    @Test
    fun locateMapsGlobalToSegmentAndOffset() {
        assertEquals(Position(0, 0), t.locate(0))
        assertEquals(Position(0, 119_999), t.locate(119_999))
        assertEquals(Position(1, 0), t.locate(120_000))
        assertEquals(Position(1, 5_000), t.locate(125_000))
        assertEquals(Position(2, 45_000), t.locate(285_000))
    }

    @Test
    fun locateClampsOutOfRange() {
        assertEquals(Position(0, 0), t.locate(-5_000))
        assertEquals(Position(2, 45_500), t.locate(285_500))
        assertEquals(Position(2, 45_500), t.locate(10_000_000))
    }

    @Test
    fun globalOfIsInverseOfLocate() {
        for (g in listOf(0L, 1L, 60_000L, 119_999L, 120_000L, 200_000L, 240_000L, 285_499L)) {
            val p = t.locate(g)
            assertEquals(g, t.globalOf(p.index, p.offsetMs))
        }
    }

    @Test
    fun globalOfClampsOffsetInsideSegment() {
        assertEquals(120_000L, t.globalOf(0, 500_000))
        assertEquals(120_000L, t.globalOf(1, -10))
        assertEquals(t.totalMs, t.globalOf(7, 0))
    }

    @Test
    fun zeroLengthSegmentsAreSkipped() {
        val z = SegmentTimeline(listOf(1_000L, 0L, 2_000L))
        assertEquals(Position(2, 0), z.locate(1_000))
        assertEquals(Position(2, 2_000), z.locate(3_000))
    }

    @Test
    fun emptyTimeline() {
        val e = SegmentTimeline(emptyList())
        assertEquals(0L, e.totalMs)
        assertEquals(Position(0, 0), e.locate(1_000))
        assertNull(e.locateItem(0))
    }

    @Test
    fun missingSegmentKeepsItsPlaceOnTheTimeline() {
        // Segment 1 cannot be played: positions after it must not shift.
        val m = SegmentTimeline(listOf(10_000L, 10_000L, 10_000L), playable = listOf(0, 2))
        assertEquals(30_000L, m.totalMs)
        assertEquals(Position(0, 5_000), m.locateItem(5_000))
        // Inside the missing segment: start of the next playable item.
        assertEquals(Position(1, 0), m.locateItem(15_000))
        assertEquals(Position(1, 2_000), m.locateItem(22_000))
        // Item 1 is segment 2, which starts at 20s.
        assertEquals(22_000L, m.globalOfItem(1, 2_000))
        assertEquals(5_000L, m.globalOfItem(0, 5_000))
    }

    @Test
    fun missingLastSegmentMapsToEndOfLastPlayable() {
        val m = SegmentTimeline(listOf(10_000L, 10_000L), playable = listOf(0))
        assertEquals(Position(0, 10_000), m.locateItem(15_000))
    }

    @Test
    fun globalOfItemOutsideRange() {
        assertEquals(0L, t.globalOfItem(-1, 0))
        assertEquals(t.totalMs, t.globalOfItem(9, 0))
    }
}
