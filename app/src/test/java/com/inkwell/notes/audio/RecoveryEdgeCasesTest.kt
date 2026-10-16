package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.RecoveryPlanner
import com.inkwell.notes.audio.logic.RecoveryPlanner.Probe
import com.inkwell.notes.audio.logic.Waveform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crash recovery picks the right files in the awkward cases the engine can meet. */
class RecoveryEdgeCasesTest {
    private val dir = "/data/docs/d/audio/r"
    private fun p(i: Int, size: Long = 900_000, measured: Long? = 120_000, persisted: Long? = 120_000) =
        Probe("$dir/seg_%03d.m4a".format(i), i, size, measured, persisted)

    @Test
    fun measuredDurationBeatsAStaleClockEstimate() {
        // Rolled over on the clock (estimate persisted) but the process died before it was measured.
        val plan = RecoveryPlanner.plan(listOf(p(0, measured = 119_940, persisted = 120_000), p(1, measured = 30_000, persisted = null)))
        assertEquals(listOf(119_940L, 30_000L), plan.durations)
        assertEquals(149_940L, plan.totalMs)
    }

    @Test
    fun segmentsPersistedOutOfOrderAreSortedByTheirIndex() {
        val plan = RecoveryPlanner.plan(listOf(p(2), p(0), p(1)))
        assertEquals(listOf(0, 1, 2).map { "$dir/seg_%03d.m4a".format(it) }, plan.paths)
    }

    @Test
    fun aMovedLibraryKeepsTheLocalCopyAndDropsTheDeadPath() {
        // The row still points at the old root; the same file exists in the local folder.
        val dead = Probe("/old/root/docs/d/audio/r/seg_000.m4a", 0, 0, null, 120_000)
        val local = p(0)
        val plan = RecoveryPlanner.plan(listOf(dead, local, p(1)))
        assertEquals(listOf(local.path, p(1).path), plan.paths)
        assertEquals(listOf(dead.path), plan.dropped)
    }

    @Test
    fun onlyTheInProgressSegmentIsLost() {
        val probes = (0 until 5).map { p(it) } + p(5, size = 250_000, measured = null, persisted = null)
        val plan = RecoveryPlanner.plan(probes)
        assertEquals(5, plan.kept.size)
        assertEquals(600_000L, plan.totalMs)
        assertEquals(listOf(p(5).path), plan.dropped)
        // The envelope is cut to the audio that survives.
        val envelope = ByteArray(Waveform.bucketsFor(640_000)) { 7 }
        assertEquals(Waveform.bucketsFor(600_000), Waveform.trim(envelope, plan.totalMs).size)
    }

    @Test
    fun zeroOrNegativeDurationsNeverCount() {
        val plan = RecoveryPlanner.plan(listOf(p(0, measured = 0, persisted = 0), p(1, measured = -5, persisted = null), p(2)))
        assertEquals(listOf(p(2).path), plan.paths)
        assertTrue(plan.dropped.containsAll(listOf(p(0).path, p(1).path)))
    }

    @Test
    fun largeIndicesStayOrderedNumerically() {
        val plan = RecoveryPlanner.plan(listOf(p(1000), p(999), p(10)))
        assertEquals(listOf(10, 999, 1000), plan.paths.map { RecoveryPlanner.indexOf(it.substringAfterLast('/')) })
    }
}
