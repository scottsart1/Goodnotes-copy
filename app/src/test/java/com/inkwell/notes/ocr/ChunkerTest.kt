package com.inkwell.notes.ocr

import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.ocr.text.Chunker
import com.inkwell.notes.ocr.text.SourcedPassage
import com.inkwell.notes.ocr.text.TextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private var row = 0L

    private fun page(index: Int, text: String, doc: String = "d") =
        SourcedPassage(++row, IndexedPassage(doc, "p$index", index, null, null, TextSource.HANDWRITING, text))

    private fun transcript(rec: String, ms: Long, text: String) =
        SourcedPassage(++row, IndexedPassage("d", null, null, rec, ms, TextSource.TRANSCRIPT, text))

    private fun filler(words: Int, seed: String = "lorem") = (1..words).joinToString(" ") { "$seed$it" }

    @Test
    fun chunksStayWithinOnePageOrRecordingAndNearTheTarget() {
        val passages = listOf(
            page(0, filler(40)), page(0, filler(40, "ipsum")), page(0, filler(40, "dolor")),
            page(1, "short page"),
            transcript("r1", 0, "hello"), transcript("r1", 5_000, "world"),
        )
        val chunks = Chunker.chunk(passages)
        assertTrue(chunks.all { it.text.length <= Chunker.MAX })
        assertEquals(listOf("page:p0", "page:p0", "page:p0", "page:p1", "rec:r1"), chunks.map { it.group })
        assertEquals((0 until chunks.size).toList(), chunks.map { it.position })
        val rec = chunks.last()
        assertEquals("hello\nworld", rec.text)
        assertEquals(0L, rec.toPassage().timestampMs)
        assertEquals(setOf(5L, 6L), rec.rowIds)
    }

    @Test
    fun smallPassagesOfAPageAreMerged() {
        val chunks = Chunker.chunk(listOf(page(0, "a b c"), page(0, "d e f"), page(0, "g h i")))
        assertEquals(1, chunks.size)
        assertEquals("a b c\nd e f\ng h i", chunks[0].text)
        assertEquals(0, chunks[0].toPassage().pageIndex)
    }

    @Test
    fun longPassagesAreSplit() {
        val long = (1..60).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = Chunker.chunk(listOf(page(0, long)))
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.text.length <= Chunker.MAX })
        assertEquals(long.replace(" ", ""), chunks.joinToString("") { it.text.replace(" ", "").replace("\n", "") })
    }

    @Test
    fun rareQueryWordsScoreHighest() {
        val chunks = Chunker.chunk(
            listOf(
                page(0, "the market opened " + filler(100)),
                page(1, "the market closed after the inflation report " + filler(100, "x")),
                page(2, filler(110, "y")),
            ),
        )
        val scores = Chunker.keywordScores(chunks, Chunker.queryTerms("What did the inflation report say about the market?"))
        val best = scores.indices.maxBy { scores[it] }
        assertEquals("page:p1", chunks[best].group)
        assertEquals(0.0, scores[chunks.indexOfFirst { it.group == "page:p2" }], 0.0)
    }

    @Test
    fun selectionKeepsBestChunksWithNeighboursInReadingOrderWithinBudget() {
        val passages = (0 until 10).map { i -> page(i, (if (i == 6) "photosynthesis converts light " else "") + filler(80, "w$i")) }
        val chunks = Chunker.chunk(passages)
        val scores = Chunker.keywordScores(chunks, Chunker.queryTerms("photosynthesis"))
        val chosen = Chunker.select(chunks, scores, maxChars = 2_000)
        val pages = chosen.map { it.parts.first().pageIndex }
        assertTrue(6 in pages)
        assertTrue(5 in pages || 7 in pages)
        assertEquals(pages.sortedBy { it }, pages)
        assertTrue(chosen.sumOf { it.text.length + 1 } <= 2_000)
    }

    @Test
    fun withoutMatchesTheOpeningPassagesAreUsed() {
        val chunks = Chunker.chunk((0 until 5).map { page(it, filler(80, "w$it")) })
        // Each chunk is ~390 characters: two fit in 1000, a third would not.
        val chosen = Chunker.select(chunks, Chunker.keywordScores(chunks, listOf("absent")), maxChars = 1_000)
        assertEquals(listOf(0, 1), chosen.map { it.parts.first().pageIndex })
    }

    @Test
    fun aSingleChunkLargerThanTheBudgetIsTruncated() {
        val chunks = Chunker.chunk(listOf(page(0, filler(100))))
        val chosen = Chunker.select(chunks, Chunker.keywordScores(chunks, listOf("lorem5")), maxChars = 120)
        assertEquals(1, chosen.size)
        assertTrue(chosen[0].text.length <= 120)
    }

    @Test
    fun semanticSimilarityCanLiftAChunk() {
        val keyword = doubleArrayOf(1.0, 0.0, 0.0)
        val combined = Chunker.combine(keyword, listOf(null, 0.9f, 0.1f))
        assertTrue(combined[1] > combined[0])
        assertEquals(0.0, combined[2], 0.0)
    }

    @Test
    fun questionStopWordsAreIgnored() {
        assertEquals(listOf("photosynthesis", "work"), Chunker.queryTerms("How does photosynthesis work?"))
        assertEquals(listOf("the", "and"), Chunker.queryTerms("the and"))
    }

    @Test
    fun textSplitterPrefersSentenceEnds() {
        val text = "First sentence is here. " + "x".repeat(300) + ". Another one follows. " + "y".repeat(400)
        val parts = TextSplitter.split(text, target = 300, max = 500)
        assertTrue(parts.all { it.length <= 500 })
        assertTrue(parts.first().endsWith("."))
        val passages = TextSplitter.passages("Para one.\n\nPara two.\n\n\n" + "z ".repeat(700), target = 600, max = 1_000)
        assertEquals("Para one.\n\nPara two.", passages.first())
        assertTrue(passages.drop(1).all { it.length <= 1_000 })
    }
}
