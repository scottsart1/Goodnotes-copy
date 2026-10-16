package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import kotlin.math.roundToInt

/**
 * When the recorder dock offers "Name the new voices" for a recording that finished while its
 * document was open. Pure.
 */
object NamingPromptLogic {
    /** A recording in progress, as far as the prompt cares. */
    data class Live(val docId: String, val recordingId: String)

    /** The recording of [docId] that stopped between [before] and [now], or null. */
    fun finished(before: Live?, now: Live?, docId: String): String? =
        if (before != null && before.docId == docId && now?.recordingId != before.recordingId) before.recordingId else null

    fun visible(
        candidate: String?,
        recordingActive: Boolean,
        needsNaming: Boolean,
        dismissed: Boolean,
        prefs: SpeakerPrefs,
    ): Boolean = candidate != null && !recordingActive && needsNaming && !dismissed && prefs.askToName && prefs.engine != SpeakerEngine.OFF
}

/** Numbers behind the speaker texts (units are formatted with string resources). Pure. */
object SpeakerNumbers {
    /** Whole megabytes, rounded to nearest; at least 1 for any non-zero size. */
    fun megabytes(bytes: Long): Long = if (bytes <= 0) 0 else ((bytes + 500_000) / 1_000_000).coerceAtLeast(1)

    /** Rounded minutes of speech; 0 means under half a minute (shown in seconds). */
    fun minutes(ms: Long): Int = ((ms.coerceAtLeast(0) + 30_000) / 60_000).toInt()

    /** Whole seconds, at least 1 for any speech. */
    fun seconds(ms: Long): Int = if (ms <= 0) 0 else ((ms + 500) / 1000).toInt().coerceAtLeast(1)

    fun percent(fraction: Float): Int = (fraction * 100f).roundToInt().coerceIn(0, 100)

    /** 0..1 progress of [done] of [total], or null when the total is unknown. */
    fun fraction(done: Long, total: Long): Float? = if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
}
