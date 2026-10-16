package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.RecoveryPlanner
import com.inkwell.notes.audio.logic.RecoveryPlanner.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPlannerTest {
    private fun probe(i: Int, size: Long = 1_000_000, measured: Long? = 120_000, persisted: Long? = 120_000) =
        Probe("/docs/d/audio/r/seg_%03d.m4a".format(i), i, size, measured, persisted)

    @Test
    fun keepsFinishedSegmentsAndDropsCorruptLastOne() {
        val plan = RecoveryPlanner.plan(
            listOf(
                probe(0),
                probe(1, measured = 119_980),
                // Being written when the app died: bytes on disk but no readable duration.
                probe(2, size = 400_000, measured = null, persisted = null),
            ),
        )
        assertEquals(2, plan.kept.size)
        assertEquals(listOf(120_000L, 119_980L), plan.durations)
        assertEquals(239_980L, plan.totalMs)
        assertEquals(listOf(probe(2).path), plan.dropped)
    }

    @Test
    fun keepsReadableUnpersistedLastSegment() {
        // Finished on disk but the process died before the row was updated.
        val plan = RecoveryPlanner.plan(listOf(probe(0), probe(1, measured = 30_500, persisted = null)))
        assertEquals(listOf(120_000L, 30_500L), plan.durations)
        assertTrue(plan.dropped.isEmpty())
    }

    @Test
    fun dropsEmptyFiles() {
        val plan = RecoveryPlanner.plan(listOf(probe(0), probe(1, size = 0, measured = null, persisted = null)))
        assertEquals(1, plan.kept.size)
        assertEquals(1, plan.dropped.size)
    }

    @Test
    fun trustsPersistedDurationWhenProbeFailsForAFinalisedSegment() {
        val plan = RecoveryPlanner.plan(listOf(probe(0, measured = null, persisted = 120_000), probe(1)))
        assertEquals(listOf(120_000L, 120_000L), plan.durations)
    }

    @Test
    fun ordersByIndexAndDeduplicates() {
        val plan = RecoveryPlanner.plan(listOf(probe(2), probe(0), probe(1), probe(0)))
        assertEquals(listOf(probe(0).path, probe(1).path, probe(2).path), plan.paths)
    }

    @Test
    fun nothingRecoverable() {
        val plan = RecoveryPlanner.plan(listOf(probe(0, size = 10, measured = null, persisted = null)))
        assertTrue(plan.kept.isEmpty())
        assertEquals(0L, plan.totalMs)
    }

    @Test
    fun segmentFileNames() {
        assertEquals("seg_000.m4a", RecoveryPlanner.nameOf(0))
        assertEquals("seg_012.m4a", RecoveryPlanner.nameOf(12))
        assertEquals("seg_1234.m4a", RecoveryPlanner.nameOf(1234))
        assertEquals(7, RecoveryPlanner.indexOf("seg_007.m4a"))
        assertEquals(1234, RecoveryPlanner.indexOf("seg_1234.m4a"))
        assertNull(RecoveryPlanner.indexOf("waveform.bin"))
        assertNull(RecoveryPlanner.indexOf("seg_7.m4a"))
        assertNull(RecoveryPlanner.indexOf("seg_001.m4a.tmp"))
    }
}
