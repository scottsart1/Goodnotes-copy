package com.inkwell.notes.audio.recorder

import android.media.MediaMetadataRetriever
import com.inkwell.notes.audio.logic.RecoveryPlanner
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.storage.FileStore
import java.io.File

/** Where a recording's files live and how to read their durations. All calls do disk I/O. */
internal object AudioFiles {
    const val WAVEFORM = "waveform.bin"
    const val TRANSCRIPT_CHECKPOINT = "transcript.partial.json"

    fun segmentFile(dir: File, index: Int): File = File(dir, RecoveryPlanner.nameOf(index))

    fun waveformFile(dir: File): File = File(dir, WAVEFORM)

    /**
     * The file for a stored segment path. Paths are absolute; when the library has moved
     * (restored backup, new device) the same file name inside the recording's folder is used.
     */
    fun resolve(files: FileStore, docId: String, recordingId: String, path: String): File {
        val f = File(path)
        if (f.isFile) return f
        val local = File(files.audioDir(docId, recordingId), f.name)
        return if (local.isFile) local else f
    }

    fun segments(files: FileStore, rec: Recording): List<File> =
        rec.segmentFiles.map { resolve(files, rec.documentId, rec.id, it) }

    fun waveform(files: FileStore, rec: Recording): File? {
        val stored = rec.waveformPath?.let { File(it) }
        if (stored != null && stored.isFile) return stored
        val local = waveformFile(files.audioDir(rec.documentId, rec.id))
        return if (local.isFile) local else null
    }

    /** Duration of a finished M4A file, or null when it is missing, empty or unreadable. */
    fun durationMs(file: File): Long? {
        if (!file.isFile || file.length() <= 0L) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun writeWaveform(file: File, bytes: ByteArray) {
        FileStore.writeAtomically(file, keepBackup = false) { it.write(bytes) }
    }

    fun readWaveform(file: File?): ByteArray? = runCatching { file?.takeIf { it.isFile }?.readBytes() }.getOrNull()
}
