package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.TranscriptAction
import com.inkwell.notes.audio.logic.TranscriptAssembly
import com.inkwell.notes.audio.logic.TranscriptCheckpoint
import com.inkwell.notes.audio.logic.TranscriptFilter
import com.inkwell.notes.audio.logic.TranscriptPrompts
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptLogicTest {
    private val segs = listOf(
        TranscriptSegment(0, 4_000, "Welcome to financial markets."),
        TranscriptSegment(4_000, 9_000, "Today: bond prices and interest rates."),
        TranscriptSegment(9_000, 15_000, "When interest rates rise, bond prices fall."),
        TranscriptSegment(15_000, 20_000, "Homework is due Friday."),
    )

    @Test
    fun blankQueryShowsEverything() {
        val r = TranscriptFilter.filter(segs, "   ")
        assertEquals(4, r.size)
        assertTrue(r.all { it.ranges.isEmpty() })
    }

    @Test
    fun matchesAreCaseInsensitiveWithRanges() {
        val r = TranscriptFilter.filter(segs, "BOND")
        assertEquals(listOf(1, 2), r.map { it.index })
        val text = r[0].segment.text
        val range = r[0].ranges.single()
        assertEquals("bond", text.substring(range.first, range.last + 1))
    }

    @Test
    fun allTermsMustMatch() {
        val r = TranscriptFilter.filter(segs, "rates fall")
        assertEquals(listOf(2), r.map { it.index })
        assertEquals(2, r[0].ranges.size)
    }

    @Test
    fun repeatedOccurrencesAndOverlapsMerge() {
        val s = listOf(TranscriptSegment(0, 1, "rate rates rate"))
        val r = TranscriptFilter.filter(s, "rate rates")
        // "rate" at 0, 5, 11 and "rates" at 5 merge into three ranges.
        assertEquals(listOf(0..3, 5..9, 11..14), r[0].ranges)
    }

    @Test
    fun noMatch() {
        assertTrue(TranscriptFilter.filter(segs, "equity").isEmpty())
    }

    @Test
    fun activeSegmentAtPosition() {
        assertEquals(0, TranscriptFilter.activeIndex(segs, 0))
        assertEquals(1, TranscriptFilter.activeIndex(segs, 4_000))
        assertEquals(2, TranscriptFilter.activeIndex(segs, 14_999))
        assertEquals(-1, TranscriptFilter.activeIndex(segs, 25_000))
        assertEquals(-1, TranscriptFilter.activeIndex(emptyList(), 1_000))
        // Gap between segments.
        val gap = listOf(TranscriptSegment(0, 1_000, "a"), TranscriptSegment(5_000, 6_000, "b"))
        assertEquals(-1, TranscriptFilter.activeIndex(gap, 3_000))
    }

    @Test
    fun shiftMovesPiecesOntoTheTimeline() {
        val pieces = listOf(
            TranscriptSegment(0, 2_500, "  Hello "),
            TranscriptSegment(2_500, 2_500, " "),
            TranscriptSegment(2_500, 130_000, "past the end"),
        )
        val out = TranscriptAssembly.shift(pieces, offsetMs = 240_000, durationMs = 120_000)
        assertEquals(2, out.size)
        assertEquals(TranscriptSegment(240_000, 242_500, "Hello"), out[0])
        assertEquals(360_000L, out[1].endMs)
        assertEquals(240_000L, TranscriptAssembly.offsetOf(listOf(120_000L, 120_000L, 40_000L), 2))
        assertEquals(0L, TranscriptAssembly.offsetOf(listOf(120_000L), 0))
        assertEquals(0.5f, TranscriptAssembly.progress(1, 2), 0f)
        assertEquals(0f, TranscriptAssembly.progress(1, 0), 0f)
    }

    @Test
    fun checkpointRoundTripsAndValidates() {
        val c = TranscriptCheckpoint(listOf("/a/seg_000.m4a", "/a/seg_001.m4a"), done = 1, segments = segs.take(2), language = "en", source = "openai:whisper-1")
        val back = TranscriptCheckpoint.decode(TranscriptCheckpoint.encode(c))
        assertEquals(c, back)
        assertTrue(c.matches(listOf("/a/seg_000.m4a", "/a/seg_001.m4a")))
        assertFalse(c.matches(listOf("/a/seg_000.m4a")))
        assertNull(TranscriptCheckpoint.decode("not json"))
    }

    @Test
    fun promptsCarryTimestampsAndTask() {
        val req = TranscriptPrompts.request(TranscriptAction.SUMMARIZE, "Lecture 4", segs, "doc1", contextTokens = 128_000)
        assertEquals(AiTask.SUMMARIZE, req.task)
        assertEquals("doc1", req.documentId)
        val body = req.messages.single().text()
        assertTrue(body.contains("[0:09] When interest rates rise"))
        assertTrue(body.contains("Lecture 4"))
        assertFalse(body.contains("<my_notes>"))
        assertNotNull(req.system)
    }

    @Test
    fun compareIncludesNotesAndExplainIncludesPassage() {
        val compare = TranscriptPrompts.request(TranscriptAction.COMPARE, "L", segs, null, 32_000, notes = "bonds inverse to rates")
        assertTrue(compare.messages.single().text().contains("<my_notes>\nbonds inverse to rates"))
        val passage = TranscriptPrompts.passageAround(segs, 2)
        assertEquals(3, passage.lines().size)
        assertTrue(passage.startsWith("[0:04]"))
        val explain = TranscriptPrompts.request(TranscriptAction.EXPLAIN, "L", segs, null, 32_000, passage = passage)
        assertTrue(explain.messages.single().text().contains("<selected_passage>"))
        assertEquals("", TranscriptPrompts.passageAround(segs, 9))
    }

    @Test
    fun longTranscriptsAreCutOnLineBoundaries() {
        val text = (1..1_000).joinToString("\n") { "[0:00] line $it with some words" }
        val (fitted, cut) = TranscriptPrompts.fit(text, 2_000)
        assertTrue(cut)
        assertTrue(fitted.length <= 2_000)
        assertTrue(fitted.endsWith(TranscriptPrompts.TRUNCATION_NOTE))
        val beforeNote = fitted.removeSuffix("\n" + TranscriptPrompts.TRUNCATION_NOTE)
        assertTrue(beforeNote.lines().all { it.startsWith("[0:00] line ") && it.endsWith("words") })
        val (same, notCut) = TranscriptPrompts.fit("short", 2_000)
        assertEquals("short", same)
        assertFalse(notCut)
    }

    @Test
    fun budgetScalesWithContextWindow() {
        assertTrue(TranscriptPrompts.transcriptBudget(200_000) > TranscriptPrompts.transcriptBudget(32_000))
        assertEquals(TranscriptPrompts.transcriptBudget(null), TranscriptPrompts.transcriptBudget(32_000))
        assertTrue(TranscriptPrompts.transcriptBudget(1_000) >= 12_000)
    }
}
