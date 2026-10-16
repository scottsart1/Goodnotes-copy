package com.inkwell.notes.ai

import com.inkwell.notes.ai.context.ContextAssembler
import com.inkwell.notes.ai.context.ContextInput
import com.inkwell.notes.ai.context.ContextScope
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.ocr.IndexedPassage
import com.inkwell.notes.ocr.SearchIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSearch(private val docs: Map<String, List<IndexedPassage>>) : SearchIndex {
    val retrieveCalls = mutableListOf<Triple<String?, String, Int>>()
    var retrieveResult: (String) -> List<IndexedPassage> = { docId -> docs[docId].orEmpty().takeLast(2) }

    override suspend fun search(query: String, limit: Int, semantic: Boolean): List<SearchHit> = emptyList()
    override suspend fun indexDocumentMeta(doc: DocumentMeta) = Unit
    override suspend fun replacePageText(docId: String, pageId: String, source: TextSource, blocks: List<RecognizedBlock>, engine: String) = Unit
    override suspend fun replaceTranscript(docId: String, recordingId: String, segments: List<TranscriptSegment>, engine: String) = Unit
    override suspend fun removeDocument(docId: String) = Unit
    override suspend fun documentText(docId: String): List<IndexedPassage> = docs[docId].orEmpty()
    override suspend fun retrieve(docId: String?, query: String, maxChars: Int): List<IndexedPassage> {
        retrieveCalls += Triple(docId, query, maxChars)
        return retrieveResult(docId ?: "")
    }
}

class ContextAssemblerTest {
    private fun page(doc: String, index: Int, text: String, source: TextSource = TextSource.HANDWRITING) =
        IndexedPassage(doc, "pg$index", index, null, null, source, text)

    private fun rec(doc: String, ms: Long, text: String) = IndexedPassage(doc, null, null, "rec1", ms, TextSource.TRANSCRIPT, text)

    private val titles = mapOf("d1" to "20261015_Macro", "d2" to "20261001_Reading")

    private fun assembler(search: SearchIndex) = ContextAssembler(search, docTitle = { titles[it] })

    @Test fun smallDocumentIsSentWholeWithLabels() = runBlocking {
        val search = FakeSearch(
            mapOf(
                "d1" to listOf(
                    IndexedPassage("d1", null, null, null, null, TextSource.TITLE, "20261015_Macro"),
                    page("d1", 0, "Inflation basics"), page("d1", 0, "CPI definition"), page("d1", 2, "Rates"),
                    rec("d1", 1_120_000, "The lecturer said rates lag."),
                ),
            ),
        )
        val ctx = assembler(search).assemble(ContextScope.DOCUMENT, ContextInput("d1"), "What is CPI?", emptyList(), retrieval = true, includeImage = true)
        val block = ctx.block!!
        assertTrue(block.startsWith("<document title=\"20261015_Macro\" label=\"this document\">"))
        assertTrue(block.contains("[p.1] Inflation basics\nCPI definition"))
        assertTrue(block.contains("[p.3] Rates"))
        assertTrue(block.contains("[rec 18:40] The lecturer said rates lag."))
        assertFalse("title passages are not repeated", block.contains("[doc]"))
        assertEquals(listOf("p.1", "p.3", "rec 18:40"), ctx.sources.map { it.label })
        assertEquals(2, ctx.sources[1].pageIndex)
        assertFalse(ctx.usedRetrieval)
        assertTrue(search.retrieveCalls.isEmpty())
    }

    @Test fun largeDocumentUsesRetrievalWithBudget() = runBlocking {
        val long = (0 until 30).map { page("d1", it, "Paragraph $it " + "x".repeat(600)) }
        val search = FakeSearch(mapOf("d1" to long))
        search.retrieveResult = { listOf(long[17], long[3]) }
        val ctx = assembler(search).assemble(ContextScope.DOCUMENT, ContextInput("d1"), "monetary policy", emptyList(), retrieval = true, includeImage = false)
        assertEquals(listOf(Triple<String?, String, Int>("d1", "monetary policy", 12_000)), search.retrieveCalls)
        assertTrue(ctx.usedRetrieval)
        val block = ctx.block!!
        // Reading order, not relevance order.
        assertTrue(block.indexOf("[p.4]") < block.indexOf("[p.18]"))
        assertFalse(block.contains("[p.1]"))
        assertTrue(ctx.notes.first().contains("most relevant"))
    }

    @Test fun retrievalOffSendsEverything() = runBlocking {
        val long = (0 until 30).map { page("d1", it, "P$it " + "y".repeat(600)) }
        val search = FakeSearch(mapOf("d1" to long))
        val ctx = assembler(search).assemble(ContextScope.DOCUMENT, ContextInput("d1"), "q", emptyList(), retrieval = false, includeImage = false)
        assertTrue(search.retrieveCalls.isEmpty())
        assertEquals(30, ctx.sources.size)
        assertFalse(ctx.usedRetrieval)
    }

    @Test fun noRetrievalMatchFallsBackToSpreadSample() = runBlocking {
        val long = (0 until 40).map { page("d1", it, "Q$it " + "z".repeat(800)) }
        val search = FakeSearch(mapOf("d1" to long))
        search.retrieveResult = { emptyList() }
        val ctx = assembler(search).assemble(ContextScope.DOCUMENT, ContextInput("d1"), "summarize", emptyList(), retrieval = true, includeImage = false)
        val pages = ctx.sources.mapNotNull { it.pageIndex }
        assertTrue(pages.size in 5..15)
        assertTrue("spread across the document", pages.last() > 20)
        assertTrue(ctx.block!!.length < 16_000)
    }

    @Test fun pageAndSelectionScopes() = runBlocking {
        val search = FakeSearch(mapOf("d1" to listOf(page("d1", 0, "Page one"), page("d1", 1, "Page two"))))
        val pageCtx = assembler(search).assemble(ContextScope.PAGE, ContextInput("d1", pageId = "pg1", pageIndex = 1), "q", emptyList(), true, false)
        assertTrue(pageCtx.block!!.contains("[p.2] Page two"))
        assertFalse(pageCtx.block!!.contains("Page one"))

        val png = byteArrayOf(9, 9, 9)
        val sel = assembler(search).assemble(
            ContextScope.SELECTION, ContextInput("d1", selectionText = "x^2 + 1 = 0", selectionPng = png, pageId = "pg1", pageIndex = 1),
            "Solve", emptyList(), retrieval = true, includeImage = true,
        )
        assertTrue(sel.block!!.contains("[p.2] x^2 + 1 = 0"))
        assertEquals(1, sel.images.size)
        assertEquals("p.2", sel.sources.single().label)

        val noImage = assembler(search).assemble(
            ContextScope.SELECTION, ContextInput("d1", selectionText = null, selectionPng = png, pageIndex = 1), "Solve", emptyList(), true, includeImage = false,
        )
        assertTrue(noImage.isEmpty)
        assertTrue(noImage.notes.single().contains("no recognised text"))
    }

    @Test fun addedDocumentsAreTaggedAndHandledTheSameWay() = runBlocking {
        val search = FakeSearch(mapOf("d1" to listOf(page("d1", 0, "Mine")), "d2" to listOf(page("d2", 3, "Theirs"))))
        val ctx = assembler(search).assemble(ContextScope.DOCUMENT, ContextInput("d1"), "compare", listOf("d2", "d1"), true, false)
        val block = ctx.block!!
        assertTrue(block.contains("<document title=\"20261001_Reading\" label=\"D2\">"))
        assertTrue(block.contains("[D2 p.4] Theirs"))
        assertEquals(listOf("p.1", "D2 p.4"), ctx.sources.map { it.label })
        assertEquals("D2", ctx.sources[1].docTag)
        assertEquals("d2", ctx.sources[1].documentId)
    }

    @Test fun noScopeAndNoExtrasMeansNoContext() = runBlocking {
        val ctx = assembler(FakeSearch(emptyMap())).assemble(null, ContextInput("d1"), "hello", emptyList(), true, true)
        assertTrue(ctx.isEmpty)
        assertTrue(ctx.sources.isEmpty())
    }
}
