package com.inkwell.notes.pdf

import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Cache, sniffing, text cleanup, error mapping and page templates. */
class PdfSupportTest {

    // ---- LRU cache

    @Test fun lruEvictsLeastRecentlyUsedBySize() {
        val cache = SizedLruCache<String, String>(maxSize = 10, maxEntrySize = 10) { it.length.toLong() }
        cache.put("a", "aaaa")
        cache.put("b", "bbbb")
        cache.get("a") // a is now most recent
        cache.put("c", "cccc") // 12 > 10: evicts b
        assertTrue(cache.containsKey("a"))
        assertFalse(cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
        assertEquals(8, cache.currentSize())
    }

    @Test fun oversizedEntriesAreNotCachedAndDoNotFlushOthers() {
        val cache = SizedLruCache<String, String>(maxSize = 10, maxEntrySize = 5) { it.length.toLong() }
        cache.put("a", "aaa")
        assertFalse(cache.put("big", "123456"))
        assertNull(cache.get("big"))
        assertEquals("aaa", cache.get("a"))
    }

    @Test fun replacingAnEntryUpdatesTheSize() {
        val cache = SizedLruCache<String, String>(maxSize = 100) { it.length.toLong() }
        cache.put("a", "aaaa")
        cache.put("a", "aa")
        assertEquals(2, cache.currentSize())
        assertEquals(1, cache.count())
    }

    private class Shrinking(var bytes: Long)

    @Test fun sizesAreRecordedWhenAdded() {
        // A recycled bitmap reports 0 bytes; removing it must still free its full size.
        val cache = SizedLruCache<String, Shrinking>(maxSize = 100, maxEntrySize = 100) { it.bytes }
        val value = Shrinking(40)
        cache.put("a", value)
        value.bytes = 0
        cache.remove("a")
        assertEquals(0, cache.currentSize())
        cache.put("b", Shrinking(60))
        cache.put("c", Shrinking(40))
        assertTrue(cache.containsKey("b") && cache.containsKey("c"))
    }

    @Test fun removeIfDropsOneDocument() {
        val cache = SizedLruCache<RenderKey, String>(maxSize = 100) { it.length.toLong() }
        cache.put(RenderKey("d1", 0, 256), "x")
        cache.put(RenderKey("d1", 1, 256), "x")
        cache.put(RenderKey("d2", 0, 256), "x")
        assertEquals(2, cache.removeIf { it.docId == "d1" })
        assertEquals(1, cache.count())
        assertEquals(1, cache.currentSize())
    }

    @Test fun trimToFractionKeepsTheNewest() {
        val cache = SizedLruCache<Int, String>(maxSize = 100) { it.length.toLong() }
        for (i in 0 until 10) cache.put(i, "x".repeat(10))
        cache.trimToFraction(0.3f)
        assertEquals(3, cache.count())
        assertTrue(cache.containsKey(9) && cache.containsKey(8) && cache.containsKey(7))
        cache.clear()
        assertEquals(0, cache.currentSize())
    }

    // ---- header sniffing

    @Test fun recognisesPdfHeader() {
        assertTrue(PdfSniffer.looksLikePdf("%PDF-1.7\n%âãÏÓ".toByteArray()))
        assertTrue(PdfSniffer.looksLikePdf(ByteArray(300) { ' '.code.toByte() } + "%PDF-1.4".toByteArray()))
        assertFalse(PdfSniffer.looksLikePdf("PK\u0003\u0004 zip file".toByteArray()))
        assertFalse(PdfSniffer.looksLikePdf("%PD".toByteArray()))
        assertFalse(PdfSniffer.looksLikePdf(ByteArray(0)))
    }

    @Test fun headerMustBeWithinTheFirstKilobyte() {
        val late = ByteArray(PdfSniffer.HEADER_WINDOW) { 'x'.code.toByte() } + "%PDF-1.4".toByteArray()
        assertFalse(PdfSniffer.looksLikePdf(late))
        val partial = "%PDF-1.4".toByteArray()
        assertFalse(PdfSniffer.looksLikePdf(partial, length = 3))
    }

    @Test fun sniffsFiles() {
        val pdf = java.io.File.createTempFile("sniff", ".pdf")
        val txt = java.io.File.createTempFile("sniff", ".txt")
        try {
            pdf.writeText("%PDF-1.5\n1 0 obj\n")
            txt.writeText("hello")
            assertTrue(PdfSniffer.looksLikePdf(pdf))
            assertFalse(PdfSniffer.looksLikePdf(txt))
        } finally {
            pdf.delete()
            txt.delete()
        }
    }

    // ---- text

    @Test fun normalizesExtractedText() {
        val raw = "Title  \r\n\r\n\r\n\r\nFirst line  \nSecond\u0000 line\n\n\n"
        assertEquals("Title\n\nFirst line\nSecond line", PdfText.normalize(raw))
        assertEquals("", PdfText.normalize("   \n \n"))
        assertEquals("", PdfText.normalize(""))
    }

    @Test fun textCacheKeysAreScopedToTheirDocument() {
        val key = PdfText.cacheKey("doc-1", 4)
        assertTrue(PdfText.isKeyOf(key, "doc-1"))
        assertFalse(PdfText.isKeyOf(key, "doc"))
        assertFalse(PdfText.isKeyOf(PdfText.cacheKey("doc-10", 4), "doc-1"))
    }

    // ---- errors

    @Test fun detectsFullDisk() {
        assertTrue(PdfErrors.isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(PdfErrors.isOutOfSpace(RuntimeException("wrapped", IOException("No space left on device"))))
        assertFalse(PdfErrors.isOutOfSpace(IOException("Broken pipe")))
        assertFalse(PdfErrors.isOutOfSpace(null))
    }

    // ---- page templates for copied paper pages

    @Test fun templateReproducesPortraitAndLandscapePages() {
        val paper = PaperSpec(style = PaperStyle.GRID, color = 0xFFFFF8E7.toInt())
        val portrait = page(widthPt = 612f, heightPt = 792f, paper = paper)
        val t = DocumentAssembler.templateFor(portrait)
        assertEquals(612f, t.widthPt(), 0.01f)
        assertEquals(792f, t.heightPt(), 0.01f)
        assertEquals(Orientation.PORTRAIT, t.orientation)
        assertEquals(paper, t.paper)

        val landscape = DocumentAssembler.templateFor(page(widthPt = 842f, heightPt = 595f))
        assertEquals(842f, landscape.widthPt(), 0.01f)
        assertEquals(595f, landscape.heightPt(), 0.01f)
        assertEquals(Orientation.LANDSCAPE, landscape.orientation)
    }

    @Test fun unboundedPagesFallBackToA4() {
        assertEquals(595f to 842f, DocumentAssembler.sizeOf(page(widthPt = 0f, heightPt = 0f)))
    }

    private fun page(widthPt: Float, heightPt: Float, paper: PaperSpec = PaperSpec()) =
        PageMeta(id = "p", documentId = "d", index = 0, widthPt = widthPt, heightPt = heightPt, paper = paper)
}
