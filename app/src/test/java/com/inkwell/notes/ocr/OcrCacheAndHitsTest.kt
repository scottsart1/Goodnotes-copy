package com.inkwell.notes.ocr

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.ocr.text.Candidate
import com.inkwell.notes.ocr.text.DocInfo
import com.inkwell.notes.ocr.text.FtsQuery
import com.inkwell.notes.ocr.text.Ranked
import com.inkwell.notes.ocr.text.HitAssembler
import com.inkwell.notes.ocr.work.CachedImageOcr
import com.inkwell.notes.ocr.work.ImageOcrCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrCacheAndHitsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val result = CachedImageOcr(
        engine = "mlkit-text",
        blocks = listOf(CachedImageOcr.CachedBlock("Total 42", listOf(0.1f, 0.2f, 0.5f, 0.3f), 0.9f)),
        confidence = 0.9f,
    )

    @Test
    fun cachedResultsAreKeyedByItemAndSize() = runTest {
        val cache = ImageOcrCache(tmp.newFolder("cache"))
        assertNull(cache.get("doc-1", "asset-a.png", 100))
        cache.put("doc-1", "asset-a.png", 100, result)
        assertEquals(result, cache.get("doc-1", "asset-a.png", 100))
        // A different size means a different file content.
        assertNull(cache.get("doc-1", "asset-a.png", 101))
        assertNull(cache.get("doc-2", "asset-a.png", 100))
    }

    @Test
    fun newResultsReplaceOlderVersionsOfTheSameItem() = runTest {
        val dir = tmp.newFolder("cache")
        val cache = ImageOcrCache(dir)
        cache.put("doc-1", "pdfpage-0", 100, result)
        val ai = CachedImageOcr("ai:OPENAI:gpt-4o", listOf(CachedImageOcr.CachedBlock("Total [?42?]", listOf(0f, 0f, 1f, 1f))))
        cache.put("doc-1", "pdfpage-0", 200, ai)
        assertNull(cache.get("doc-1", "pdfpage-0", 100))
        assertTrue(cache.get("doc-1", "pdfpage-0", 200)!!.isAi)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun pruneAndRemoveDropOnlyTheGivenDocuments() = runTest {
        val dir = tmp.newFolder("cache")
        val cache = ImageOcrCache(dir)
        cache.put("keep", "asset-1", 1, result)
        cache.put("gone", "asset-1", 1, result)
        cache.put("other", "asset-1", 1, result)
        cache.prune(setOf("keep", "other"))
        assertNull(cache.get("gone", "asset-1", 1))
        assertEquals(result, cache.get("keep", "asset-1", 1))
        cache.removeDocument("other")
        assertNull(cache.get("other", "asset-1", 1))
        assertEquals(result, cache.get("keep", "asset-1", 1))
    }

    @Test
    fun corruptCacheFilesAreIgnoredAndRemoved() = runTest {
        val dir = tmp.newFolder("cache")
        val cache = ImageOcrCache(dir)
        cache.put("d", "asset-x", 5, result)
        dir.listFiles()!!.single().writeText("{not json")
        assertNull(cache.get("d", "asset-x", 5))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun hitsCarryDocumentFactsExcerptAndParsedBounds() {
        val doc = DocInfo("d1", "20261015_Financial Markets", DocKind.PDF, "f1", 0L, "/thumbs/d1.webp")
        val c = Candidate(
            key = "row:7", documentId = "d1", pageId = "p3", pageIndex = 2, recordingId = null, timestampMs = null,
            source = TextSource.OCR, text = "Bond yields rose as inflation expectations climbed.", confidence = 0.8f,
            bounds = "10,20,300,40",
        )
        val hit = HitAssembler.toHit(Ranked(c, 7.5), doc, "Economics / Term 1", FtsQuery.matchTokens("inflation"))
        assertEquals("20261015_Financial Markets", hit.documentTitle)
        assertEquals(DocKind.PDF, hit.kind)
        assertEquals("Economics / Term 1", hit.folderPath)
        assertEquals(2, hit.pageIndex)
        assertEquals("inflation", hit.excerpt.substring(hit.matchStart, hit.matchEnd))
        assertArrayEquals(floatArrayOf(10f, 20f, 300f, 40f), hit.bounds!!, 0f)
        assertEquals("/thumbs/d1.webp", hit.thumbnailPath)
        assertEquals(7.5, hit.score, 0.0)
    }
}
