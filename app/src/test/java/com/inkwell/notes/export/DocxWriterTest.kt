package com.inkwell.notes.export

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.export.format.DocumentTextBuilder
import com.inkwell.notes.export.format.DocxImage
import com.inkwell.notes.export.format.DocxImageSource
import com.inkwell.notes.export.format.DocxWriter
import com.inkwell.notes.export.format.ExportBlock
import com.inkwell.notes.export.format.ExportDocument
import com.inkwell.notes.export.format.ExportSection
import com.inkwell.notes.export.format.InlineRun
import com.inkwell.notes.export.format.ListKind
import com.inkwell.notes.export.format.PageTextInput
import com.inkwell.notes.export.format.TranscriptInput
import com.inkwell.notes.export.format.XmlEscape
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriterTest {

    @Test fun escapesMarkupAndDropsCharactersXmlForbids() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", XmlEscape.text("a & b <c> \"d\" 'e'"))
        assertEquals("tab\there\nline", XmlEscape.text("tab\there\nline"))
        assertEquals("ab", XmlEscape.text("a\u0000\u0007\u001Fb"))
        assertEquals("x", XmlEscape.text("x￾￿"))
        assertEquals("😀 ok", XmlEscape.text("😀 ok")) // a valid surrogate pair stays
        assertEquals("ab", XmlEscape.text("a\uD83Db"))                   // a lone high surrogate goes
        assertEquals("ab", XmlEscape.text("a\uDE00b"))                   // and a lone low one
        assertEquals("1 < 2", XmlEscape.clean("1 < 2\u0001"))
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private val images = object : DocxImageSource {
        override suspend fun asset(assetId: String) = if (assetId == "photo.png") DocxImage(png, "png", 1200, 800) else null
        override suspend fun page(pageIndex: Int) = DocxImage(png, "png", 900, 1270)
    }

    private fun sample(): ExportDocument {
        val typed = TypedDocument(
            blocks = listOf(
                TypedBlock("h", BlockType.HEADING, "Supply & demand <basics>", level = 1),
                TypedBlock("p", BlockType.PARAGRAPH, "Bold link\tand tab\nnext line", spans = listOf(TextSpan(0, 4, bold = true), TextSpan(5, 9, link = "https://example.com/a?b=1&c=2"))),
                TypedBlock("b1", BlockType.BULLET, "first"),
                TypedBlock("b2", BlockType.BULLET, "nested", level = 1),
                TypedBlock("n1", BlockType.NUMBERED, "one"),
                TypedBlock("n2", BlockType.NUMBERED, "two"),
                TypedBlock("x", BlockType.PARAGRAPH, "break"),
                TypedBlock("n3", BlockType.NUMBERED, "again one"),
                TypedBlock("c", BlockType.CHECKLIST, "done", checked = true),
                TypedBlock("q", BlockType.QUOTE, "\"Quoted\" text"),
                TypedBlock("code", BlockType.CODE, "val x = 1 < 2\n\tprintln(x)", language = "kotlin"),
                TypedBlock("t", BlockType.TABLE, rows = listOf(listOf("Term", "Meaning"), listOf("P&L", "Profit <and> loss"))),
                TypedBlock("i", BlockType.IMAGE, "Chart caption", assetId = "photo.png"),
                TypedBlock("missing", BlockType.IMAGE, "", assetId = "missing.png"),
                TypedBlock("d", BlockType.DIVIDER),
            ),
        )
        val built = DocumentTextBuilder.build(
            title = "20261015_Financial Markets & \u0001Co",
            typed = typed,
            pages = listOf(PageTextInput(pageIndex = 2, pdfText = "Page text", hasInk = true)),
            transcripts = listOf(TranscriptInput("Lecture", listOf(TranscriptSegment(5_000, 9_000, "Welcome <all>", "Ana")))),
            includePageImages = true,
        )
        return built
    }

    private suspend fun writeEntries(doc: ExportDocument): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        DocxWriter(images).write(doc, out, createdAt = 1_760_000_000_000, modifiedAt = 1_760_000_100_000)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun attrs(doc: org.w3c.dom.Document, ns: String, tag: String, attrNs: String?, attr: String): List<String> {
        val nodes = doc.getElementsByTagNameNS(ns, tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).let { e -> if (attrNs == null) e.getAttribute(attr) else e.getAttributeNS(attrNs, attr) } }
    }

    @Test fun packageHasEveryRequiredPartAndAllXmlIsWellFormed() = runTest {
        val entries = writeEntries(sample())
        for (name in listOf(
            "[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml",
            "word/_rels/document.xml.rels", "docProps/core.xml", "docProps/app.xml", "word/media/image1.png", "word/media/image2.png",
        )) assertTrue("missing $name in ${entries.keys}", name in entries)
        assertFalse(entries.keys.any { it.contains("image3") }) // the missing asset is skipped
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, bytes) ->
            try {
                parse(bytes)
            } catch (e: Exception) {
                throw AssertionError("$name is not well-formed: ${e.message}")
            }
        }
    }

    @Test fun relationshipsStylesAndNumberingResolve() = runTest {
        val entries = writeEntries(sample())
        val w = DocxWriter.NS_W
        val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val doc = parse(entries.getValue("word/document.xml"))
        val styles = parse(entries.getValue("word/styles.xml"))
        val numbering = parse(entries.getValue("word/numbering.xml"))
        val rels = parse(entries.getValue("word/_rels/document.xml.rels"))

        val styleIds = attrs(styles, w, "style", w, "styleId").toSet()
        val usedStyles = attrs(doc, w, "pStyle", w, "val").toSet() + attrs(doc, w, "rStyle", w, "val")
        assertTrue("styles used but undefined: ${usedStyles - styleIds}", styleIds.containsAll(usedStyles))
        assertTrue(usedStyles.containsAll(listOf("Title", "Heading1", "ListBullet", "ListNumber", "Quote", "Code", "Caption", "Hyperlink")))

        val relIds = attrs(rels, "http://schemas.openxmlformats.org/package/2006/relationships", "Relationship", null, "Id").toSet()
        val embeds = attrs(doc, "http://schemas.openxmlformats.org/drawingml/2006/main", "blip", r, "embed")
        val links = attrs(doc, w, "hyperlink", r, "id")
        assertEquals(2, embeds.size)
        assertEquals(1, links.size)
        assertTrue(relIds.containsAll(embeds + links))

        val numIds = attrs(numbering, w, "num", w, "numId").toSet()
        val usedNums = attrs(doc, w, "numId", w, "val").toSet()
        assertTrue(numIds.containsAll(usedNums))
        // Bullets plus two separate numbered lists, each restarting at 1.
        assertEquals(3, usedNums.size)

        val text = String(entries.getValue("word/document.xml"), Charsets.UTF_8)
        assertTrue(text.contains("Supply &amp; demand &lt;basics&gt;"))
        assertTrue(text.contains("P&amp;L"))
        assertTrue(text.contains("<w:tab/>"))
        assertTrue(text.contains("<w:br/>"))
        assertTrue(text.contains("[00:05] "))
        assertFalse(text.contains("\u0001"))
        val relsText = String(entries.getValue("word/_rels/document.xml.rels"), Charsets.UTF_8)
        assertTrue(relsText.contains("https://example.com/a?b=1&amp;c=2"))
        assertTrue(relsText.contains("TargetMode=\"External\""))
    }

    @Test fun unsafeLinksStayPlainText() = runTest {
        val doc = ExportDocument("T", listOf(ExportSection(null, listOf(ExportBlock.Paragraph(listOf(InlineRun("x", link = "javascript:alert(1)")))))))
        val entries = writeEntries(doc)
        assertFalse(String(entries.getValue("word/document.xml")).contains("hyperlink"))
        val lists = ExportDocument("T", listOf(ExportSection(null, listOf(ExportBlock.ListItem(ListKind.BULLET, 12, listOf(InlineRun("deep")))))))
        parse(writeEntries(lists).getValue("word/document.xml"))
    }

    @Test fun picturesFitTheTextBlock() {
        val (cx, cy) = DocxWriter.pictureExtent(4000, 2000)
        assertEquals(5_731_510L, cx)
        assertEquals(2_865_755L, cy)
        val (sx, sy) = DocxWriter.pictureExtent(100, 50)
        assertEquals(952_500L, sx)
        assertEquals(476_250L, sy)
        val (tx, ty) = DocxWriter.pictureExtent(100, 100_000)
        assertTrue(ty <= (16838 - 2 * 1440 - 720) * 635L)
        assertTrue(tx in 1..ty)
    }
}
