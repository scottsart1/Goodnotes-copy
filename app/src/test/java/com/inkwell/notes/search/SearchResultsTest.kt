package com.inkwell.notes.search

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsTest {
    private fun hit(doc: String, source: TextSource, page: Int? = null, ts: Long? = null, rec: String? = null, kind: DocKind = DocKind.NOTEBOOK) =
        SearchHit(
            documentId = doc, documentTitle = "Title $doc", kind = kind, folderPath = "", pageId = page?.let { "p$it" },
            pageIndex = page, recordingId = rec, timestampMs = ts, source = source, excerpt = "x",
        )

    @Test
    fun groupsByDocumentInRankOrder() {
        val hits = listOf(
            hit("b", TextSource.HANDWRITING, 2),
            hit("a", TextSource.TITLE),
            hit("b", TextSource.OCR, 0),
            hit("a", TextSource.TYPED, 1),
        )
        val groups = SearchResults.group(hits, SearchFilter.ALL)
        assertEquals(listOf("b", "a"), groups.map { it.documentId })
        assertEquals(listOf(2, 0), groups[0].hits.map { it.pageIndex })
        assertTrue(groups[1].titleMatched)
        assertEquals(listOf(TextSource.TYPED), groups[1].rows.map { it.source })
        assertEquals(2, groups[0].firstPageIndex)
    }

    @Test
    fun filtersBySourceAndCounts() {
        val hits = listOf(
            hit("a", TextSource.HANDWRITING, 0),
            hit("a", TextSource.PDF_TEXT, 1),
            hit("b", TextSource.OCR, 0),
            hit("c", TextSource.TRANSCRIPT, ts = 5_000, rec = "r"),
            hit("d", TextSource.TAG),
            hit("d", TextSource.FOLDER),
        )
        assertEquals(listOf("a", "b"), SearchResults.group(hits, SearchFilter.PDF_OCR).map { it.documentId })
        assertEquals(listOf("d"), SearchResults.group(hits, SearchFilter.TITLES).map { it.documentId })
        assertTrue(SearchResults.group(hits, SearchFilter.TYPED).isEmpty())
        val counts = SearchResults.counts(hits)
        assertEquals(6, counts[SearchFilter.ALL])
        assertEquals(1, counts[SearchFilter.HANDWRITING])
        assertEquals(2, counts[SearchFilter.PDF_OCR])
        assertEquals(1, counts[SearchFilter.TRANSCRIPTS])
        assertEquals(2, counts[SearchFilter.TITLES])
        assertEquals(0, counts[SearchFilter.TYPED])
    }

    @Test
    fun locationLabels() {
        assertEquals("p.3", SearchResults.location(hit("a", TextSource.HANDWRITING, 2)))
        assertEquals("18:40", SearchResults.location(hit("a", TextSource.TRANSCRIPT, ts = 1_120_000, rec = "r")))
        assertEquals("1:02:05", SearchResults.location(hit("a", TextSource.TRANSCRIPT, ts = 3_725_000, rec = "r")))
        assertNull(SearchResults.location(hit("a", TextSource.TITLE)))
        assertEquals("0:00", SearchResults.formatTimestamp(-5))
    }

    @Test
    fun pagePreviewsOnlyForRealPages() {
        assertTrue(SearchResults.hasPagePreview(hit("a", TextSource.HANDWRITING, 0)))
        assertFalse(SearchResults.hasPagePreview(hit("a", TextSource.TRANSCRIPT, ts = 0, rec = "r")))
        assertFalse(SearchResults.hasPagePreview(hit("a", TextSource.TYPED, kind = DocKind.TYPED).copy(pageId = "typed")))
    }

    @Test
    fun recentQueriesAreMostRecentFirstDeduplicatedAndCapped() {
        var list = emptyList<String>()
        list = RecentQueries.add(list, "  inflation  ")
        list = RecentQueries.add(list, "bonds")
        list = RecentQueries.add(list, "Inflation")
        assertEquals(listOf("Inflation", "bonds"), list)
        list = RecentQueries.add(list, "   ")
        assertEquals(2, list.size)
        list = RecentQueries.add(list, "multiple   spaces\nhere")
        assertEquals("multiple spaces here", list.first())
        repeat(20) { list = RecentQueries.add(list, "q$it") }
        assertEquals(RecentQueries.MAX, list.size)
        assertEquals("q19", list.first())
        assertEquals(listOf("q19", "q17"), RecentQueries.remove(list, "Q18").take(2))
    }
}
