package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.speakers.DiarizationPhase
import com.inkwell.notes.speakers.SpeakerModelState

/** What the recording panel says about identifying the speakers of one recording. */
sealed interface SpeakerStatus {
    /** Nothing to say (engine off, speakers known, or nothing to offer). */
    data object Hidden : SpeakerStatus

    /** "Identifying speakers 3/12" ([total] 0 = not known yet). */
    data class Analysing(val done: Int, val total: Int) : SpeakerStatus

    data object Queued : SpeakerStatus

    /** The job waits for the one-time model download, which is running. */
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : SpeakerStatus

    /** The job waits for the model download to start ([wifiOnly]: for an unmetered network). */
    data class WaitingForModels(val wifiOnly: Boolean) : SpeakerStatus

    data class ModelsFailed(val message: String) : SpeakerStatus

    data class Failed(val message: String?, val canRetry: Boolean) : SpeakerStatus

    /** Identification ran and found no separate voices. */
    data class NoneFound(val canRetry: Boolean) : SpeakerStatus

    /** "Identify speakers" on the device, downloading the models first when [needsDownload]. */
    data class Offer(val needsDownload: Boolean) : SpeakerStatus

    /** Busy states offer Cancel. */
    val cancellable: Boolean get() = this is Analysing || this is Queued || this is Downloading || this is WaitingForModels
}

object SpeakerStatusLogic {
    /** About how large the one-time on-device model download is, for texts before it starts. */
    const val MODEL_DOWNLOAD_MB = 32

    fun of(
        engine: SpeakerEngine,
        phase: DiarizationPhase,
        models: SpeakerModelState,
        hasSpeakers: Boolean,
        hasAudio: Boolean,
        wifiOnly: Boolean,
    ): SpeakerStatus {
        if (engine == SpeakerEngine.OFF) return SpeakerStatus.Hidden
        val canRun = engine == SpeakerEngine.DEVICE && hasAudio
        return when (phase) {
            is DiarizationPhase.Running -> SpeakerStatus.Analysing(phase.done.coerceAtLeast(0), phase.total.coerceAtLeast(0))
            DiarizationPhase.Queued -> SpeakerStatus.Queued
            DiarizationPhase.WaitingForModels -> when (models) {
                is SpeakerModelState.Downloading -> SpeakerStatus.Downloading(models.bytesDone, models.bytesTotal)
                is SpeakerModelState.Failed -> SpeakerStatus.ModelsFailed(models.message)
                // Ready: the job is about to pick up again.
                SpeakerModelState.Ready -> SpeakerStatus.Queued
                SpeakerModelState.NotDownloaded -> SpeakerStatus.WaitingForModels(wifiOnly)
            }
            is DiarizationPhase.Failed -> SpeakerStatus.Failed(phase.message?.takeIf { it.isNotBlank() }, canRun)
            DiarizationPhase.Done -> if (hasSpeakers) SpeakerStatus.Hidden else SpeakerStatus.NoneFound(canRun)
            DiarizationPhase.None -> when {
                hasSpeakers -> SpeakerStatus.Hidden
                canRun -> SpeakerStatus.Offer(needsDownload = models !is SpeakerModelState.Ready)
                else -> SpeakerStatus.Hidden
            }
        }
    }
}
