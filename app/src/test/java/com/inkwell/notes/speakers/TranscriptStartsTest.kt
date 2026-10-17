package com.inkwell.notes.speakers

import com.inkwell.notes.audio.logic.TranscriptAssembly
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import com.inkwell.notes.speakers.logic.SpeakerRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transcript blocks are addressed by their start time, so no two may share one. */
class TranscriptStartsTest {
    @Test
    fun duplicateStartsMoveOneMillisecondApartKeepingOrderAndText() {
        val out = TranscriptAssembly.distinctStarts(
            listOf(
                TranscriptSegment(1_000, 2_000, "a"),
                TranscriptSegment(1_000, 1_000, "b"),
                TranscriptSegment(999, 3_000, "c"),
                TranscriptSegment(5_000, 6_000, "d"),
            ),
        )
        assertEquals(listOf("c", "a", "b", "d"), out.map { it.text })
        assertEquals(listOf(999L, 1_000L, 1_001L, 5_000L), out.map { it.startMs })
        assertTrue(out.all { it.endMs >= it.startMs })
    }

    @Test
    fun alreadyDistinctStartsAreUntouched() {
        val input = listOf(TranscriptSegment(0, 10, "x"), TranscriptSegment(10, 20, "y"))
        assertEquals(input, TranscriptAssembly.distinctStarts(input))
    }

    @Test
    fun relabelledBlocksNeverShareAStart() {
        // A one-word block across a change of voice splits into pieces that can round together.
        val seg = TranscriptSegmentEntity(recordingId = "r", startMs = 0, endMs = 2, text = "Yes. No.", speaker = null, source = "openai")
        val turns = listOf(SpeakerTurn("S1", 0, 1), SpeakerTurn("S2", 1, 2))
        val out = SpeakerRecords.relabel(listOf(seg), turns, emptyMap(), keepExistingLabels = false) ?: listOf(seg)
        assertEquals(out.size, out.map { it.startMs }.toSet().size)
        assertEquals("Yes. No.", out.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim())
    }
}
