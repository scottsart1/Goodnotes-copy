package com.inkwell.notes.speakers.voices

import android.content.Context
import android.util.Log
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.speakers.logic.ReferenceClip
import com.inkwell.notes.speakers.logic.ReferenceClipCodec
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.Voiceprint
import com.inkwell.notes.speakers.logic.VoiceprintCodec
import java.io.File

/**
 * Voice data on disk: biometric, so it lives only here, AES-GCM encrypted with a Keystore key
 * ([VoiceCrypto]), under noBackupFilesDir (outside every backup, export, sync and the
 * FileProvider), never in Room and never in logs:
 *
 * voices/<personId>.vp       a person's voiceprint samples
 * voices/<personId>.ref      a person's reference clip (16 kHz WAV, 2-10 s)
 * voices/rec/<recordingId>.vp  centroids of a recording's voices, for enrolling them when named
 * voices/work/<recordingId>.vp window embeddings of an identification in progress
 *
 * Files that cannot be decrypted (the Keystore key was lost) are dropped quietly; prints of
 * another embedding model are dropped as stale. All calls do disk I/O.
 */
internal class VoiceprintStore(context: Context) {
    val root: File = File(context.noBackupFilesDir, "voices")
    private val crypto = VoiceCrypto()

    sealed interface Read<out T> {
        data class Found<T>(val value: T) : Read<T>
        data object Missing : Read<Nothing>

        /** The file existed but could not be read and was deleted. */
        data object Dropped : Read<Nothing>

        /** The Keystore is temporarily unavailable; the file is kept. */
        data object Unavailable : Read<Nothing>
    }

    // ------------------------------------------------------------------ people

    fun readPrint(personId: String): Read<Voiceprint> = readVoiceprint(personFile(personId, PRINT), "p/${FileStore.safe(personId)}")

    fun writePrint(personId: String, print: Voiceprint) {
        if (print.vectors.isEmpty()) {
            personFile(personId, PRINT).delete()
            return
        }
        write(personFile(personId, PRINT), "p/${FileStore.safe(personId)}", VoiceprintCodec.encode(print))
    }

    fun readReference(personId: String): Read<ReferenceClip> {
        val f = personFile(personId, REF)
        return when (val r = readBlob(f, "r/${FileStore.safe(personId)}")) {
            is Read.Found -> ReferenceClipCodec.decode(r.value)?.let { Read.Found(it) } ?: drop(f)
            is Read.Missing -> Read.Missing
            is Read.Dropped -> Read.Dropped
            is Read.Unavailable -> Read.Unavailable
        }
    }

    fun writeReference(personId: String, clip: ReferenceClip) =
        write(personFile(personId, REF), "r/${FileStore.safe(personId)}", ReferenceClipCodec.encode(clip))

    fun hasReference(personId: String): Boolean = personFile(personId, REF).isFile

    fun deleteReference(personId: String) {
        personFile(personId, REF).delete()
    }

    fun hasPrint(personId: String): Boolean = personFile(personId, PRINT).isFile

    fun deletePerson(personId: String) {
        personFile(personId, PRINT).delete()
        personFile(personId, REF).delete()
    }

    // ------------------------------------------------------------------ recordings

    fun readRecording(recordingId: String): Read<Voiceprint> = readVoiceprint(recFile(REC_DIR, recordingId), "rec/${FileStore.safe(recordingId)}")

    fun writeRecording(recordingId: String, print: Voiceprint) =
        write(recFile(REC_DIR, recordingId), "rec/${FileStore.safe(recordingId)}", VoiceprintCodec.encode(print))

    fun deleteRecording(recordingId: String) {
        recFile(REC_DIR, recordingId).delete()
    }

    fun readWork(recordingId: String): Read<Voiceprint> = readVoiceprint(recFile(WORK_DIR, recordingId), "work/${FileStore.safe(recordingId)}")

    fun writeWork(recordingId: String, print: Voiceprint) =
        write(recFile(WORK_DIR, recordingId), "work/${FileStore.safe(recordingId)}", VoiceprintCodec.encode(print))

    fun deleteWork(recordingId: String) {
        recFile(WORK_DIR, recordingId).delete()
    }

    /** Recording ids with per-recording voice data (for removing data of deleted recordings). */
    fun recordingIds(): Set<String> =
        listOf(REC_DIR, WORK_DIR).flatMap { d -> File(root, d).listFiles()?.map { it.name.removeSuffix(PRINT) }.orEmpty() }.toSet()

    // ------------------------------------------------------------------ everything

    /** Deletes every voice file and the key, so nothing sealed before can ever be read again. */
    @Synchronized
    fun wipe() {
        root.deleteRecursively()
        crypto.rotate()
    }

    // ------------------------------------------------------------------ internals

    private fun personFile(personId: String, ext: String) = File(root, FileStore.safe(personId) + ext)

    private fun recFile(dir: String, recordingId: String) = File(File(root, dir), FileStore.safe(recordingId) + PRINT)

    private fun readVoiceprint(f: File, aad: String): Read<Voiceprint> = when (val r = readBlob(f, aad)) {
        is Read.Found -> {
            val print = VoiceprintCodec.decode(r.value)
            when {
                print == null -> drop(f)
                print.modelId != SpeakerModelSpecs.EMBEDDING_MODEL_ID -> drop(f) // another model: not comparable
                else -> Read.Found(print)
            }
        }
        is Read.Missing -> Read.Missing
        is Read.Dropped -> Read.Dropped
        is Read.Unavailable -> Read.Unavailable
    }

    @Synchronized
    private fun readBlob(f: File, aad: String): Read<ByteArray> {
        if (!f.isFile) return Read.Missing
        val blob = try {
            f.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Voice file unreadable: ${e.javaClass.simpleName}")
            return Read.Unavailable
        }
        return when (val o = crypto.open(blob, aad(aad))) {
            is VoiceCrypto.Opened.Ok -> Read.Found(o.bytes)
            VoiceCrypto.Opened.Unreadable -> drop(f)
            VoiceCrypto.Opened.Unavailable -> Read.Unavailable
        }
    }

    @Synchronized
    private fun write(f: File, aad: String, plain: ByteArray) {
        val sealed = crypto.seal(plain, aad(aad))
        FileStore.writeAtomically(f, keepBackup = false) { it.write(sealed) }
    }

    private fun drop(f: File): Read<Nothing> {
        // Never log names or ids of people with voice data; the file name is an opaque id.
        Log.w(TAG, "Dropping an unreadable voice file")
        f.delete()
        return Read.Dropped
    }

    private fun aad(name: String) = "inkwell-voices:v1:$name".toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAG = "VoiceprintStore"
        private const val PRINT = ".vp"
        private const val REF = ".ref"
        private const val REC_DIR = "rec"
        private const val WORK_DIR = "work"
    }
}

/** The value read, or null when missing, dropped or unavailable. */
internal fun <T> VoiceprintStore.Read<T>.valueOrNull(): T? = (this as? VoiceprintStore.Read.Found<T>)?.value
