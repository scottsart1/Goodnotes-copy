package com.inkwell.notes.speakers.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.inkwell.notes.core.storage.FileStore
import java.io.File
import java.util.concurrent.TimeUnit

/** Queues, cancels and observes on-device identification of recordings (WorkManager). */
internal object Diarizations {
    const val KEY_DOC = "docId"
    const val KEY_RECORDING = "recordingId"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"
    /** Output of a job that stopped to wait for the models. */
    const val KEY_WAITING = "waiting"
    private const val TAG = "inkwell-speakers"

    fun uniqueName(recordingId: String) = "inkwell-speakers-$recordingId"

    /** [replace] restarts a queued, finished or failed job; otherwise an existing one is kept. */
    fun enqueue(context: Context, docId: String, recordingId: String, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<DiarizationWorker>()
            .setInputData(workDataOf(KEY_DOC to docId, KEY_RECORDING to recordingId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(recordingId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context, recordingId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(recordingId))
    }

    fun observe(context: Context, recordingId: String): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueName(recordingId))
            // After a REPLACE the cancelled job can still be listed next to the new one.
            .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() }

    fun progressOf(info: WorkInfo?): Pair<Int, Int>? {
        if (info == null || info.state != WorkInfo.State.RUNNING) return null
        val total = info.progress.getInt(KEY_TOTAL, 0)
        if (total <= 0) return null
        return info.progress.getInt(KEY_DONE, 0) to total
    }

    fun errorOf(info: WorkInfo?): String? =
        if (info?.state == WorkInfo.State.FAILED) info.outputData.getString(KEY_ERROR) else null
}

/**
 * Recordings waiting for the speaker models to download. Persisted (noBackupFilesDir) so the
 * wait survives process death; when the models arrive every waiting recording is queued.
 */
internal class AwaitingModels(context: Context) {
    @Serializable
    data class Entry(val docId: String, val recordingId: String)

    private val file = File(context.noBackupFilesDir, "speakers/awaiting-models.json")
    private val serializer = ListSerializer(Entry.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())
    private val idsFlow = MutableStateFlow(state.value.map { it.recordingId }.toSet())

    /** Recording ids waiting for the models. */
    val ids: StateFlow<Set<String>> = idsFlow.asStateFlow()

    val entries: List<Entry> get() = state.value

    fun add(docId: String, recordingId: String) = update { list -> list.filterNot { it.recordingId == recordingId } + Entry(docId, recordingId) }

    fun remove(recordingId: String) = update { list -> list.filterNot { it.recordingId == recordingId } }

    /** Takes every waiting entry out of the list (they are about to be queued). */
    fun drain(): List<Entry> {
        val all = state.value
        if (all.isNotEmpty()) update { emptyList() }
        return all
    }

    @Synchronized
    private fun update(transform: (List<Entry>) -> List<Entry>) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        idsFlow.value = next.map { it.recordingId }.toSet()
        runCatching {
            if (next.isEmpty()) file.delete()
            else FileStore.writeAtomically(file, keepBackup = false) { it.write(json.encodeToString(serializer, next).toByteArray()) }
        }.onFailure { Log.w("AwaitingModels", "Could not save the waiting list", it) }
    }

    private fun load(): List<Entry> = runCatching {
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())
}
