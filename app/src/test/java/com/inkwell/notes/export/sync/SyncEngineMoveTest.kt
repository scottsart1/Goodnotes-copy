package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A document moved in the library (or below a renamed folder): [SyncEngine.enqueueMoved]. */
class SyncEngineMoveTest {
    private val tmp: File = Files.createTempDirectory("sync-move").toFile()
    private val vault = FakeVault()
    private val drive = File(tmp, "drive").apply { mkdirs() }
    private val d = doc(folderId = "f2")
    private val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ASync"
    private val vaultUrl = "https://yourpitbox.com/notes/Lk7xQ2pLmn0"

    @After
    fun tearDown() {
        vault.close()
        tmp.deleteRecursively()
    }

    private fun harness(prefs: DestinationPrefs) =
        EngineHarness(tmp, prefs, vault, folderRoot = drive).apply {
            source.docs[d.id] = d
            source.folders["f1"] = "Economics" to null
            source.folders["f2"] = "Term 1" to "f1"
            source.markdowns[d.id] = "# Financial Markets\n\nYield curve notes"
            source.recordings["rec-1"] = recording(title = "Lecture 3")
            source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello class", null)), "on-device")
        }

    private val oldDir get() = File(drive, "Inkwell/Economics/Term 1/${d.title}")
    private val newDir get() = File(drive, "Inkwell/Economics/${d.title}")

    private fun EngineHarness.move() {
        source.docs[d.id] = d.copy(folderId = "f1")
    }

    // ------------------------------------------------------------------ folder

    @Test
    fun `a move takes transcripts and audio along when notes do not go to the folder`() = runBlocking {
        val prefs = DestinationPrefs(transcripts = setOf(OutputTarget.FOLDER), audio = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.engine.run()
        assertEquals(listOf("audio_Lecture 3.m4a", "transcript_Lecture 3.md"), oldDir.list()!!.sorted())

        h.move()
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        h.engine.run()

        assertEquals(listOf("audio_Lecture 3.m4a", "transcript_Lecture 3.md"), newDir.list()!!.sorted())
        assertFalse("the old copies are gone", oldDir.exists())
        assertTrue(h.dao.pendingRows().isEmpty())
        assertEquals(0, h.source.pdfExports)
    }

    @Test
    fun `a move with only audio in the folder moves the audio`() = runBlocking {
        val prefs = DestinationPrefs(audio = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.engine.run()

        h.move()
        h.engine.enqueueMoved(d.id, prefs)
        assertEquals(listOf(SyncKind.AUDIO.name to "rec-1"), h.dao.pendingRows().map { it.kind to it.refId })
        h.engine.run()
        assertEquals(listOf("audio_Lecture 3.m4a"), newDir.list()!!.sorted())
        assertFalse(oldDir.exists())
    }

    @Test
    fun `a move skips a first transcript that has no text and moves the copies with one that does`() = runBlocking {
        val prefs = DestinationPrefs(transcripts = setOf(OutputTarget.FOLDER), audio = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        // The first recording was silent: its transcription finished with no segments.
        val lecture = h.source.recordings.getValue("rec-1")
        h.source.recordings.clear()
        h.source.recordings["rec-0"] = recording(id = "rec-0", title = "Silence", startedAt = lecture.startedAt - 100_000)
        h.source.recordings["rec-1"] = lecture
        h.source.transcripts["rec-0"] = StoredTranscript(emptyList(), "on-device")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-0")
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.engine.run()
        val copies = listOf("audio_Lecture 3.m4a", "audio_Silence.m4a", "transcript_Lecture 3.md")
        assertEquals(copies, oldDir.list()!!.sorted())

        h.move()
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        assertEquals(listOf(SyncKind.TRANSCRIPT.name to "rec-1"), h.dao.pendingRows().map { it.kind to it.refId })
        h.engine.run()

        assertEquals(copies, newDir.list()!!.sorted())
        assertFalse("the old copies are gone", oldDir.exists())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `a move copies the notes again when they go to the folder`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.FOLDER), transcripts = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.run()

        h.move()
        h.engine.enqueueMoved(d.id, prefs)
        assertEquals(listOf(SyncKind.NOTES.name to OutputTarget.FOLDER.name), h.dao.pendingRows().map { it.kind to it.target })
        h.engine.run()
        assertEquals(listOf("notes.md", "notes.pdf", "transcript_Lecture 3.md"), newDir.list()!!.sorted())
        assertFalse(oldDir.exists())
    }

    @Test
    fun `nothing is queued for a folder that holds no copies of the document`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.FOLDER), transcripts = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.move()
        assertEquals(0, h.engine.enqueueMoved(d.id, prefs))
        assertTrue(h.dao.all.isEmpty())
    }

    // ------------------------------------------------------------------ vault

    @Test
    fun `a move sends the vault the new folder path without uploading the notes again`() = runBlocking {
        vault.docIds += d.id
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.VAULT), vaultUrl = vaultUrl, notesFormat = NotesExportFormat.PDF_AND_MARKDOWN)
        val h = harness(prefs)
        h.move()
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        val report = h.engine.run()

        assertEquals(listOf("POST /api/session", "GET /api/docs", "PUT /api/docs/${d.id}"), vault.calls())
        val put = FakeVault.jsonOf(vault.requests.last())
        assertEquals("Economics", put["folderPath"]!!.jsonPrimitive.content)
        assertEquals("the upsert keeps the note text", "# Financial Markets\n\nYield curve notes", put["markdown"]!!.jsonPrimitive.content)
        assertEquals(0, h.source.pdfExports)
        assertEquals(1, report.succeeded)
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `with only transcripts in the vault a move leaves the vault's stored notes alone`() = runBlocking {
        // Notes went to the vault before; the upsert would replace their text with nothing.
        vault.docIds += d.id
        val prefs = DestinationPrefs(transcripts = setOf(OutputTarget.VAULT), vaultUrl = vaultUrl)
        val h = harness(prefs)
        h.move()
        assertEquals(0, h.engine.enqueueMoved(d.id, prefs))
        h.engine.run()

        assertTrue(vault.requests.isEmpty())
        assertTrue(h.dao.all.isEmpty())
    }

    @Test
    fun `a move never creates a document the vault does not have`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.VAULT), vaultUrl = vaultUrl)
        val h = harness(prefs)
        h.move()
        h.engine.enqueueMoved(d.id, prefs)
        h.engine.run()
        assertEquals(listOf("POST /api/session", "GET /api/docs"), vault.calls())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `with both destinations only the folder gets the notes again`() = runBlocking {
        val prefs = DestinationPrefs(
            notes = setOf(OutputTarget.VAULT, OutputTarget.FOLDER), vaultUrl = vaultUrl, folderUri = folderUri,
        )
        val h = harness(prefs)
        h.state.setFolderLocation(folderUri, d.id, listOf("Inkwell", "Economics", "Term 1", d.title))
        h.move()
        h.engine.enqueueMoved(d.id, prefs)
        assertEquals(
            setOf(
                Triple(SyncKind.NOTES.name, OutputTarget.FOLDER.name, null),
                Triple(SyncKind.NOTES.name, OutputTarget.VAULT.name, SyncEngine.VAULT_METADATA_REF),
            ),
            h.dao.pendingRows().map { Triple(it.kind, it.target, it.refId) }.toSet(),
        )
    }

    @Test
    fun `notes copied to the vault after the move make the metadata update unnecessary`() = runBlocking {
        vault.docIds += d.id
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.VAULT), vaultUrl = vaultUrl, notesFormat = NotesExportFormat.PDF)
        val h = harness(prefs)
        h.move()
        // A rename: the edit path queues the notes, the move path the metadata.
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueueMoved(d.id, prefs)
        h.engine.run()
        assertEquals(listOf("POST /api/session", "PUT /api/docs/${d.id}", "PUT /api/docs/${d.id}/files/notes.pdf"), vault.calls())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `a metadata update is dropped once notes no longer go to the vault`() = runBlocking {
        vault.docIds += d.id
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.VAULT), transcripts = setOf(OutputTarget.VAULT), vaultUrl = vaultUrl)
        val h = harness(prefs)
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        // Transcripts still go to the vault: a metadata-only upsert would erase its stored notes.
        h.prefs = prefs.copy(notes = emptySet())
        h.engine.run()
        assertTrue(vault.requests.isEmpty())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `metadata updates go to the vault while notes do`() {
        val none = DestinationPrefs()
        assertEquals(emptySet<OutputTarget>(), none.vaultMetadataTargets())
        assertEquals(setOf(OutputTarget.VAULT), none.copy(notes = setOf(OutputTarget.VAULT)).vaultMetadataTargets())
        assertEquals(emptySet<OutputTarget>(), none.copy(audio = setOf(OutputTarget.VAULT)).vaultMetadataTargets())
        assertEquals(emptySet<OutputTarget>(), none.copy(notes = setOf(OutputTarget.FOLDER)).vaultMetadataTargets())
        assertEquals(
            setOf(OutputTarget.VAULT),
            SyncEngine.targetsFor(SyncKind.NOTES, SyncEngine.VAULT_METADATA_REF, none.copy(notes = setOf(OutputTarget.VAULT))),
        )
        assertEquals(
            emptySet<OutputTarget>(),
            SyncEngine.targetsFor(SyncKind.NOTES, SyncEngine.VAULT_METADATA_REF, none.copy(transcripts = setOf(OutputTarget.VAULT))),
        )
        assertEquals(emptySet<OutputTarget>(), SyncEngine.targetsFor(SyncKind.NOTES, null, none.copy(transcripts = setOf(OutputTarget.VAULT))))
    }
}
