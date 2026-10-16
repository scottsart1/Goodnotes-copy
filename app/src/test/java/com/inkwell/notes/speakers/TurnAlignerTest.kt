package com.inkwell.notes.speakers

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.logic.TurnAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnAlignerTest {
    private fun seg(start: Long, end: Long, text: String, speaker: String? = null) = TranscriptSegment(start, end, text, speaker)
    private fun turn(label: String, start: Long, end: Long) = SpeakerTurn(label, start, end)

    private fun nonSpace(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun shortSegmentsTakeTheLabelWithMostOverlap() {
        val turns = listOf(turn("S1", 0, 3_000), turn("S2", 3_000, 9_000))
        val out = TurnAligner.align(listOf(seg(500, 2_500, "Hi."), seg(2_000, 5_500, "Hello there"), seg(6_000, 8_000, "Fine.")), turns)
        assertEquals(listOf("S1", "S2", "S2"), out.map { it.speakerLabel })
        assertEquals(3, out.size)
    }

    @Test
    fun segmentsBetweenTurnsTakeTheNearestWithinLimit() {
        val turns = listOf(turn("S1", 0, 2_000), turn("S2", 10_000, 12_000))
        val out = TurnAligner.align(listOf(seg(2_800, 3_500, "near one"), seg(5_000, 6_000, "far"), seg(8_900, 9_500, "near two")), turns)
        assertEquals("S1", out[0].speakerLabel)
        assertNull(out[1].speakerLabel)
        assertEquals("S2", out[2].speakerLabel)
    }

    @Test
    fun noTurnsLeavesSegmentsUntouched() {
        val segs = listOf(seg(0, 1_000, "x", speaker = "Old"))
        assertEquals(segs, TurnAligner.align(segs, emptyList()))
    }

    @Test
    fun longSegmentIsSplitAtSentenceBoundaries() {
        val text = "Let's start with the budget for next quarter. I think we can manage that. " +
            "Actually I disagree with that plan entirely. We need more time to review it."
        // 40 s block; the voice changes at 20 s, right where the third sentence starts by length.
        val turns = listOf(turn("S1", 0, 19_500), turn("S2", 20_500, 40_000))
        val out = TurnAligner.align(listOf(seg(0, 40_000, text, speaker = "x")), turns)
        assertEquals(2, out.size)
        assertEquals("Let's start with the budget for next quarter. I think we can manage that.", out[0].text)
        assertEquals("Actually I disagree with that plan entirely. We need more time to review it.", out[1].text)
        assertEquals(listOf("S1", "S2"), out.map { it.speakerLabel })
        assertEquals(0L, out[0].startMs)
        assertEquals(40_000L, out[1].endMs)
        assertEquals(out[0].endMs, out[1].startMs)
        assertTrue(out[1].startMs in 15_000L..25_000L)
        assertEquals(nonSpace(text), nonSpace(out.joinToString("") { it.text }))
    }

    @Test
    fun fallsBackToClauseThenWordBoundaries() {
        val clause = "we went over the numbers in detail for a long while, and then somebody else took over the discussion"
        val turns = listOf(turn("S1", 0, 10_000), turn("S2", 10_000, 20_000))
        val out = TurnAligner.align(listOf(seg(0, 20_000, clause)), turns)
        assertEquals(2, out.size)
        assertTrue(out[0].text.endsWith(","))
        assertEquals(nonSpace(clause), nonSpace(out.joinToString("") { it.text }))

        val words = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
        val out2 = TurnAligner.align(listOf(seg(0, 20_000, words)), turns)
        assertEquals(2, out2.size)
        assertTrue(out2.all { it.text.isNotBlank() && !it.text.startsWith(" ") && !it.text.endsWith(" ") })
        assertEquals(words, out2.joinToString(" ") { it.text })
    }

    @Test
    fun splitsIntoSeveralPiecesAndKeepsEveryCharacterOnce() {
        val text = "One two three. Four five six! Seven eight nine? Ten eleven twelve. 十三十四。 Fifteen sixteen."
        val turns = listOf(turn("S1", 0, 15_000), turn("S2", 15_000, 30_000), turn("S1", 30_000, 45_000), turn("S3", 45_000, 60_000))
        val out = TurnAligner.align(listOf(seg(0, 60_000, text)), turns)
        assertTrue(out.size in 3..4)
        assertEquals(nonSpace(text), nonSpace(out.joinToString("") { it.text }))
        // Starts strictly increase, pieces are contiguous and cover the block.
        for (i in 1 until out.size) {
            assertTrue(out[i].startMs > out[i - 1].startMs)
            assertEquals(out[i - 1].endMs, out[i].startMs)
        }
        assertEquals(0L, out.first().startMs)
        assertEquals(60_000L, out.last().endMs)
        // Adjacent pieces never share a label.
        for (i in 1 until out.size) assertTrue(out[i].speakerLabel != out[i - 1].speakerLabel)
    }

    @Test
    fun briefInterjectionsDoNotSplit() {
        val text = "This is a long explanation of the method. It keeps going for a while. And it ends here."
        val turns = listOf(turn("S1", 0, 14_000), turn("S2", 14_000, 14_800), turn("S1", 14_800, 30_000))
        val out = TurnAligner.align(listOf(seg(0, 30_000, text)), turns)
        assertEquals(1, out.size)
        assertEquals("S1", out[0].speakerLabel)
        assertEquals(text, out[0].text)
    }

    @Test
    fun unbrokenTextIsCutWithoutLosingCharacters() {
        val text = "あいうえおかきくけこさしすせそたちつてと😀なにぬねの"
        val turns = listOf(turn("S1", 0, 10_000), turn("S2", 10_000, 20_000))
        val out = TurnAligner.align(listOf(seg(0, 20_000, text)), turns)
        assertEquals(2, out.size)
        assertEquals(text, out.joinToString("") { it.text })
        assertTrue(out.none { it.text.isEmpty() })
    }

    @Test
    fun cutCandidatesRespectDecimalsAndQuotes() {
        val (sentence, clause, word) = TurnAligner.cutCandidates("It costs 3.5 dollars. \"Really?\" she asked, twice.")
        val text = "It costs 3.5 dollars. \"Really?\" she asked, twice."
        assertEquals(listOf(text.indexOf('"'), text.indexOf("she")), sentence)
        assertEquals(listOf(text.indexOf("twice")), clause)
        assertTrue(word.contains(text.indexOf("costs")))
    }
}
