package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TranscriptStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SyncEngineTest {
    private val tmp: File = Files.createTempDirectory("sync-engine").toFile()
    private val vault = FakeVault()
    private val drive = File(tmp, "drive").apply { mkdirs() }
    private val d = doc(folderId = "f2")

    private val vaultPrefs = DestinationPrefs(
        notes = setOf(OutputTarget.VAULT),
        vaultUrl = "https://yourpitbox.com/notes/Lk7xQ2pLmn0",
        notesFormat = NotesExportFormat.PDF_AND_MARKDOWN,
    )
    private val folderPrefs = DestinationPrefs(
        notes = setOf(OutputTarget.FOLDER),
        folderUri = "content://com.android.externalstorage.documents/tree/primary%3ASync",
        folderLabel = "Sync",
    )

    @After
    fun tearDown() {
        vault.close()
        tmp.deleteRecursively()
    }

    private fun harness(prefs: DestinationPrefs, rename: Boolean = true, creds: MemoryCredentials = MemoryCredentials()) =
        EngineHarness(tmp, prefs, vault, creds, folderRoot = drive, folderRename = rename).apply {
            source.docs[d.id] = d
            source.folders["f1"] = "Economics" to null
            source.folders["f2"] = "Term 1" to "f1"
            source.markdowns[d.id] = "# Financial Markets\n\nYield curve notes"
        }

    private fun docDir(title: String = d.title) = File(drive, "Inkwell/Economics/Term 1/$title")

    // ------------------------------------------------------------------ vault

    @Test
    fun `notes go to the vault as doc, notes pdf and notes md`() = runBlocking {
        val h = harness(vaultPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()

        assertEquals(
            listOf("POST /api/session", "PUT /api/docs/${d.id}", "PUT /api/docs/${d.id}/files/notes.pdf", "PUT /api/docs/${d.id}/files/notes.md"),
            vault.calls(),
        )
        val put = FakeVault.jsonOf(vault.requests[1])
        assertEquals("Economics / Term 1", put["folderPath"]!!.jsonPrimitive.content)
        assertEquals("# Financial Markets\n\nYield curve notes", put["markdown"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", vault.requests[2].getHeader("Content-Type"))
        assertTrue(vault.requests[2].body.readByteArray().contentEquals(FakeSource.pdfBytes(d.id)))
        assertEquals("# Financial Markets\n\nYield curve notes", vault.requests[3].body.readUtf8())

        assertEquals(1, report.succeeded)
        assertNull(report.error)
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
        assertEquals(h.now, h.source.synced[d.id])
        assertEquals(h.now, h.state.lastSuccessAt)
        assertEquals("temporary exports are cleaned up", 0, File(tmp, "exports").list()!!.size)
    }

    @Test
    fun `PDF only format uploads no markdown file but still sends the text for search`() = runBlocking {
        val h = harness(vaultPrefs.copy(notesFormat = NotesExportFormat.PDF))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(listOf("POST /api/session", "PUT /api/docs/${d.id}", "PUT /api/docs/${d.id}/files/notes.pdf"), vault.calls())
        assertTrue(FakeVault.jsonOf(vault.requests[1]).containsKey("markdown"))
    }

    @Test
    fun `markdown only format exports no PDF`() = runBlocking {
        val h = harness(vaultPrefs.copy(notesFormat = NotesExportFormat.MARKDOWN))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(0, h.source.pdfExports)
        assertEquals(listOf("POST /api/session", "PUT /api/docs/${d.id}", "PUT /api/docs/${d.id}/files/notes.md"), vault.calls())
    }

    @Test
    fun `transcripts create a metadata-only doc first when notes stay on the tablet`() = runBlocking {
        val h = harness(DestinationPrefs(transcripts = setOf(OutputTarget.VAULT), vaultUrl = "https://v/notes/x"))
        h.source.recordings["rec-1"] = recording()
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello", null)), "openai:whisper-1")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.VAULT), "rec-1")
        h.engine.run()

        assertEquals(
            listOf("POST /api/session", "GET /api/docs", "PUT /api/docs/${d.id}", "PUT /api/docs/${d.id}/transcripts/rec-1"),
            vault.calls(),
        )
        assertFalse("never sends note text that should stay local", FakeVault.jsonOf(vault.requests[2]).containsKey("markdown"))
        assertEquals("openai:whisper-1", FakeVault.jsonOf(vault.requests[3])["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `transcripts never overwrite a doc the vault already has`() = runBlocking {
        vault.docIds += d.id
        val h = harness(DestinationPrefs(transcripts = setOf(OutputTarget.VAULT), vaultUrl = "https://v/notes/x"))
        h.source.recordings["rec-1"] = recording()
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello", null)), "on-device")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.VAULT), "rec-1")
        h.engine.run()
        assertEquals(listOf("POST /api/session", "GET /api/docs", "PUT /api/docs/${d.id}/transcripts/rec-1"), vault.calls())
    }

    @Test
    fun `audio waits for an unmetered network when asked`() = runBlocking {
        vault.docIds += d.id
        val h = harness(DestinationPrefs(audio = setOf(OutputTarget.VAULT), vaultUrl = "https://v/notes/x", audioOnUnmeteredOnly = true))
        h.source.recordings["rec-1"] = recording()
        h.network.unmetered = false
        h.engine.enqueue(d.id, SyncKind.AUDIO, setOf(OutputTarget.VAULT), "rec-1")

        val first = h.engine.run()
        assertTrue(vault.requests.isEmpty())
        assertEquals(1, first.deferred)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertEquals(SyncNeeds(unmetered = true), syncNeeds(h.dao.pendingRows(), h.prefs))

        h.network.unmetered = true
        h.engine.run()
        assertEquals("PUT /api/docs/${d.id}/files/rec_rec-1.m4a", vault.calls().last())
        assertEquals("audio/mp4", vault.requests.last().getHeader("Content-Type"))
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
    }

    @Test
    fun `audio on metered networks goes when the user allows it`() = runBlocking {
        vault.docIds += d.id
        val h = harness(DestinationPrefs(audio = setOf(OutputTarget.VAULT), vaultUrl = "https://v/notes/x", audioOnUnmeteredOnly = false))
        h.source.recordings["rec-1"] = recording()
        h.network.unmetered = false
        h.engine.enqueue(d.id, SyncKind.AUDIO, setOf(OutputTarget.VAULT), "rec-1")
        h.engine.run()
        assertEquals("PUT /api/docs/${d.id}/files/rec_rec-1.m4a", vault.calls().last())
    }

    @Test
    fun `a recording still in progress is left for later`() = runBlocking {
        val h = harness(DestinationPrefs(audio = setOf(OutputTarget.VAULT), vaultUrl = "https://v/notes/x", audioOnUnmeteredOnly = false))
        h.source.recordings["rec-1"] = recording(status = RecordingStatus.RECORDING)
        h.engine.enqueue(d.id, SyncKind.AUDIO, setOf(OutputTarget.VAULT), "rec-1")
        val report = h.engine.run()
        assertEquals(1, report.deferred)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertEquals(0, h.source.audioExports)
    }

    @Test
    fun `offline the vault waits while the folder copy goes ahead`() = runBlocking {
        val h = harness(vaultPrefs.copy(notes = setOf(OutputTarget.VAULT, OutputTarget.FOLDER), folderUri = folderPrefs.folderUri))
        h.network.online = false
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()

        assertTrue(vault.requests.isEmpty())
        assertTrue(File(docDir(), "notes.pdf").isFile)
        assertEquals(1, report.succeeded)
        assertEquals(1, report.deferred)
        val vaultRow = h.dao.all.single { it.target == OutputTarget.VAULT.name }
        assertEquals(SyncItemStatus.PENDING, vaultRow.status)
        assertEquals("waiting for a network is not a failed attempt", 0, vaultRow.attempts)
    }

    @Test
    fun `wrong password keeps vault items waiting and says so once`() = runBlocking {
        val h = harness(vaultPrefs, creds = MemoryCredentials(pw = "wrong"))
        val other = doc(id = "11111111-2222-3333-4444-555555555555", title = "Other")
        h.source.docs[other.id] = other
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.enqueue(other.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()

        assertEquals("one login attempt, then vault work stops for this run", listOf("POST /api/session"), vault.calls())
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.PENDING })
        assertTrue(report.error!!.contains("password is wrong"))
        assertFalse(report.retryLater)
        assertEquals(report.error, h.state.lastError)
    }

    @Test
    fun `missing link is reported without network traffic`() = runBlocking {
        val noLink = harness(vaultPrefs.copy(vaultUrl = ""))
        noLink.engine.enqueue(d.id, SyncKind.NOTES, noLink.prefs.notes)
        assertTrue(noLink.engine.run().error!!.contains("vault link"))
        assertTrue(vault.requests.isEmpty())
    }

    @Test
    fun `without a password a vault that asks for one stops after one bare request and the item waits`() = runBlocking {
        val noPassword = harness(vaultPrefs, creds = MemoryCredentials(pw = null))
        noPassword.engine.enqueue(d.id, SyncKind.NOTES, noPassword.prefs.notes)
        val report = noPassword.engine.run()
        assertTrue(report.error!!, report.error!!.contains("asks for a password"))
        assertEquals(1, vault.requests.size)
        assertNull(vault.requests.single().getHeader("Authorization"))
        assertEquals(SyncItemStatus.PENDING, noPassword.dao.all.single().status)
    }

    @Test
    fun `a link-only vault syncs without a password`() = runBlocking {
        vault.linkOnly = true
        val h = harness(vaultPrefs, creds = MemoryCredentials(pw = null))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()
        assertNull(report.error, report.error)
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
        assertTrue(vault.calls().none { it == "POST /api/session" })
        assertTrue(vault.requests.all { it.getHeader("Authorization") == null })
    }

    @Test
    fun `lockout blocks the vault until it ends`() = runBlocking {
        vault.lockedSeconds = 300
        val h = harness(vaultPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()
        assertEquals(h.now + 300_000, report.vaultLockedUntil)
        assertTrue(report.retryLater)
        assertEquals(1, vault.requests.size)

        h.engine.run()
        assertEquals("no login attempts while locked", 1, vault.requests.size)

        vault.lockedSeconds = null
        h.now += 301_000
        h.engine.run()
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
    }

    @Test
    fun `server errors retry with a limit and bad requests fail at once`() = runBlocking {
        val h = harness(vaultPrefs.copy(notesFormat = NotesExportFormat.MARKDOWN))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        repeat(SyncEngine.MAX_ATTEMPTS - 1) { i ->
            if (i > 0) h.now += SyncEngine.retryDelayMs(i)
            vault.failNext += FakeVault.json(503, """{"error":"overloaded"}""")
            val report = h.engine.run()
            assertTrue(report.retryLater)
            val row = h.dao.all.single()
            assertEquals(SyncItemStatus.PENDING, row.status)
            assertEquals(i + 1, row.attempts)
            assertTrue(row.lastError!!.contains("HTTP 503"))
        }
        h.now += SyncEngine.retryDelayMs(SyncEngine.MAX_ATTEMPTS - 1)
        vault.failNext += FakeVault.json(503, """{"error":"overloaded"}""")
        h.engine.run()
        assertEquals(SyncItemStatus.FAILED, h.dao.all.single().status)

        val h2 = harness(vaultPrefs)
        h2.engine.enqueue(d.id, SyncKind.NOTES, h2.prefs.notes)
        vault.failNext += FakeVault.json(400, """{"error":"invalid_request","detail":"title is required"}""")
        val report = h2.engine.run()
        assertFalse(report.retryLater)
        assertEquals(1, report.failed)
        assertEquals(SyncItemStatus.FAILED, h2.dao.all.single().status)
    }

    @Test
    fun `runs kicked during an item's retry backoff neither send it nor use up its attempts`() = runBlocking {
        val h = harness(vaultPrefs.copy(notesFormat = NotesExportFormat.MARKDOWN))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        vault.failNext += FakeVault.json(503, """{"error":"overloaded"}""")
        h.engine.run()
        assertEquals(1, h.dao.all.single().attempts)
        val sent = vault.requests.size

        // Edits settling, recordings and settings changes kick runs again and again during the outage.
        repeat(20) {
            h.now += 1_000
            val report = h.engine.run()
            assertTrue("the run comes back for the waiting item", report.retryLater)
            assertEquals(1, report.deferred)
            assertTrue("the outage stays shown while the item waits", h.state.lastError!!.contains("HTTP 503"))
        }
        assertEquals(1, h.dao.all.single().attempts)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertEquals("nothing is sent while the item waits", sent, vault.requests.size)

        // "Sync now" does not wait: the user asked.
        vault.failNext += FakeVault.json(503, """{"error":"overloaded"}""")
        h.engine.run(ignoreBackoff = true)
        assertEquals(2, h.dao.all.single().attempts)
        h.now += SyncEngine.retryDelayMs(2) - 1
        h.engine.run()
        assertEquals("the longer second backoff is waited out too", 2, h.dao.all.single().attempts)

        h.now += 1
        val report = h.engine.run()
        assertNull(report.error, report.error)
        assertNull("the copy went through: the error is cleared", h.state.lastError)
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
    }

    @Test
    fun `retry backoff doubles per attempt and is capped`() {
        assertEquals(30_000L, SyncEngine.retryDelayMs(1))
        assertEquals(60_000L, SyncEngine.retryDelayMs(2))
        assertEquals(30_000L shl 6, SyncEngine.retryDelayMs(SyncEngine.MAX_ATTEMPTS - 1))
        assertEquals(3_600_000L, SyncEngine.retryDelayMs(50))
        assertEquals(30_000L, SyncEngine.retryDelayMs(0))
    }

    @Test
    fun `export failures retry, unimplemented exports fail`() = runBlocking {
        val h = harness(folderPrefs)
        h.source.failExport = IOException("render failed")
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val r1 = h.engine.run()
        assertTrue(r1.retryLater)
        assertEquals(1, h.dao.all.single().attempts)
        assertTrue(r1.error!!.contains(d.title))

        h.source.failExport = NotImplementedError()
        h.now += SyncEngine.retryDelayMs(1)
        val r2 = h.engine.run()
        assertEquals(1, r2.failed)
        assertEquals(SyncItemStatus.FAILED, h.dao.all.single().status)
    }

    @Test
    fun `items for switched-off destinations and missing documents are dropped quietly`() = runBlocking {
        val h = harness(vaultPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.VAULT))
        h.engine.enqueue("gone", SyncKind.NOTES, setOf(OutputTarget.VAULT))
        h.prefs = h.prefs.copy(notes = emptySet())
        h.engine.run()
        assertTrue(vault.requests.isEmpty())
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.DONE })

        val h2 = harness(vaultPrefs)
        h2.source.docs[d.id] = d.copy(trashedAt = 5)
        h2.engine.enqueue(d.id, SyncKind.NOTES, h2.prefs.notes)
        h2.engine.enqueue("gone", SyncKind.NOTES, h2.prefs.notes)
        h2.engine.run()
        assertTrue(vault.requests.isEmpty())
        assertTrue(h2.dao.all.all { it.status == SyncItemStatus.DONE })
        assertTrue(h2.source.synced.isEmpty())
    }

    // ------------------------------------------------------------------ folder

    @Test
    fun `notes go to Inkwell, folder path, title in the chosen folder`() = runBlocking {
        val h = harness(folderPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(listOf("notes.md", "notes.pdf"), docDir().list()!!.sorted())
        assertTrue(File(docDir(), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(d.id)))
        assertEquals("# Financial Markets\n\nYield curve notes", File(docDir(), "notes.md").readText())

        h.source.markdowns[d.id] = "# v2"
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals("updates overwrite in place", listOf("notes.md", "notes.pdf"), docDir().list()!!.sorted())
        assertEquals("# v2", File(docDir(), "notes.md").readText())
        assertTrue(vault.requests.isEmpty())
    }

    @Test
    fun `transcripts and audio land next to the notes`() = runBlocking {
        val h = harness(DestinationPrefs(transcripts = setOf(OutputTarget.FOLDER), audio = setOf(OutputTarget.FOLDER), folderUri = folderPrefs.folderUri))
        h.source.recordings["rec-1"] = recording(title = "Lecture 3")
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello class", null)), "on-device")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, h.prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, h.prefs.audio, "rec-1")
        h.engine.run()
        assertEquals(listOf("audio_Lecture 3.m4a", "transcript_Lecture 3.md"), docDir().list()!!.sorted())
        assertTrue(File(docDir(), "transcript_Lecture 3.md").readText().contains("Hello class"))
        assertTrue(File(docDir(), "audio_Lecture 3.m4a").readBytes().contentEquals(FakeSource.audioBytes("rec-1")))
    }

    @Test
    fun `both destinations share one PDF export`() = runBlocking {
        val h = harness(vaultPrefs.copy(notes = setOf(OutputTarget.VAULT, OutputTarget.FOLDER), folderUri = folderPrefs.folderUri))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(1, h.source.pdfExports)
        assertTrue(File(docDir(), "notes.pdf").isFile)
        assertEquals("PUT /api/docs/${d.id}/files/notes.md", vault.calls().last())
    }

    @Test
    fun `a renamed document renames its folder copy`() = runBlocking {
        val h = harness(folderPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        File(docDir(), "my own file.txt").writeText("keep me")

        h.source.docs[d.id] = d.copy(title = "20261015_Bond Pricing")
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertFalse(docDir().exists())
        assertEquals(listOf("my own file.txt", "notes.md", "notes.pdf"), docDir("20261015_Bond Pricing").list()!!.sorted())
    }

    @Test
    fun `without rename support a moved document is rewritten and old copies removed`() = runBlocking {
        val prefs = folderPrefs.copy(transcripts = setOf(OutputTarget.FOLDER))
        val h = harness(prefs, rename = false)
        h.source.recordings["rec-1"] = recording(title = "Lecture 3", transcriptStatus = TranscriptStatus.DONE)
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hi", null)), "on-device")
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.run()
        assertEquals(listOf("notes.md", "notes.pdf", "transcript_Lecture 3.md"), docDir().list()!!.sorted())

        h.source.docs[d.id] = d.copy(folderId = "f1", title = "Moved")
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.run()
        val moved = File(drive, "Inkwell/Economics/Moved")
        assertEquals(listOf("notes.md", "notes.pdf", "transcript_Lecture 3.md"), moved.list()!!.sorted())
        assertFalse("old folder removed once empty", docDir().exists())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `a revoked folder clears the destination and fails its items`() = runBlocking {
        val h = harness(folderPrefs.copy(transcripts = setOf(OutputTarget.FOLDER)))
        h.folderAccess = FolderAccess { throw FolderRevokedException() }
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.FOLDER))
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.FOLDER), "rec-x")
        h.source.recordings["rec-x"] = recording(id = "rec-x")
        h.source.transcripts["rec-x"] = StoredTranscript(listOf(TranscriptSegment(0, 1, "x", null)), "s")
        val report = h.engine.run()

        assertEquals(1, h.revoked)
        assertNull(h.prefs.folderUri)
        assertFalse(OutputTarget.FOLDER in h.prefs.notes)
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.FAILED || it.status == SyncItemStatus.DONE })
        assertTrue(report.error!!.contains("Choose the folder again"))
    }

    @Test
    fun `an unavailable folder is retried later and keeps the destination`() = runBlocking {
        val h = harness(folderPrefs)
        h.folderAccess = FolderAccess { throw FolderUnavailableException() }
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()
        assertTrue(report.retryLater)
        assertEquals(0, h.revoked)
        assertNotNull(h.prefs.folderUri)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun `enqueue keeps one open item per document, kind, target and recording`() = runBlocking {
        val h = harness(vaultPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.VAULT, OutputTarget.FOLDER))
        h.now += 1000
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.VAULT))
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.VAULT), "rec-1")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.VAULT), "rec-2")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.VAULT), "rec-1")

        val pending = h.dao.pendingRows()
        assertEquals(4, pending.size)
        assertEquals(1, pending.count { it.kind == "NOTES" && it.target == "VAULT" })
        assertEquals(h.now, pending.single { it.kind == "NOTES" && it.target == "VAULT" }.enqueuedAt)
        assertEquals(1, pending.count { it.kind == "NOTES" && it.target == "FOLDER" })
        assertEquals(setOf("rec-1", "rec-2"), pending.filter { it.kind == "TRANSCRIPT" }.map { it.refId }.toSet())
        assertEquals(1, pending.count { it.refId == "rec-1" })
    }

    @Test
    fun `a new item supersedes a failed one`() = runBlocking {
        val h = harness(vaultPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.VAULT))
        h.dao.failPendingForTarget("VAULT", "boom")
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.VAULT))
        assertEquals(1, h.dao.all.size)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
    }

    @Test
    fun `changed documents are those edited after their last copy`() = runBlocking {
        val h = harness(folderPrefs)
        h.source.docs.clear()
        val never = doc(id = "a", lastSyncedAt = 0)
        val edited = doc(id = "b", modifiedAt = 10_000, lastSyncedAt = 5_000)
        val clean = doc(id = "c", modifiedAt = 10_000, lastSyncedAt = 9_000)
        val trashed = doc(id = "d", lastSyncedAt = 0).copy(trashedAt = 1)
        listOf(never, edited, clean, trashed).forEach { h.source.docs[it.id] = it }
        h.engine.enqueueChangedDocuments()
        assertEquals(setOf("a", "b"), h.dao.pendingRows().map { it.documentId }.toSet())
    }

    @Test
    fun `everything means notes, finished recordings and done transcripts`() = runBlocking {
        val prefs = DestinationPrefs(
            notes = setOf(OutputTarget.FOLDER),
            transcripts = setOf(OutputTarget.VAULT),
            audio = setOf(OutputTarget.FOLDER, OutputTarget.VAULT),
            folderUri = "x", vaultUrl = "https://v/notes/x",
        )
        val h = harness(prefs)
        h.source.recordings["r1"] = recording(id = "r1")
        h.source.recordings["r2"] = recording(id = "r2", status = RecordingStatus.RECORDING, transcriptStatus = TranscriptStatus.NONE)
        h.engine.enqueueEverything()
        val rows = h.dao.pendingRows().map { "${it.kind}:${it.target}:${it.refId}" }.toSet()
        assertEquals(setOf("NOTES:FOLDER:null", "TRANSCRIPT:VAULT:r1", "AUDIO:FOLDER:r1", "AUDIO:VAULT:r1"), rows)

        h.dao.rows.value = emptyList()
        h.engine.enqueueEverything(onlyKind = SyncKind.AUDIO, onlyTarget = OutputTarget.VAULT)
        assertEquals(listOf("AUDIO:VAULT:r1"), h.dao.pendingRows().map { "${it.kind}:${it.target}:${it.refId}" })
    }

    @Test
    fun `scoped runs only take their own items and keep other errors`() = runBlocking {
        vault.docIds += d.id
        val prefs = vaultPrefs.copy(
            notes = setOf(OutputTarget.FOLDER, OutputTarget.VAULT), audio = setOf(OutputTarget.VAULT),
            folderUri = folderPrefs.folderUri, audioOnUnmeteredOnly = true,
        )
        val h = harness(prefs)
        h.source.recordings["rec-1"] = recording()
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.state.lastError = "The vault password is wrong."

        h.engine.run(SyncScope.FOLDER)
        assertTrue(File(docDir(), "notes.pdf").isFile)
        assertTrue(vault.requests.isEmpty())
        assertEquals("a folder-only run can't clear a vault problem", "The vault password is wrong.", h.state.lastError)

        h.engine.run(SyncScope.VAULT_AUDIO)
        assertEquals(listOf("POST /api/session", "GET /api/docs", "PUT /api/docs/${d.id}/files/rec_rec-1.m4a"), vault.calls())
        assertEquals(1, h.dao.pendingRows().size)
        assertEquals("VAULT", h.dao.pendingRows().single().target)

        h.engine.run(SyncScope.ALL)
        assertTrue(h.dao.pendingRows().isEmpty())
        assertNull(h.state.lastError)
    }

    @Test
    fun `background needs follow targets and the Wi-Fi rule`() {
        fun row(kind: SyncKind, target: OutputTarget) = com.inkwell.notes.core.storage.db.SyncQueueEntity(
            documentId = "d", kind = kind.name, target = target.name, refId = null, enqueuedAt = 0, attempts = 0, lastError = null, status = "PENDING",
        )
        val wifiOnly = DestinationPrefs(audioOnUnmeteredOnly = true)
        assertEquals(SyncNeeds(local = true), syncNeeds(listOf(row(SyncKind.NOTES, OutputTarget.FOLDER)), wifiOnly))
        assertEquals(SyncNeeds(network = true), syncNeeds(listOf(row(SyncKind.NOTES, OutputTarget.VAULT)), wifiOnly))
        assertEquals(SyncNeeds(unmetered = true), syncNeeds(listOf(row(SyncKind.AUDIO, OutputTarget.VAULT)), wifiOnly))
        assertEquals(SyncNeeds(network = true), syncNeeds(listOf(row(SyncKind.AUDIO, OutputTarget.VAULT)), wifiOnly.copy(audioOnUnmeteredOnly = false)))
        assertFalse(syncNeeds(emptyList(), wifiOnly).any)
    }
}
