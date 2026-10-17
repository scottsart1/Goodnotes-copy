package com.inkwell.notes.search

import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.ocr.text.Excerpts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSearchTextTest {
    @Test fun namedBlocksAreIndexedWithTheName() {
        val s = TranscriptSegment(65_000, 70_000, "  The budget is final. ", speaker = "Priya", speakerLabel = "S1")
        assertEquals("Priya: The budget is final.", TranscriptSearchText.indexText(s))
        val stored = TranscriptSearchText.forIndex(listOf(s)).single()
        assertEquals(65_000L to 70_000L, stored.startMs to stored.endMs)
        assertEquals("S1", stored.speakerLabel)
    }

    @Test fun unnamedVoicesAndUnknownSpeakersAreNotPeople() {
        assertEquals("Hello", TranscriptSearchText.indexText(TranscriptSegment(0, 1, "Hello")))
        assertEquals("Hello", TranscriptSearchText.indexText(TranscriptSegment(0, 1, "Hello", speaker = "Speaker 3", speakerLabel = "S3")))
        assertEquals("Hello", TranscriptSearchText.indexText(TranscriptSegment(0, 1, "Hello", speaker = "  ")))
        assertNull(TranscriptSearchText.personName(TranscriptSegment(0, 1, "x", speaker = "speaker12")))
        assertEquals("Speaker Bureau", TranscriptSearchText.personName(TranscriptSegment(0, 1, "x", speaker = "Speaker  Bureau")))
        val plain = TranscriptSegment(0, 1, "Hello")
        assertSame("unchanged blocks are passed through", plain, TranscriptSearchText.forIndex(listOf(plain)).single())
    }

    @Test fun indexingTwiceDoesNotRepeatTheName() {
        val once = TranscriptSearchText.forIndex(listOf(TranscriptSegment(0, 1, "Thanks", speaker = "Dr. Lee"))).single()
        assertEquals("Dr. Lee: Thanks", once.text)
        assertEquals("Dr. Lee: Thanks", TranscriptSearchText.forIndex(listOf(once)).single().text)
        assertEquals("", TranscriptSearchText.indexText(TranscriptSegment(0, 1, "  ", speaker = "Ana")))
    }

    @Test fun excerptHighlightsTheNameAndTheWordsOfTheStoredText() {
        val text = TranscriptSearchText.indexText(TranscriptSegment(0, 1, "We agreed to ship on Friday.", speaker = "Priya"))
        val byName = Excerpts.build(text, listOf("priya"))
        assertEquals("Priya", byName.text.substring(byName.matchStart, byName.matchEnd))
        val byWord = Excerpts.build(text, listOf("friday"))
        assertEquals("Friday", byWord.text.substring(byWord.matchStart, byWord.matchEnd))
        assertTrue(byWord.text.startsWith("Priya: "))
    }
}
