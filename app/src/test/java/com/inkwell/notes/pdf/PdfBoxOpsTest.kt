package com.inkwell.notes.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Runs the real pdfbox structure operations on the JVM. Page widths encode identity: page i
 * of a test file is (base + i) points wide, so the order of pages in a result is visible.
 */
class PdfBoxOpsTest {
    private lateinit var dir: File
    private lateinit var ops: PdfBoxOps

    @Before fun setUp() {
        dir = Files.createTempDirectory("pdfbox-ops").toFile()
        ops = PdfBoxOps(initialise = {}, tempDir = { dir })
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun pdf(name: String, base: Int, pages: Int): File {
        val f = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { i -> doc.addPage(PDPage(PDRectangle((base + i).toFloat(), 500f))) }
            doc.save(f)
        }
        return f
    }

    private fun widths(file: File): List<Int> = PDDocument.load(file).use { doc ->
        (0 until doc.numberOfPages).map { doc.getPage(it).mediaBox.width.toInt() }
    }

    @Test fun appendKeepsExistingPagesFirstAndReportsTheirCount() {
        val existing = pdf("existing.pdf", 100, 3)
        val incoming = pdf("incoming.pdf", 200, 2)
        val out = File(dir, "merged.pdf")
        val before = ops.append(existing, incoming, out)
        assertEquals(3, before)
        assertEquals(listOf(100, 101, 102, 200, 201), widths(out))
        // The inputs are untouched (non-destructive).
        assertEquals(listOf(100, 101, 102), widths(existing))
    }

    @Test fun appendedPagesMatchTheInsertPlan() {
        val existing = pdf("existing.pdf", 100, 4)
        val incoming = pdf("incoming.pdf", 300, 3)
        val out = File(dir, "merged.pdf")
        val before = ops.append(existing, incoming, out)
        val plan = PdfPageMapping.insertPlan(before, 3, atIndex = 1, pageCount = 4)
        val merged = widths(out)
        assertEquals(listOf(300, 301, 302), plan.pdfIndices.map { merged[it] })
    }

    @Test fun subsetCopiesPagesInTheRequestedOrderAcrossFiles() {
        val a = pdf("a.pdf", 100, 5)
        val b = pdf("b.pdf", 200, 3)
        val out = File(dir, "subset.pdf")
        ops.subset(listOf(a to 4, a to 1, b to 2, a to 0), out)
        assertEquals(listOf(104, 101, 202, 100), widths(out))
    }

    @Test fun subsetFollowsACompaction() {
        val a = pdf("a.pdf", 100, 6)
        val refs = listOf(PdfPageMapping.Ref("a", 5), null, PdfPageMapping.Ref("a", 2), PdfPageMapping.Ref("a", 5))
        val c = PdfPageMapping.compact(refs)
        val out = File(dir, "subset.pdf")
        ops.subset(c.keep.map { a to it.pdfIndex }, out)
        val result = widths(out)
        assertEquals(listOf(105, 102), result)
        // Every page of the new document shows the PDF page its source page showed.
        refs.forEachIndexed { i, ref -> if (ref != null) assertEquals(100 + ref.pdfIndex, result[c.remapped[i]!!]) }
    }

    @Test fun subsetKeepsResourcesInheritedFromThePageTree() {
        val src = File(dir, "inherited.pdf")
        val marker = COSName.getPDFName("InkwellMarker")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(100f, 100f)))
            doc.addPage(PDPage(PDRectangle(101f, 100f)))
            for (i in 0 until doc.numberOfPages) doc.getPage(i).cosObject.removeItem(COSName.RESOURCES)
            val shared = COSDictionary().apply { setItem(marker, COSDictionary()) }
            doc.pages.cosObject.setItem(COSName.RESOURCES, shared)
            doc.save(src)
        }
        val out = File(dir, "subset.pdf")
        ops.subset(listOf(src to 1), out)
        PDDocument.load(out).use { doc ->
            val page = doc.getPage(0)
            assertEquals(101, page.mediaBox.width.toInt())
            assertTrue(page.cosObject.containsKey(COSName.RESOURCES))
            assertTrue(page.resources.cosObject.containsKey(marker))
        }
    }

    /** Page 0 links to page 2 and to page 1. */
    private fun linkedPdf(): File {
        val f = File(dir, "linked.pdf")
        PDDocument().use { doc ->
            repeat(3) { i -> doc.addPage(PDPage(PDRectangle((100 + i).toFloat(), 500f))) }
            fun linkTo(target: PDPage) = PDAnnotationLink().apply {
                rectangle = PDRectangle(0f, 0f, 10f, 10f)
                destination = PDPageFitDestination().apply { page = target }
            }
            doc.getPage(0).annotations = listOf(linkTo(doc.getPage(2)), linkTo(doc.getPage(1)))
            doc.save(f)
        }
        return f
    }

    private fun linkTargets(file: File): List<Int?> = PDDocument.load(file).use { doc ->
        doc.getPage(0).annotations.map { a ->
            val target = ((a as PDAnnotationLink).destination as PDPageDestination).page
            target?.let { t -> (0 until doc.numberOfPages).firstOrNull { doc.getPage(it).cosObject === t.cosObject } }
        }
    }

    @Test fun linksBetweenCopiedPagesPointAtTheCopies() {
        val out = File(dir, "subset.pdf")
        val src = linkedPdf()
        ops.subset(listOf(src to 0, src to 2), out)
        // The link to old page 2 now targets new page 1; the link to old page 1 (not copied) is cut.
        assertEquals(listOf(1, null), linkTargets(out))
        assertEquals(listOf(100, 102), widths(out))
    }

    @Test fun subsetRejectsPagesOutOfRange() {
        val a = pdf("a.pdf", 100, 2)
        val out = File(dir, "subset.pdf")
        try {
            ops.subset(listOf(a to 2), out)
            throw AssertionError("expected failure")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test fun rewriteRemovesOwnerPasswordProtection() {
        val src = File(dir, "protected.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(123f, 456f)))
            val policy = StandardProtectionPolicy("owner-secret", "", AccessPermission().apply { setCanExtractContent(false) })
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(src)
        }
        PDDocument.load(src, "").use { assertTrue(it.isEncrypted) }
        val out = File(dir, "open.pdf")
        ops.rewrite(src, out)
        PDDocument.load(out).use { doc ->
            assertFalse(doc.isEncrypted)
            assertEquals(123, doc.getPage(0).mediaBox.width.toInt())
        }
    }

    @Test fun rewriteFailsForUserPasswords() {
        val src = File(dir, "locked.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            val policy = StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(src)
        }
        try {
            ops.rewrite(src, File(dir, "out.pdf"))
            throw AssertionError("expected a password failure")
        } catch (expected: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            // expected
        }
    }

    @Test fun sniffingAcceptsWhatPdfboxWrites() {
        assertTrue(PdfSniffer.looksLikePdf(pdf("x.pdf", 100, 1)))
    }
}
