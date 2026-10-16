package com.inkwell.notes.audio.transcription

import androidx.work.WorkInfo
import com.inkwell.notes.core.model.TranscriptStatus

/** Where transcription of one recording stands, combining the row's status with its job. */
internal sealed interface TranscriptPhase {
    data object None : TranscriptPhase
    data object Queued : TranscriptPhase
    data class Running(val done: Int, val total: Int) : TranscriptPhase
    data object Done : TranscriptPhase
    data class Failed(val message: String?) : TranscriptPhase
    /** The row says RUNNING but no job exists any more (process killed, work pruned). */
    data object Interrupted : TranscriptPhase

    val busy: Boolean get() = this is Queued || this is Running

    companion object {
        fun of(status: TranscriptStatus, info: WorkInfo?): TranscriptPhase =
            of(status, info?.state, Transcriptions.progressOf(info), Transcriptions.errorOf(info))

        /**
         * A live job wins (it is the freshest information); otherwise the stored status, with
         * a failed job's message when there is one.
         */
        fun of(status: TranscriptStatus, job: WorkInfo.State?, progress: Pair<Int, Int>?, jobError: String?): TranscriptPhase = when {
            job == WorkInfo.State.RUNNING -> progress?.let { Running(it.first, it.second) } ?: Running(0, 0)
            job == WorkInfo.State.ENQUEUED || job == WorkInfo.State.BLOCKED -> Queued
            status == TranscriptStatus.DONE -> Done
            job == WorkInfo.State.FAILED && status != TranscriptStatus.QUEUED -> Failed(jobError)
            status == TranscriptStatus.FAILED -> Failed(null)
            // Just queued: the job appears a moment after the row is marked.
            status == TranscriptStatus.QUEUED -> Queued
            status == TranscriptStatus.RUNNING -> Interrupted
            else -> None
        }
    }
}
