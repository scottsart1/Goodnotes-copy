package com.inkwell.notes.export

import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.export.format.PdfCanvas
import com.inkwell.notes.export.format.PdfDocResources
import com.inkwell.notes.export.format.PdfFonts
import com.inkwell.notes.export.format.PdfGeometry
import com.inkwell.notes.export.format.PdfImages
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Runs the real PDFBox writer on the JVM for the parts that need no Android graphics. */
class PdfStructureTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("pdf-structure").toFile()
        installIdentityCMap()
    }

    /**
     * On Android PDFBox reads its predefined CMaps from the AAR assets; on the JVM it looks on
     * the classpath. Write the standard Identity-H CMap next to the test classes so Type0
     * fonts load here exactly as they do on the device.
     */
    private fun installIdentityCMap() {
        val root = File(PdfStructureTest::class.java.protectionDomain.codeSource.location.toURI())
        Assume.assumeTrue(root.isDirectory)
        val target = File(root, "com/tom_roush/fontbox/resources/cmap/Identity-H")
        if (target.isFile) return
        target.parentFile.mkdirs()
        val ranges = (0 until 256).joinToString("\n") { hi -> "<%02x00> <%02xff> %d".format(hi, hi, hi * 256) }
        target.writeText(
            """
            %!PS-Adobe-3.0 Resource-CMap
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CIDSystemInfo 3 dict dup begin
            /Registry (Adobe) def
            /Ordering (Identity) def
            /Supplement 0 def
            end def
            /CMapName /Identity-H def
            /CMapVersion 10.001 def
            /CMapType 1 def
            /WMode 0 def
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            256 begincidrange
            """.trimIndent() + "\n" + ranges + "\nendcidrange\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n",
        )
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun font(name: String): File {
        val candidates = listOf(File("src/main/res/font/$name"), File("app/src/main/res/font/$name"))
        return candidates.first { it.isFile }
    }

    @Test fun vectorPaperShapesAndInvisibleTextAreWritten() {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val fonts = PdfFonts(doc, font("figtree_regular.ttf"), font("figtree_bold.ttf"))
            val res = PdfDocResources(doc, { fonts }, PdfImages(doc, { _, a -> File(dir, a) }, 85, 2048))
            val page = PDPage(PDRectangle(595f, 842f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.saveGraphicsState()
                cs.transform(PdfCanvas.matrix(PdfGeometry.pageToUser(595f, 842f)))
                val canvas = PdfCanvas(cs, res, "doc")
                canvas.paper(PaperSpec(PaperStyle.RULED), 595f, 842f)
                canvas.shape(ShapeItem("s", ShapeKind.ROUNDED_RECTANGLE, floatArrayOf(50f, 50f, 200f, 120f), 0xFF2952CC.toInt(), fillColor = 0x802952CC.toInt()))
                canvas.fill(0xFFF2D04B.toInt(), 0.45f, multiply = true) { cs.addRect(10f, 10f, 100f, 20f) }
                canvas.invisibleText("Supply and demand\nsecond line", 60f, 300f, 400f, 340f)
                cs.restoreGraphicsState()
            }
            doc.save(bytes)
            fonts.close()
        }

        PDDocument.load(bytes.toByteArray()).use { doc ->
            val page = doc.getPage(0)
            val content = page.contents.readBytes().toString(Charsets.ISO_8859_1)
            val tokens = content.split(Regex("\\s+")).toSet()
            assertTrue("invisible text uses rendering mode 3", Regex("\\b3\\s+Tr\\b").containsMatchIn(content))
            assertTrue("text is shown", "Tj" in tokens)
            assertTrue("paths are filled", "f" in tokens)
            assertTrue("curves for rounded corners", "c" in tokens)
            assertTrue("shapes are stroked", "S" in tokens)
            val fontNames = page.resources.fontNames.toList()
            assertEquals(1, fontNames.size)
            val font = page.resources.getFont(fontNames[0])
            assertTrue(font is PDType0Font)
            assertTrue("font is embedded", (font as PDType0Font).isEmbedded)
            assertTrue("ToUnicode keeps text searchable", font.cosObject.containsKey(COSName.TO_UNICODE))
            val states = page.resources.extGStateNames.map { page.resources.getExtGState(it) }
            assertTrue(states.any { it.blendMode == BlendMode.MULTIPLY && it.nonStrokingAlphaConstant!! < 0.5f })
            assertTrue(states.any { it.nonStrokingAlphaConstant != null && it.nonStrokingAlphaConstant!! in 0.49f..0.51f }) // 0x80 fill
            // Decode every shown string through the font's ToUnicode map, as a text extractor would.
            val parser = com.tom_roush.pdfbox.pdfparser.PDFStreamParser(page)
            parser.parse()
            val shown = ArrayList<String>()
            var last: Any? = null
            for (token in parser.tokens) {
                if (token is com.tom_roush.pdfbox.contentstream.operator.Operator && token.name == "Tj" && last is com.tom_roush.pdfbox.cos.COSString) {
                    val b = last.bytes
                    shown += (0 until b.size / 2).joinToString("") { i -> font.toUnicode(((b[2 * i].toInt() and 0xFF) shl 8) or (b[2 * i + 1].toInt() and 0xFF)) ?: "?" }
                }
                last = token
            }
            assertEquals(listOf("Supply and demand", "second line"), shown)
        }
    }

    @Test fun coverageFallsBackForMissingGlyphs() {
        PDDocument().use { doc ->
            val fonts = PdfFonts(doc, font("figtree_regular.ttf"), font("figtree_bold.ttf"))
            assertTrue(fonts.covers('A'.code, bold = false))
            assertTrue(fonts.covers('é'.code, bold = true))
            assertTrue(!fonts.covers(0x65E5, bold = false)) // CJK
            assertTrue(fonts.width("Hello", 12f, bold = false) > 10f)
            fonts.close()
        }
    }
}
