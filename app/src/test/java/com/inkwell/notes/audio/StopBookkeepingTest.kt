package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.PausableClock
import com.inkwell.notes.audio.logic.SegmentLedger
import com.inkwell.notes.audio.ui.dock.DockMemory
import com.inkwell.notes.audio.logic.DockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What RecordingEngine.finish does with the ledger, replayed on the pure ledger: the file
 * being written at stop is closed on the stopped clock, then every segment takes its
 * measured length.
 */
class StopBookkeepingTest {
    private var now = 0L
    private val clock = PausableClock { now }

    @Test
    fun stopBeforeTheFirstRolloverKeepsOneMeasuredSegment() {
        val l = SegmentLedger()
        clock.resume()
        now += 42_000
        clock.pause() // stopClock()
        now += 5_000 // time spent releasing the recorder is not audio
        val index = l.roll("/a/seg_000.m4a", clock.elapsedMs())
        assertEquals(0, index)
        assertEquals(42_000L, l.durations()[0])
        l.correct(index, 41_870)
        assertEquals(listOf(41_870L), l.durations())
        assertEquals(41_870L, l.committedMs())
    }

    @Test
    fun stopWhilePausedClosesTheSegmentAtThePausePoint() {
        val l = SegmentLedger()
        clock.resume()
        now += 120_000
        l.roll("/a/seg_000.m4a", clock.elapsedMs())
        now += 30_000
        clock.pause()
        now += 600_000 // paused for ten minutes, then stop
        clock.pause() // stopClock() while already paused is harmless
        l.roll("/a/seg_001.m4a", clock.elapsedMs())
        assertEquals(listOf(120_000L, 30_000L), l.durations())
        assertEquals(150_000L, l.timelineMs(clock.elapsedMs()))
    }

    @Test
    fun segmentsLeftUnmeasuredAtRolloverAreCorrectedAtStop() {
        val l = SegmentLedger()
        clock.resume()
        now += 120_000
        l.roll("/a/seg_000.m4a", clock.elapsedMs())
        l.correct(0, 0) // measurement failed at rollover
        now += 120_000
        l.roll("/a/seg_001.m4a", clock.elapsedMs())
        l.correct(1, 119_990)
        now += 10_000
        clock.pause()
        l.correct(l.roll("/a/seg_002.m4a", clock.elapsedMs()), 9_980)
        // The retry at stop measures the first one.
        for ((i, seg) in l.finished.withIndex()) if (!seg.measured) l.correct(i, 119_900)
        assertTrue(l.finished.all { it.measured })
        assertEquals(listOf(119_900L, 119_990L, 9_980L), l.durations())
        assertEquals(249_870L, l.committedMs())
    }

    @Test
    fun dockMemoryIsPerRecording() {
        DockMemory.remember("r1", DockMode.COLLAPSED, 0.25f)
        assertEquals(DockMode.COLLAPSED, DockMemory.modeFor("r1"))
        assertNull(DockMemory.modeFor("r2"))
        assertEquals(0.25f, DockMemory.centerFraction, 0f)
        // A new recording starts from the default centre and its own mode.
        DockMemory.remember("r2", DockMode.EXPANDED, Float.NaN)
        assertEquals(DockMode.EXPANDED, DockMemory.modeFor("r2"))
        assertNull(DockMemory.modeFor("r1"))
        assertEquals(0.5f, DockMemory.centerFraction, 0f)
        DockMemory.remember("r2", DockMode.EXPANDED, 7f)
        assertEquals(1f, DockMemory.centerFraction, 0f)
        assertFalse(DockMemory.centerFraction.isNaN())
    }
}
