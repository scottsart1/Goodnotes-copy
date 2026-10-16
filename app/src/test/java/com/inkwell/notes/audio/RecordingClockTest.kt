package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.PausableClock
import com.inkwell.notes.audio.logic.SegmentLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingClockTest {
    private var now = 1_000_000L
    private val clock = PausableClock { now }

    @Test
    fun elapsedExcludesPauses() {
        clock.resume()
        now += 10_000
        clock.pause()
        now += 60_000 // paused: not counted
        assertEquals(10_000L, clock.elapsedMs())
        clock.resume()
        now += 5_000
        assertEquals(15_000L, clock.elapsedMs())
        clock.pause()
        now += 1_000
        clock.resume()
        now += 2_500
        assertEquals(17_500L, clock.elapsedMs())
    }

    @Test
    fun repeatedPauseOrResumeIsHarmless() {
        clock.resume()
        now += 1_000
        clock.resume() // must not reset the start
        now += 1_000
        assertEquals(2_000L, clock.elapsedMs())
        clock.pause()
        clock.pause()
        now += 9_000
        assertEquals(2_000L, clock.elapsedMs())
        assertFalse(clock.running)
    }

    @Test
    fun clockGoingBackwardsNeverProducesNegativeTime() {
        clock.resume()
        now -= 500
        assertEquals(0L, clock.elapsedMs())
        assertTrue(clock.running)
    }

    @Test
    fun notStartedIsZero() {
        now += 50_000
        assertEquals(0L, clock.elapsedMs())
    }

    @Test
    fun ledgerTracksSegmentsAndTimeline() {
        val l = SegmentLedger()
        assertEquals(30_000L, l.timelineMs(30_000))
        assertEquals(0, l.roll("/a/seg_000.m4a", 120_000))
        assertEquals(120_000L, l.committedMs())
        assertEquals(5_000L, l.currentSegmentMs(125_000))
        assertEquals(125_000L, l.timelineMs(125_000))
        assertEquals(1, l.roll("/a/seg_001.m4a", 240_100))
        assertEquals(listOf(120_000L, 120_100L), l.durations())
    }

    @Test
    fun measuredDurationCorrectsTheTimeline() {
        val l = SegmentLedger()
        l.roll("/a/seg_000.m4a", 120_000)
        l.correct(0, 119_950)
        assertEquals(119_950L, l.committedMs())
        // Time in the current segment is still measured from the boundary on the clock.
        assertEquals(119_950L + 1_000L, l.timelineMs(121_000))
        assertTrue(l.finished[0].measured)
        // A failed measurement (0) keeps the estimate.
        l.roll("/a/seg_001.m4a", 240_000)
        l.correct(1, 0)
        assertEquals(120_000L, l.durations()[1])
        assertFalse(l.finished[1].measured)
    }

    @Test
    fun dropRemovesUnusableSegment() {
        val l = SegmentLedger()
        l.roll("/a/seg_000.m4a", 1_000)
        l.roll("/a/seg_001.m4a", 1_200)
        l.drop(1)
        assertEquals(listOf("/a/seg_000.m4a"), l.paths())
        l.drop(5) // out of range: ignored
        assertEquals(1, l.count)
    }

    @Test
    fun pausesInsideASegmentAreExcludedFromItsDuration() {
        val l = SegmentLedger()
        clock.resume()
        now += 30_000
        clock.pause()
        now += 600_000
        clock.resume()
        now += 90_000
        l.roll("/a/seg_000.m4a", clock.elapsedMs())
        assertEquals(listOf(120_000L), l.durations())
    }
}
