package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.db.RecordingEntity
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Deleting a document must reach the speaker service for every recording it held: the voice
 * centroids and work files it keeps outside the database (encrypted, under noBackupFilesDir)
 * are biometric data that a Room cascade cannot remove, and a queued identification job or a
 * "voices to name" notification for a recording that no longer exists must not stay behind.
 */
class LibraryRepositoryVoiceDataTest {
    private lateinit var h: RepositoryTestHarness
    private val repo get() = h.repo

    @Before fun setUp() { h = RepositoryTestHarness() }
    @After fun tearDown() { h.close() }

    private suspend fun recording(docId: String, id: String, startedAt: Long = 1L): RecordingEntity {
        val dir = h.files.audioDir(docId, id)
        File(dir, "seg_000.m4a").writeBytes(byteArrayOf(1, 2, 3))
        val e = RecordingEntity(
            id = id, documentId = docId, title = "Meeting", segmentFiles = File(dir, "seg_000.m4a").absolutePath,
            segmentDurations = "1000", durationMs = 1000, startedAt = startedAt, endedAt = startedAt + 1000,
            status = RecordingStatus.COMPLETE, transcriptStatus = TranscriptStatus.DONE, waveformPath = null,
        )
        h.db.recordings().upsert(e)
        h.db.transcripts().insertAll(listOf(TranscriptSegmentEntity(recordingId = id, startMs = 0, endMs = 900, text = "hello", speaker = "Priya", source = "t", speakerLabel = "S1")))
        return e
    }

    @Test fun deletePermanentlyTellsTheSpeakerServiceAboutEveryRecording() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        recording(d.id, "rec-a", startedAt = 1)
        recording(d.id, "rec-b", startedAt = 2)
        val other = repo.createDocument(DocKind.NOTEBOOK)
        recording(other.id, "rec-other")

        repo.deletePermanently(d.id)

        assertEquals(listOf("rec-a", "rec-b"), h.hooks.recordingsDeleted.sorted())
        // Rows cascaded and the audio folder is gone; the other document's recording is untouched.
        assertNull(h.db.recordings().get("rec-a"))
        assertTrue(h.db.transcripts().forRecording("rec-b").isEmpty())
        assertTrue(h.db.recordings().forDocument(other.id).map { it.id } == listOf("rec-other"))
        assertEquals(listOf(d.id), h.hooks.deleted)
    }

    @Test fun documentsWithoutRecordingsDoNotCallTheSpeakerHook() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        repo.deletePermanently(d.id)
        repo.deletePermanently("ghost")
        assertTrue(h.hooks.recordingsDeleted.isEmpty())
    }

    @Test fun emptyingTheTrashReachesTheSpeakerServiceForEachTrashedDocument() = runTest {
        val a = repo.createDocument(DocKind.NOTEBOOK)
        val b = repo.createDocument(DocKind.WHITEBOARD)
        val kept = repo.createDocument(DocKind.NOTEBOOK)
        recording(a.id, "rec-a")
        recording(b.id, "rec-b")
        recording(kept.id, "rec-kept")
        repo.trashDocument(a.id)
        repo.trashDocument(b.id)

        repo.emptyTrash()

        assertEquals(listOf("rec-a", "rec-b"), h.hooks.recordingsDeleted.sorted())
    }

    @Test fun purgingExpiredTrashReachesTheSpeakerService() = runTest {
        val old = repo.createDocument(DocKind.NOTEBOOK)
        val recent = repo.createDocument(DocKind.NOTEBOOK)
        recording(old.id, "rec-old")
        recording(recent.id, "rec-recent")
        repo.trashDocument(old.id)
        h.advance(LibraryRepositoryImpl.TRASH_RETENTION_MS + 1)
        repo.trashDocument(recent.id)

        assertEquals(1, repo.purgeExpiredTrash())

        assertEquals(listOf("rec-old"), h.hooks.recordingsDeleted)
    }

    @Test fun trashingAloneKeepsVoiceDataBecauseTheDocumentCanBeRestored() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        recording(d.id, "rec-a")
        repo.trashDocument(d.id)
        repo.restoreDocument(d.id)
        assertTrue(h.hooks.recordingsDeleted.isEmpty())
        assertEquals("rec-a", h.db.recordings().forDocument(d.id).single().id)
    }
}
