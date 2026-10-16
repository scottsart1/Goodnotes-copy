package com.inkwell.notes.speakers.cloud

import com.inkwell.notes.ai.provider.OpenAiModels
import kotlinx.serialization.Serializable

/**
 * One voice heard so far while a recording is transcribed by OpenAI with speaker labels.
 * Only timeline positions are kept here (never audio), so this can live in the job's checkpoint.
 */
@Serializable
data class CloudVoice(
    /** "S1", "S2", ... in order of first appearance; stable across the recording's audio segments. */
    val label: String,
    /** The known person whose reference the provider echoed back for this voice, or null. */
    val personId: String? = null,
    val personName: String? = null,
    /** Speech so far, in ms. */
    val speechMs: Long = 0,
    /**
     * Where this voice spoke longest (recording timeline, ms), cut as its reference clip for the
     * following segments. Null while no stretch is long enough.
     */
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
)

/**
 * Speaker state of one transcription job, saved with its checkpoint so a resumed job keeps the
 * same labels and person links. The provider's own labels ("A", "B") are local to each audio
 * file, so they are resolved file by file and never need to be stored.
 */
@Serializable
data class CloudSpeakerState(
    /** The transcript so far was labelled by the cloud engine. */
    val diarized: Boolean = false,
    val voices: List<CloudVoice> = emptyList(),
)

/** Recording labels "S1", "S2", ... */
object CloudLabels {
    /** DiarizationResult.engine for this path. */
    const val ENGINE = "openai:" + OpenAiModels.DIARIZE_MODEL

    fun label(index: Int): String = "S$index"

    /** 1-based index of a recording label, or null for anything else. */
    fun indexOf(label: String?): Int? =
        label?.takeIf { it.startsWith("S") }?.substring(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** The next unused label. */
    fun next(voices: List<CloudVoice>): String = label((voices.mapNotNull { indexOf(it.label) }.maxOrNull() ?: 0) + 1)
}
