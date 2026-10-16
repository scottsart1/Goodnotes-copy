package com.inkwell.notes.ocr

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.ocr.text.AiTranscription
import com.inkwell.notes.ocr.text.OrderRow
import com.inkwell.notes.ocr.text.ReadingOrder
import com.inkwell.notes.ocr.text.TypedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingOrderAndTextTest {
    private fun row(id: Long, page: String?, source: TextSource, bounds: FloatArray? = null, rec: String? = null, ts: Long? = null) =
        OrderRow(id, page, rec, source, bounds, ts)

    private fun sort(rows: List<OrderRow>, pages: Map<String, Int>, recs: Map<String, Long> = emptyMap()) =
        ReadingOrder.sort(rows, { it }, pages, recs).map { it.rowId }

    @Test
    fun metadataPagesThenTranscripts() {
        val rows = listOf(
            row(1, null, TextSource.TRANSCRIPT, rec = "r2", ts = 0),
            row(2, "pB", TextSource.HANDWRITING, floatArrayOf(0f, 10f, 50f, 20f)),
            row(3, null, TextSource.TAG),
            row(4, "pA", TextSource.TYPED, floatArrayOf(0f, 10f, 50f, 20f)),
            row(5, null, TextSource.TITLE),
            row(6, null, TextSource.TRANSCRIPT, rec = "r1", ts = 9_000),
            row(7, null, TextSource.TRANSCRIPT, rec = "r1", ts = 1_000),
        )
        val order = sort(rows, mapOf("pA" to 0, "pB" to 1), mapOf("r1" to 100L, "r2" to 200L))
        assertEquals(listOf(5L, 3L, 4L, 2L, 7L, 6L, 1L), order)
    }

    @Test
    fun withinAPageUnplacedTextFirstThenLinesTopToBottomLeftToRight() {
        val rows = listOf(
            row(1, "p", TextSource.HANDWRITING, floatArrayOf(300f, 104f, 400f, 120f)), // same line as 2, right
            row(2, "p", TextSource.HANDWRITING, floatArrayOf(20f, 100f, 200f, 118f)),
            row(3, "p", TextSource.OCR, floatArrayOf(20f, 40f, 400f, 60f)),
            row(4, "p", TextSource.PDF_TEXT),
            row(5, "p", TextSource.HANDWRITING, floatArrayOf(20f, 200f, 100f, 220f)),
        )
        assertEquals(listOf(4L, 3L, 2L, 1L, 5L), sort(rows, mapOf("p" to 0)))
    }

    @Test
    fun rowsOfDeletedPagesAreDroppedAndTypedTextKept() {
        val rows = listOf(
            row(1, "gone", TextSource.HANDWRITING),
            row(2, ReadingOrder.TYPED_PAGE, TextSource.TYPED),
            row(3, ReadingOrder.TYPED_PAGE, TextSource.TYPED),
        )
        assertEquals(listOf(2L, 3L), sort(rows, emptyMap()))
    }

    @Test
    fun aiMarkersAreCollectedAndKeptInTheText() {
        val raw = "```markdown\nThe [?quick?] brown fox\n\njumps over [illegible] dog [ILLEGIBLE].\n```"
        val p = AiTranscription.parse(raw)
        assertEquals("The [?quick?] brown fox\n\njumps over [illegible] dog [ILLEGIBLE].", p.text)
        assertEquals(listOf("[?quick?]", "[illegible]", "[ILLEGIBLE]"), p.uncertain)
        assertEquals(2, p.paragraphs.size)
    }

    @Test
    fun aiTextWithoutFenceOrMarkers() {
        val p = AiTranscription.parse("  Plain text.  ")
        assertEquals("Plain text.", p.text)
        assertTrue(p.uncertain.isEmpty())
        assertEquals(listOf("Plain text."), p.paragraphs)
        assertTrue(AiTranscription.parse("").paragraphs.isEmpty())
        // An unterminated fence is left alone rather than eating text.
        assertEquals("```\nkept", AiTranscription.parse("```\nkept").text)
    }

    @Test
    fun typedDocumentPassages() {
        val doc = TypedDocument(
            blocks = listOf(
                TypedBlock("1", BlockType.HEADING, "Supply and demand", level = 1),
                TypedBlock("2", BlockType.PARAGRAPH, "Prices rise when demand exceeds supply."),
                TypedBlock("3", BlockType.DIVIDER),
                TypedBlock("4", BlockType.TABLE, rows = listOf(listOf("Good", "Price"), listOf("Tea", "3"))),
                TypedBlock("5", BlockType.HEADING, "Elasticity", level = 2),
                TypedBlock("6", BlockType.BULLET, "  "),
                TypedBlock("7", BlockType.CHECKLIST, "Read chapter 4", checked = true),
            ),
        )
        assertEquals(
            listOf("Supply and demand", "Prices rise when demand exceeds supply.", "Good | Price\nTea | 3", "Elasticity", "Read chapter 4"),
            TypedText.blockTexts(doc),
        )
        assertEquals(
            listOf("Supply and demand\nPrices rise when demand exceeds supply.", "Good | Price\nTea | 3", "Elasticity\nRead chapter 4"),
            TypedText.passages(doc),
        )
    }
}
