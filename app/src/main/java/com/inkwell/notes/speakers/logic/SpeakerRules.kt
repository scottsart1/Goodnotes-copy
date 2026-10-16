package com.inkwell.notes.speakers.logic

import androidx.work.WorkInfo
import com.inkwell.notes.speakers.DiarizationPhase
import com.inkwell.notes.speakers.SpeakerModelState

/** Where identification of a recording stands, from its job and stored results. Pure. */
object SpeakerPhases {
    /**
     * A live job wins; then waiting for the models (failed when their download failed); then a
     * stored result; then how the last job ended.
     */
    fun of(
        job: WorkInfo.State?,
        progress: Pair<Int, Int>?,
        jobError: String?,
        hasResult: Boolean,
        waitingForModels: Boolean,
        modelState: SpeakerModelState,
    ): DiarizationPhase = when {
        job == WorkInfo.State.RUNNING -> progress?.let { DiarizationPhase.Running(it.first, it.second) } ?: DiarizationPhase.Running(0, 0)
        job == WorkInfo.State.ENQUEUED || job == WorkInfo.State.BLOCKED -> DiarizationPhase.Queued
        waitingForModels && modelState is SpeakerModelState.Failed -> DiarizationPhase.Failed(modelState.message)
        waitingForModels -> DiarizationPhase.WaitingForModels
        hasResult -> DiarizationPhase.Done
        job == WorkInfo.State.SUCCEEDED -> DiarizationPhase.Done
        job == WorkInfo.State.FAILED -> DiarizationPhase.Failed(jobError)
        else -> DiarizationPhase.None
    }

    /**
     * The model download's state: files on disk win; otherwise the download job's progress.
     * [bytesOnDisk] counts partial downloads so a queued resume shows where it stands.
     */
    fun modelState(
        present: Boolean,
        job: WorkInfo.State?,
        progress: Pair<Long, Long>?,
        bytesOnDisk: Long,
        totalBytes: Long,
        jobError: String?,
    ): SpeakerModelState = when {
        present -> SpeakerModelState.Ready
        job == WorkInfo.State.RUNNING -> progress?.let { SpeakerModelState.Downloading(it.first, it.second) }
            ?: SpeakerModelState.Downloading(bytesOnDisk, totalBytes)
        job == WorkInfo.State.ENQUEUED || job == WorkInfo.State.BLOCKED -> SpeakerModelState.Downloading(bytesOnDisk, totalBytes)
        job == WorkInfo.State.FAILED -> SpeakerModelState.Failed(jobError ?: "Download failed")
        else -> SpeakerModelState.NotDownloaded
    }
}

/** When to ask the user to name voices. Pure. */
object NamingRules {
    /** Voices that spoke less than this are not worth asking about. */
    const val MIN_SPEECH_MS = 4_000L

    data class Voice(val personId: String?, val confirmed: Boolean, val speechMs: Long)

    fun needsNaming(voices: List<Voice>, askToName: Boolean): Boolean =
        askToName && voices.any { it.personId == null && !it.confirmed && it.speechMs >= MIN_SPEECH_MS }

    fun countToName(voices: List<Voice>): Int = voices.count { it.personId == null && !it.confirmed && it.speechMs >= MIN_SPEECH_MS }
}

/** What the "name these voices" notification may say. Pure. */
object NamingNotice {
    sealed interface Text {
        /** "3 voices in 20261016_Standup - tap to name them". */
        data class Counted(val count: Int, val docTitle: String) : Text

        /** "New voices to name": no document title or names while the app lock is on. */
        data object Generic : Text
    }

    /** Null when no notification should be posted. */
    fun decide(count: Int, docTitle: String?, appLocked: Boolean, inForeground: Boolean, canNotify: Boolean): Text? = when {
        count <= 0 || inForeground || !canNotify -> null
        appLocked -> Text.Generic
        docTitle.isNullOrBlank() -> Text.Generic
        else -> Text.Counted(count, docTitle.trim())
    }
}

/** Order of known voices sent with a cloud diarization request. Pure. */
object ReferenceOrder {
    data class Candidate(val personId: String, val isMe: Boolean, val updatedAt: Long)

    /**
     * People linked in this recording first, then the owner, then people heard in recent
     * recordings of the document (most recent first), then everyone else by most recently
     * updated; only people with a reference clip, each once, at most [max].
     */
    fun people(
        people: List<Candidate>,
        inThisRecording: List<String>,
        recentInDocument: List<String>,
        withClip: Set<String>,
        max: Int,
    ): List<String> {
        if (max <= 0) return emptyList()
        val known = people.associateBy { it.personId }
        val order = LinkedHashSet<String>()
        inThisRecording.forEach { order += it }
        people.filter { it.isMe }.forEach { order += it.personId }
        recentInDocument.forEach { order += it }
        people.sortedWith(compareByDescending<Candidate> { it.updatedAt }.thenBy { it.personId }).forEach { order += it.personId }
        return order.filter { it in known && it in withClip }.take(max)
    }
}

/** How the model download continues after the server answers a (possibly ranged) request. Pure. */
object RangeResume {
    sealed interface Action {
        /** 206 for the requested offset: append to the partial file. */
        data object Append : Action

        /** The server ignored the range and sent the whole file: truncate and write this body. */
        data object Overwrite : Action

        /** The range was refused or answered wrongly: drop the partial file and ask again from 0. */
        data object Restart : Action

        data class Fail(val retryable: Boolean) : Action
    }

    fun onResponse(code: Int, requestedFrom: Long, contentRange: String?): Action = when {
        code == 206 && requestedFrom > 0 && rangeStart(contentRange) == requestedFrom -> Action.Append
        code == 206 && requestedFrom == 0L && (rangeStart(contentRange) ?: 0L) == 0L -> Action.Append
        code == 206 -> Action.Restart
        code == 200 -> if (requestedFrom > 0) Action.Overwrite else Action.Append
        code == 416 -> Action.Restart
        code == 408 || code == 429 || code >= 500 -> Action.Fail(retryable = true)
        else -> Action.Fail(retryable = false)
    }

    /** Start offset of a "bytes a-b/total" Content-Range header. */
    fun rangeStart(header: String?): Long? {
        val h = header?.trim() ?: return null
        if (!h.startsWith("bytes", ignoreCase = true)) return null
        val spec = h.substring(5).trim()
        val dash = spec.indexOf('-')
        if (dash <= 0) return null
        return spec.substring(0, dash).trim().toLongOrNull()
    }
}
