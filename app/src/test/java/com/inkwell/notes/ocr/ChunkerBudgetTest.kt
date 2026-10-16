package com.inkwell.notes.ocr

import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.ocr.text.Chunk
import com.inkwell.notes.ocr.text.Chunker
import com.inkwell.notes.ocr.text.SourcedPassage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Coverage: the retrieval budget is a hard cap on what goes into an AI prompt. Whatever the
 * document and scores look like, the selected text never exceeds it, reading order is kept and
 * something is returned whenever there is text and a budget.
 */
class ChunkerBudgetTest {
    private val random = Random(42)

    private fun passages(doc: String, pages: Int, perPage: Int): List<SourcedPassage> {
        var row = 0L
        return (0 until pages).flatMap { p ->
            (0 until perPage).map {
                val words = 1 + random.nextInt(120)
                val text = (0 until words).joinToString(" ") { w -> if (random.nextInt(9) == 0) "inflation" else "w$w" }
                SourcedPassage(row++, IndexedPassage(doc, "page-$p", p, null, null, TextSource.HANDWRITING, text))
            }
        }
    }

    private fun used(chunks: List<Chunk>) = chunks.sumOf { it.text.length + 1 }

    @Test
    fun selectionNeverExceedsTheBudgetAndKeepsReadingOrder() {
        repeat(60) {
            val chunks = Chunker.chunk(passages("d", 1 + random.nextInt(6), 1 + random.nextInt(8)))
            val terms = Chunker.queryTerms("what about inflation and w3")
            val scores = Chunker.combine(Chunker.keywordScores(chunks, terms), null)
            val budget = 50 + random.nextInt(6_000)
            val chosen = Chunker.select(chunks, scores, budget)
            assertTrue("nothing selected", chosen.isNotEmpty())
            assertTrue("budget $budget exceeded: ${used(chosen)}", chosen.sumOf { it.text.length } <= budget)
            val positions = chosen.map { it.position }
            assertEquals(positions.sorted(), positions)
            assertEquals(positions.distinct().size, positions.size)
        }
    }

    @Test
    fun openingFallbackRespectsTheBudgetToo() {
        val chunks = Chunker.chunk(passages("d", 4, 5))
        for (budget in listOf(1, 10, 100, 1_000, 10_000, 100_000)) {
            val opening = Chunker.opening(chunks, budget)
            assertTrue(opening.isNotEmpty())
            assertTrue(opening.sumOf { it.text.length } <= budget)
            assertEquals(opening.map { it.position }, opening.map { it.position }.sorted())
        }
        assertTrue(Chunker.select(emptyList(), DoubleArray(0), 1_000).isEmpty())
        assertTrue(Chunker.select(chunks, DoubleArray(chunks.size), 0).isEmpty())
    }

    @Test
    fun chunksCoverEveryPassageExactlyOnce() {
        val input = passages("d", 5, 6)
        val chunks = Chunker.chunk(input)
        assertEquals(input.map { it.rowId }.toSet(), chunks.flatMap { it.rowIds }.toSet())
        assertTrue(chunks.all { it.text.length <= Chunker.MAX + 1 || it.parts.size == 1 })
        // A chunk never mixes pages.
        chunks.forEach { c -> assertEquals(1, c.parts.map { it.pageId }.distinct().size) }
    }
}
