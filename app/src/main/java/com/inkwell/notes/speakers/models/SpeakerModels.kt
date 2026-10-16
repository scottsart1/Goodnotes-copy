package com.inkwell.notes.speakers.models

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.inkwell.notes.speakers.SpeakerModelState
import com.inkwell.notes.speakers.logic.ModelFiles
import com.inkwell.notes.speakers.logic.ModelSpec
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.SpeakerPhases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The on-device speaker models: where they live (noBackupFilesDir/models/speakers, so they
 * are never backed up), whether they are ready, and their one-time download
 * ([ModelDownloadWorker], unique work [UNIQUE_WORK]).
 */
internal class SpeakerModels(private val context: Context, scope: CoroutineScope) {
    val dir: File = File(context.noBackupFilesDir, "models/speakers")

    private val diskChanged = MutableStateFlow(0)

    @Volatile private var verifiedThisProcess = false

    private val job = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK)
        .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() }

    /** Kept in sync with the files on disk and the download job. */
    val state: StateFlow<SpeakerModelState> = combine(job, diskChanged) { info, _ -> stateOf(info) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, if (present()) SpeakerModelState.Ready else SpeakerModelState.NotDownloaded)

    fun present(): Boolean = ModelFiles.present(dir)

    fun file(spec: ModelSpec): File = ModelFiles.target(dir, spec)

    /** Queues the download (kept if one is already queued or running). */
    fun enqueue(unmeteredOnly: Boolean) {
        val network = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Cancels any download and deletes the models and partial files. */
    suspend fun delete() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        withContext(Dispatchers.IO) {
            SpeakerModelSpecs.ALL.forEach {
                ModelFiles.target(dir, it).delete()
                ModelFiles.part(dir, it).delete()
            }
        }
        verifiedThisProcess = false
        changed()
    }

    /** The files changed outside the job's knowledge (download finished, models deleted). */
    fun changed() {
        diskChanged.value++
    }

    /**
     * Full SHA-256 check of the models, once per process before they are first loaded (a
     * corrupt model can crash native code instead of failing). Corrupt files are deleted.
     */
    suspend fun verifyOnce(): Boolean = withContext(Dispatchers.IO) {
        if (verifiedThisProcess && present()) return@withContext true
        var ok = true
        for (spec in SpeakerModelSpecs.ALL) {
            val f = ModelFiles.target(dir, spec)
            val outcome = ModelFiles.verify(f, spec)
            if (outcome != ModelFiles.Outcome.Ok) {
                Log.w(TAG, "Model ${spec.fileName} failed verification: $outcome")
                if (outcome !is ModelFiles.Outcome.Unreadable) f.delete()
                ok = false
            }
        }
        verifiedThisProcess = ok
        if (!ok) changed()
        ok
    }

    private fun stateOf(info: WorkInfo?): SpeakerModelState {
        val progress = info?.takeIf { it.state == WorkInfo.State.RUNNING }?.progress?.let { p ->
            val total = p.getLong(ModelDownloadWorker.KEY_TOTAL, 0L)
            if (total > 0) p.getLong(ModelDownloadWorker.KEY_DONE, 0L) to total else null
        }
        val error = if (info?.state == WorkInfo.State.FAILED) info.outputData.getString(ModelDownloadWorker.KEY_ERROR) else null
        return SpeakerPhases.modelState(
            present = present(),
            job = info?.state,
            progress = progress,
            bytesOnDisk = ModelFiles.bytesOnDisk(dir),
            totalBytes = SpeakerModelSpecs.TOTAL_BYTES,
            jobError = error,
        )
    }

    companion object {
        const val UNIQUE_WORK = "inkwell-speaker-models"
        private const val TAG = "SpeakerModels"
    }
}
