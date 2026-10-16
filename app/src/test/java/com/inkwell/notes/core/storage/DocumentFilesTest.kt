package com.inkwell.notes.core.storage

import android.content.ContextWrapper
import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocumentFilesTest {
    private lateinit var tmp: File
    private lateinit var files: FileStore
    private lateinit var pages: PageContentStore
    private lateinit var docFiles: DocumentFiles

    @Before fun setUp() {
        tmp = Files.createTempDirectory("inkwell-files").toFile()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = File(tmp, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(tmp, "cache").apply { mkdirs() }
        }
        files = FileStore(context)
        pages = PageContentStore(files)
        docFiles = DocumentFiles(files, pages)
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private val anchor = AudioAnchor("rec-1", 5_000)

    private fun content() = PageContent(
        strokes = listOf(Stroke("s1", PenType.FOUNTAIN, 0xFF000000.toInt(), 1.5f, floatArrayOf(1f, 2f, 0.5f, 0f, 0f), 10, audio = anchor)),
        images = listOf(ImageItem("i1", "photo.png", 0f, 0f, 100f, 80f)),
    )

    @Test fun copyAcrossDocumentsStripsAudioAndCopiesAssets() = runTest {
        pages.save("docA", "p1", content())
        files.assetFile("docA", "photo.png").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(docFiles.copyPageContent("docA", "p1", "docB", "p9", stripAudio = true, copyAssets = true))

        val copied = pages.load("docB", "p9")
        assertNull(copied.strokes.single().audio)
        assertTrue(copied.strokes.single().points.contentEquals(floatArrayOf(1f, 2f, 0.5f, 0f, 0f)))
        assertTrue(files.assetFile("docB", "photo.png").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test fun copyWithinADocumentKeepsAudioLinks() = runTest {
        pages.save("docA", "p1", content())
        docFiles.copyPageContent("docA", "p1", "docA", "p2", stripAudio = false, copyAssets = false)
        assertEquals(anchor, pages.load("docA", "p2").strokes.single().audio)
    }

    @Test fun pagesWithoutContentAreNotWritten() = runTest {
        assertFalse(docFiles.copyPageContent("docA", "missing", "docB", "p1", stripAudio = true, copyAssets = true))
        assertFalse(files.pageFile("docB", "p1").exists())
    }

    @Test fun flattenedPdfBackgroundGoesBelowTheInk() = runTest {
        pages.save("docA", "p1", content())
        val bg = ImageItem("bg", "pdfpage-1.png", 0f, 0f, 595f, 842f)
        docFiles.copyPageContent("docA", "p1", "docB", "p1", stripAudio = true, copyAssets = true, background = bg)
        val images = pages.load("docB", "p1").images
        assertEquals(listOf("bg", "i1"), images.map { it.id })
        assertEquals(-1, images.first().z)
    }

    @Test fun duplicateCopiesEverythingButRecordings() = runTest {
        pages.save("src", "p1", content())
        pages.save("src", "p2", PageContent())
        files.assetFile("src", "photo.png").writeBytes(byteArrayOf(9))
        files.sourcePdf("src").writeText("%PDF-1.7 fake")
        files.typedFile("src").writeText("{\"blocks\":[]}")
        files.thumbnailFile("src").writeBytes(byteArrayOf(7))
        files.pageThumbnailFile("src", "p1").writeBytes(byteArrayOf(8))
        File(files.audioDir("src", "rec-1"), "seg_000.m4a").writeBytes(byteArrayOf(1, 1))

        val result = docFiles.copyForDuplicate("src", "dst", mapOf("p1" to "n1", "p2" to "n2"))

        assertNull(pages.load("dst", "n1").strokes.single().audio)
        assertTrue(files.pageFile("dst", "n2").exists())
        assertTrue(files.assetFile("dst", "photo.png").exists())
        assertEquals("%PDF-1.7 fake", files.sourcePdf("dst").readText())
        assertTrue(files.typedFile("dst").exists())
        assertEquals(files.thumbnailFile("dst").absolutePath, result.coverPath)
        assertNotNull(result.pageThumbnails["n1"])
        assertNull(result.pageThumbnails["n2"])
        assertFalse(File(files.docDir("dst"), "audio").exists())
    }

    @Test fun identicalPdfsAreRecognised() = runTest {
        files.sourcePdf("a").writeText("%PDF same")
        files.sourcePdf("b").writeText("%PDF same")
        files.sourcePdf("c").writeText("%PDF diff")
        assertTrue(docFiles.sameSourcePdf("a", "b"))
        assertFalse(docFiles.sameSourcePdf("a", "c"))
        assertFalse(docFiles.sameSourcePdf("a", "none"))
        assertTrue(docFiles.copySourcePdf("a", "d"))
        assertFalse("never overwrites an existing PDF", docFiles.copySourcePdf("c", "d"))
        assertTrue(docFiles.sameSourcePdf("a", "d"))
    }

    @Test fun deletionJournalSurvivesUntilCleared() = runTest {
        docFiles.markPendingDelete("doc-1")
        docFiles.markPendingDelete("doc-2")
        assertEquals(setOf("doc-1", "doc-2"), docFiles.pendingDeletes())
        // A new instance (process restart) still sees the journal.
        assertEquals(setOf("doc-1", "doc-2"), DocumentFiles(files, pages).pendingDeletes())
        docFiles.clearPendingDelete("doc-1")
        docFiles.clearPendingDelete("doc-2")
        assertTrue(docFiles.pendingDeletes().isEmpty())
    }

    @Test fun deletingAPageRemovesContentBackupAndThumbnail() = runTest {
        pages.save("d", "p", content())
        pages.save("d", "p", PageContent()) // creates the .bak
        files.pageThumbnailFile("d", "p").writeBytes(byteArrayOf(1))
        docFiles.deletePageFiles("d", "p")
        assertFalse(files.pageFile("d", "p").exists())
        assertFalse(FileStore.backupOf(files.pageFile("d", "p")).exists())
        assertFalse(files.pageThumbnailFile("d", "p").exists())
    }

    @Test fun scanBreaksDownSizes() = runTest {
        File(files.audioDir("d1", "r"), "seg_000.m4a").writeBytes(ByteArray(100))
        files.sourcePdf("d1").writeBytes(ByteArray(50))
        files.pageFile("d2", "p").writeBytes(ByteArray(30))
        val scan = docFiles.scan()
        assertEquals(100L, scan.audioBytes)
        assertEquals(50L, scan.pdfBytes)
        assertEquals(30L, scan.inkBytes)
        assertEquals(180L, scan.totalBytes)
        assertEquals(150L, scan.perDocument["d1"])
    }
}
