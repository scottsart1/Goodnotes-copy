package com.inkwell.notes.speakers.ui

import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun speaker(
    label: String,
    name: String = "Speaker ${label.removePrefix("S")}",
    personId: String? = null,
    confirmed: Boolean = personId != null,
    confidence: Float? = null,
    speechMs: Long = 0,
    color: Int = label.removePrefix("S").toIntOrNull()?.minus(1) ?: 0,
) = RecordingSpeaker("r1", label, personId, name, confidence, confirmed, speechMs, 0, 5_000, color)

class SpeakerTranscriptTest {
    private val priya = speaker("S1", "Priya", personId = "p1", speechMs = 60_000)
    private val unnamed = speaker("S2", speechMs = 30_000)
    private val guess = speaker("S3", "Tom", personId = "p2", confirmed = false, confidence = 0.82f, speechMs = 10_000)
    private val byLabel = listOf(priya, unnamed, guess).associateBy { it.label }

    private val segs = listOf(
        TranscriptSegment(0, 4_000, "Welcome everyone.", "Priya", "S1"),
        TranscriptSegment(4_000, 8_000, "Let's look at the budget.", "Priya", "S1"),
        TranscriptSegment(8_000, 12_000, "The budget is tight.", "Speaker 2", "S2"),
        TranscriptSegment(12_000, 16_000, "Agreed.", "Priya", "S1"),
        TranscriptSegment(16_000, 20_000, "I can take that.", "Tom", "S3"),
        TranscriptSegment(20_000, 24_000, "Old transcript line.", "Ana", null),
        TranscriptSegment(24_000, 28_000, "No speaker known."),
    )

    @Test
    fun voicesComeFromTheRecordingSpeakersThenTheStoredName() {
        val v = SpeakerTranscript.voiceOf(segs[0], byLabel)!!
        assertEquals("S1", v.key)
        assertEquals("Priya", v.name)
        assertEquals(0, v.colorIndex)
        assertFalse(v.tentative)

        val tentative = SpeakerTranscript.voiceOf(segs[4], byLabel)!!
        assertTrue(tentative.tentative)
        assertEquals(0.82f, tentative.confidence!!, 1e-6f)

        val nameOnly = SpeakerTranscript.voiceOf(segs[5], byLabel)!!
        assertEquals("Ana", nameOnly.name)
        assertNull(nameOnly.colorIndex)
        assertEquals("name:ana", nameOnly.key)

        assertNull(SpeakerTranscript.voiceOf(segs[6], byLabel))
    }

    @Test
    fun aLabelWithoutASpeakerRowFallsBackToTheStoredName() {
        val seg = TranscriptSegment(0, 1, "x", "Lee", "S9")
        val v = SpeakerTranscript.voiceOf(seg, byLabel)!!
        assertEquals("S9", v.key)
        assertEquals("Lee", v.name)
        assertNull(v.colorIndex)
    }

    @Test
    fun consecutiveBlocksOfOneVoiceShowTheNameOnce() {
        val rows = SpeakerTranscript.rows(segs, "", byLabel)
        assertEquals(segs.size, rows.size)
        assertEquals(listOf(true, false, true, true, true, true, false), rows.map { it.startsGroup })
    }

    @Test
    fun searchMatchesNamesAsWellAsText() {
        val byName = SpeakerTranscript.rows(segs, "priya", byLabel)
        assertEquals(listOf(0, 1, 3), byName.map { it.match.index })
        assertTrue("a name match highlights nothing in the text", byName.all { it.match.ranges.isEmpty() })

        val mixed = SpeakerTranscript.rows(segs, "priya budget", byLabel)
        assertEquals(listOf(1), mixed.map { it.match.index })
        val r = mixed.single().match.ranges.single()
        assertEquals("budget", segs[1].text.substring(r.first, r.last + 1))

        assertEquals(listOf(4), SpeakerTranscript.rows(segs, "tom", byLabel).map { it.match.index })
        assertEquals(listOf(5), SpeakerTranscript.rows(segs, "ana", byLabel).map { it.match.index })
        assertTrue(SpeakerTranscript.rows(segs, "nobody", byLabel).isEmpty())
    }

    @Test
    fun filteredRowsRegroupAcrossHiddenBlocks() {
        // Blocks 1 and 3 are both Priya but block 2 (Speaker 2) is between them in the transcript.
        val rows = SpeakerTranscript.rows(segs, "", byLabel, onlyKey = "S1")
        assertEquals(listOf(0, 1, 3), rows.map { it.match.index })
        assertEquals(listOf(true, false, true), rows.map { it.startsGroup })
    }

    @Test
    fun talkSharesAddUpToAHundred() {
        val shares = SpeakerTranscript.talkShares(listOf(priya, unnamed, guess), emptyList())
        assertEquals(mapOf("S1" to 60, "S2" to 30, "S3" to 10), shares)

        val thirds = SpeakerTranscript.talkShares(
            listOf(speaker("S1", speechMs = 1_000), speaker("S2", speechMs = 1_000), speaker("S3", speechMs = 1_000)),
            emptyList(),
        )
        assertEquals(100, thirds.values.sum())
        assertEquals(setOf(33, 34), thirds.values.toSet())
    }

    @Test
    fun talkSharesFallBackToTurnsAndHandleSilence() {
        val shares = SpeakerTranscript.talkShares(
            listOf(speaker("S1"), speaker("S2")),
            listOf(SpeakerTurn("S1", 0, 3_000), SpeakerTurn("S2", 3_000, 4_000)),
        )
        assertEquals(mapOf("S1" to 75, "S2" to 25), shares)
        assertTrue(SpeakerTranscript.talkShares(listOf(speaker("S1")), emptyList()).isEmpty())
        assertTrue(SpeakerTranscript.talkShares(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun blockStartsUseTheTranscriptThenTheTurns() {
        assertArrayEquals(longArrayOf(0, 4_000, 12_000), SpeakerTranscript.blockStarts("S1", segs, byLabel, emptyList()))
        val turns = listOf(SpeakerTurn("S1", 5_000, 6_000), SpeakerTurn("S2", 1_000, 2_000), SpeakerTurn("S1", 0, 1_000))
        assertArrayEquals(longArrayOf(0, 5_000), SpeakerTranscript.blockStarts("S1", emptyList(), byLabel, turns))
        assertEquals(0, SpeakerTranscript.blockStarts("S7", segs, byLabel, turns).size)
    }

    @Test
    fun nextAndPreviousBehaveLikeMediaButtons() {
        val starts = longArrayOf(0, 4_000, 12_000)
        assertEquals(4_000L, SpeakerTranscript.next(starts, 0))
        assertEquals(4_000L, SpeakerTranscript.next(starts, 3_900))
        // Sitting on a block start (or a tick after it) moves on rather than replaying it.
        assertEquals(12_000L, SpeakerTranscript.next(starts, 4_000))
        assertEquals(12_000L, SpeakerTranscript.next(starts, 3_990))
        assertNull(SpeakerTranscript.next(starts, 12_000))

        // Far into a block: back to its start; just after the start: the block before.
        assertEquals(4_000L, SpeakerTranscript.previous(starts, 9_000))
        assertEquals(0L, SpeakerTranscript.previous(starts, 4_500))
        assertNull(SpeakerTranscript.previous(starts, 1_000))
        assertNull(SpeakerTranscript.previous(LongArray(0), 50_000))
    }

    @Test
    fun freeLabelsFollowTheHighestNumber() {
        assertEquals("S1", SpeakerTranscript.nextFreeLabel(emptyList()))
        assertEquals("S4", SpeakerTranscript.nextFreeLabel(listOf("S1", "S3", "A")))
        assertEquals("S11", SpeakerTranscript.nextFreeLabel(listOf("S10", "S2")))
    }

    @Test
    fun tentativeMeansMatchedButNotConfirmed() {
        assertTrue(SpeakerTranscript.isTentative(guess))
        assertFalse(SpeakerTranscript.isTentative(priya))
        assertFalse(SpeakerTranscript.isTentative(unnamed))
        assertEquals(1, SpeakerTranscript.unnamedCount(listOf(priya, unnamed, guess)))
    }
}
