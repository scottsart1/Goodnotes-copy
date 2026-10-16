package com.inkwell.notes.core.storage

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Ids reach the file system through [FileStore.safe]; none of them may escape the document root. */
class FileStoreSafetyTest {
    private lateinit var tmp: File
    private lateinit var files: FileStore

    @Before fun setUp() {
        tmp = Files.createTempDirectory("inkwell-safe").toFile()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = File(tmp, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(tmp, "cache").apply { mkdirs() }
        }
        files = FileStore(context)
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun isInside(f: File, dir: File) = f.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())

    @Test fun dotIdsNeverResolveToTheRootOrItsParent() {
        for (id in listOf(".", "..", "...", "....")) {
            val dir = files.docDir(id)
            assertTrue("docDir($id) = $dir", isInside(dir, files.root) && dir.canonicalFile != files.root.canonicalFile)
        }
    }

    @Test fun deletingADocumentWithATraversalIdDoesNotWipeTheAppFiles() {
        val other = files.pageFile("real-doc", "p1").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val outside = File(files.root.parentFile, "datastore/settings.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        files.deleteDocument("..")
        files.deleteDocument(".")
        assertTrue("another document's page was deleted", other.exists())
        assertTrue("files outside docs/ were deleted", outside.exists())
        assertTrue(files.root.exists())
    }

    @Test fun assetAndPageNamesStayInsideTheirDocument() {
        val doc = files.docDir("doc-1")
        for (name in listOf("..", ".", "../../x", "/etc/passwd", "..\\..\\x", "a/b")) {
            val asset = files.assetFile("doc-1", name)
            assertTrue("asset $name -> $asset", isInside(asset, File(doc, "assets")) && asset.canonicalFile != File(doc, "assets").canonicalFile)
            val page = files.pageFile("doc-1", name)
            assertTrue("page $name -> $page", isInside(page, File(doc, "pages")))
            val audio = files.audioDir("doc-1", name)
            assertTrue("audio $name -> $audio", isInside(audio, File(doc, "audio")) && audio.canonicalFile != File(doc, "audio").canonicalFile)
        }
    }

    @Test fun safeKeepsOrdinaryIdsAndNeverReturnsEmpty() {
        assertEquals("3f2a9c1e-0000-4000-8000-000000000000", FileStore.safe("3f2a9c1e-0000-4000-8000-000000000000"))
        assertEquals("pdfpage-1.png", FileStore.safe("pdfpage-1.png"))
        assertEquals("_", FileStore.safe(""))
        assertFalse(FileStore.safe("..").contains(File.separatorChar))
        assertTrue(FileStore.safe("..") != ".." && FileStore.safe(".") != ".")
        assertTrue(FileStore.safe("a/b").none { it == '/' || it == '\\' })
    }
}
