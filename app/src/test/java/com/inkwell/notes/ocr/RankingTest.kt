package com.inkwell.notes.ocr

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.ocr.text.Candidate
import com.inkwell.notes.ocr.text.DocInfo
import com.inkwell.notes.ocr.text.FolderPaths
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.HitAssembler
import com.inkwell.notes.ocr.text.RankInput
import com.inkwell.notes.ocr.text.Ranking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun terms(q: String) = FtsQuery.matchTokens(q)
    private fun score(text: String, source: TextSource, q: String, ageDays: Long = 0, conf: Float? = null, sim: Float? = null) =
        Ranking.score(RankInput(text, source, now - ageDays * day, conf, sim), terms(q), now)

    @Test
    fun exactPhraseBeatsAllWordsEvenInATitle() {
        val phraseBody = score("we discussed interest rates today", TextSource.HANDWRITING, "interest rates", ageDays = 400)
        val allWordsTitle = score("Rates and interest", TextSource.TITLE, "interest rates", ageDays = 0)
        assertTrue(phraseBody > allWordsTitle)
    }

    @Test
    fun allWordsBeatPartialTitleMatches() {
        val allWords = score("rates went up; interest followed", TextSource.OCR, "interest rates", ageDays = 400)
        val partialTitle = score("Interest", TextSource.TITLE, "interest rates", ageDays = 0)
        assertTrue(allWords > partialTitle)
    }

    @Test
    fun titleBoostBeatsRecencyAtTheSameMatchQuality() {
        val oldTitle = score("Market notes", TextSource.TITLE, "market", ageDays = 365)
        val newBody = score("market notes", TextSource.TYPED, "market", ageDays = 0)
        assertTrue(oldTitle > newBody)
    }

    @Test
    fun recencyBreaksTies() {
        assertTrue(score("market", TextSource.TYPED, "market", ageDays = 1) > score("market", TextSource.TYPED, "market", ageDays = 90))
        assertEquals(Ranking.RECENCY_MAX / 2, Ranking.recency(now - 30 * day, now), 1e-9)
        assertEquals(0.0, Ranking.recency(0, now), 0.0)
    }

    @Test
    fun lowConfidenceCostsALittle() {
        assertTrue(score("market", TextSource.OCR, "market", conf = 0.95f) > score("market", TextSource.OCR, "market", conf = 0.3f))
    }

    @Test
    fun semanticOnlyMatchesRankBelowKeywordMatches() {
        val semanticOnly = score("monetary policy tightening", TextSource.HANDWRITING, "interest rates", sim = 0.8f)
        val keyword = score("rates of interest", TextSource.HANDWRITING, "interest rates", ageDays = 300)
        assertTrue(semanticOnly > 0)
        assertTrue(keyword > semanticOnly)
        assertEquals(0.0, score("unrelated", TextSource.TYPED, "interest rates"), 0.0)
    }

    private fun doc(id: String, title: String = id, modified: Long = now, folder: String? = null, tags: List<String> = emptyList()) =
        DocInfo(id, title, DocKind.NOTEBOOK, folder, modified, null, tags)

    private fun row(id: Long, doc: String, text: String, source: TextSource = TextSource.HANDWRITING, page: Int? = 0) =
        Candidate(Candidate.keyForRow(id, doc, source, text), doc, "p$id", page, null, null, source, text)

    @Test
    fun rankDropsNonMatchesDeduplicatesAndOrders() {
        val docs = mapOf("a" to doc("a", modified = now - 50 * day), "b" to doc("b", modified = now))
        val candidates = listOf(
            row(1, "a", "interest rates rose"),
            row(2, "b", "rates and interest"),
            row(3, "b", "nothing relevant"),
            Candidate(Candidate.titleKey("a"), "a", null, null, null, null, TextSource.TITLE, "Interest"),
            Candidate(Candidate.titleKey("a"), "a", null, null, null, null, TextSource.TITLE, "Interest rates"),
            row(4, "zz", "interest rates"), // unknown (trashed) document
        )
        val ranked = HitAssembler.rank(candidates, docs, terms("interest rates"), now, 10)
        assertEquals(listOf("title:a", "row:1", "row:2"), ranked.map { it.candidate.key })
        assertEquals("Interest rates", ranked.first().candidate.text)
        assertEquals(2, HitAssembler.rank(candidates, docs, terms("interest rates"), now, 2).size)
    }

    @Test
    fun mergeAttachesSimilarityToKeywordMatches() {
        val keyword = listOf(row(1, "a", "interest rates"), row(2, "a", "bond yields"))
        val semantic = listOf(row(2, "a", "bond yields").copy(semantic = 0.7f), row(9, "a", "central bank").copy(semantic = 0.5f))
        val merged = HitAssembler.merge(keyword, semantic)
        assertEquals(listOf("row:1", "row:2", "row:9"), merged.map { it.key })
        assertEquals(null, merged[0].semantic)
        assertEquals(0.7f, merged[1].semantic)
        assertEquals(0.5f, merged[2].semantic)
    }

    @Test
    fun metaMatchesFindTitleInfixesTagsAndFolders() {
        val folders = mapOf(
            "f1" to FolderPaths.Node("f1", "Economics", null),
            "f2" to FolderPaths.Node("f2", "Term 1", "f1"),
        )
        val docs = listOf(
            doc("d1", title = "20261015_Financial Markets"),
            doc("d2", title = "Lecture", tags = listOf("markets", "exam")),
            doc("d3", title = "Week 3", folder = "f2"),
            doc("d4", title = "Unrelated"),
        )
        val byTitle = HitAssembler.metaMatches(docs, folders, terms("arkets"), "arkets")
        assertEquals(setOf("title:d1", "tag:d2:markets"), byTitle.map { it.key }.toSet())

        val byFolder = HitAssembler.metaMatches(docs, folders, terms("economics"), "economics")
        val folderHit = byFolder.single()
        assertEquals(TextSource.FOLDER, folderHit.source)
        assertEquals("d3", folderHit.documentId)
        assertEquals("Economics / Term 1", folderHit.text)

        val underscore = HitAssembler.metaMatches(docs, folders, terms("financial markets"), "financial markets")
        assertEquals(listOf("title:d1"), underscore.map { it.key })
    }

    @Test
    fun folderPathsSurviveCycles() {
        val nodes = mapOf("a" to FolderPaths.Node("a", "A", "b"), "b" to FolderPaths.Node("b", "B", "a"))
        assertEquals("A / B", FolderPaths.path(nodes, "b"))
        assertEquals("", FolderPaths.path(nodes, "missing"))
        assertEquals("", FolderPaths.path(nodes, null))
    }
}
