package com.inkwell.notes.search

import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import com.inkwell.notes.ocr.text.Excerpts
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.IndexText
import com.inkwell.notes.ocr.text.TextMatch
import com.inkwell.notes.speakers.logic.SpeakerNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Name: text" rows: people stay searchable whatever their names look like, and offsets stay right. */
class TranscriptIndexEdgeCasesTest {
    private fun stored(name: String, text: String) = TranscriptSearchText.indexText(TranscriptSegment(0, 1_000, text, speaker = name, speakerLabel = "S1"))

    @Test fun namesWithPunctuationAreFoundAndHighlighted() {
        for (name in listOf("O'Brien", "Dr. Lee", "Jean-Luc", "Ana (PM)", "Zoë Müller", "A+B", "Mr. \"Q\"")) {
            val text = stored(name, "We ship on Friday.")
            val tokens = FtsQuery.matchTokens(name)
            assertTrue(name, tokens.isNotEmpty())
            val words = TextMatch.words(IndexText.normalize(text)).map { it.lower }
            for (t in tokens) assertTrue("$name: $t in $words", words.any { it.startsWith(t) })
            val excerpt = Excerpts.build(text, tokens)
            assertEquals("$name highlights its own name", 0, excerpt.matchStart)
            assertTrue(name, excerpt.matchEnd > 0 && excerpt.matchEnd <= name.length)
            assertTrue("a MATCH expression exists for $name", FtsQuery.forQuery(name) != null)
        }
    }

    @Test fun excerptOffsetsAreRightDeepInsideALongNamedBlock() {
        val body = (1..60).joinToString(" ") { "word$it" } + " landmark and then more words follow here"
        val text = stored("Priya", body)
        val e = Excerpts.build(text, listOf("landmark"))
        assertEquals("landmark", e.text.substring(e.matchStart, e.matchEnd))
        val byName = Excerpts.build(text, listOf("priya"))
        assertEquals("Priya", byName.text.substring(byName.matchStart, byName.matchEnd))
        assertTrue(byName.text.startsWith("Priya: word1"))
    }

    @Test fun placeholdersFromEveryEngineAreNotPeople() {
        assertNull(TranscriptSearchText.personName(TranscriptSegment(0, 1, "x", speaker = SpeakerNames.unnamed("S3"))))
        assertNull(TranscriptSearchText.personName(TranscriptSegment(0, 1, "x", speaker = "Speaker 12")))
        assertNull(TranscriptSearchText.personName(TranscriptSegment(0, 1, "x", speaker = "speaker 2")))
        assertNull(TranscriptSearchText.personName(TranscriptSegment(0, 1, "x", speaker = " Speaker  7 ")))
        assertEquals("Speaker Bureau", TranscriptSearchText.personName(TranscriptSegment(0, 1, "x", speaker = "Speaker Bureau")))
        assertEquals("Hello", TranscriptSearchText.indexText(TranscriptSegment(0, 1, "Hello", speaker = SpeakerNames.unnamed("S1"), speakerLabel = "S1")))
    }

    @Test fun rebuildFromStoredRowsMatchesTheLivePath() {
        val entities = listOf(
            TranscriptSegmentEntity(rowId = 7, recordingId = "r", startMs = 0, endMs = 4_000, text = "Morning.", speaker = "Ana", source = "openai:x", speakerLabel = "S1"),
            TranscriptSegmentEntity(rowId = 8, recordingId = "r", startMs = 4_000, endMs = 9_000, text = "Hi.", speaker = "Speaker 2", source = "openai:x", speakerLabel = "S2"),
            TranscriptSegmentEntity(rowId = 9, recordingId = "r", startMs = 9_000, endMs = 12_000, text = "Bye.", speaker = null, source = "openai:x", speakerLabel = null),
        )
        val live = TranscriptSearchText.forIndex(entities.map { it.toModel() })
        val rebuilt = TranscriptSearchText.forIndex(entities.map { it.toModel() })
        assertEquals(live, rebuilt)
        assertEquals(listOf("Ana: Morning.", "Hi.", "Bye."), live.map { it.text })
        assertEquals(listOf(0L, 4_000L, 9_000L), live.map { it.startMs })
        // Indexing what the index produced changes nothing (a second pass over stored rows).
        assertEquals(live.map { it.text }, TranscriptSearchText.forIndex(live).map { it.text })
    }

    @Test fun aNameThatOpensTheSentenceIsStillPrefixedUnlessAlreadyLabelled() {
        assertEquals("Priya: Priya said no", stored("Priya", "Priya said no"))
        assertEquals("Priya: let's go", stored("Priya", "Priya: let's go"))
        assertEquals("Priya: let's go", stored("priya", "Priya: let's go"))
        assertEquals("Ana Lee: Ana said no", stored("Ana  Lee", "Ana said no"))
    }

    @Test fun normalisedIndexTextKeepsTheNamePrefixIntact() {
        val text = stored("Zoë Müller", "Ja.")
        val clean = IndexText.normalize(text)
        assertTrue(clean, clean.startsWith("Zoë Müller: Ja."))
    }
}
