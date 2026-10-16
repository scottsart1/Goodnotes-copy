package com.inkwell.notes.core.model

import kotlinx.serialization.Serializable

/** Which engine works out who spoke when in a recording. */
@Serializable
enum class SpeakerEngine {
    /**
     * On this tablet (sherpa-onnx: pyannote segmentation + speaker embeddings). Works offline
     * and without an AI key once the models are downloaded; voiceprints never leave the device.
     */
    DEVICE,

    /**
     * OpenAI gpt-4o-transcribe-diarize transcribes and labels speakers in one pass. Short
     * reference clips of up to four known voices are sent with each request.
     */
    OPENAI,

    OFF,
}

@Serializable
data class SpeakerPrefs(
    val engine: SpeakerEngine = SpeakerEngine.DEVICE,
    /** After a recording, ask for the names of voices the app has not met yet. */
    val askToName: Boolean = true,
    /**
     * Keep voiceprints of named people so they are recognised in later recordings. Off: names
     * still label the current recording, but nothing about the voice is stored.
     */
    val rememberVoices: Boolean = true,
    /** Cosine similarity (0..1) a voice needs to be labelled with a known person automatically. */
    val matchThreshold: Float = 0.55f,
    /** Expected number of speakers in a recording; 0 = detect automatically. */
    val expectedSpeakers: Int = 0,
    /** Download the on-device models only on unmetered networks. */
    val downloadOnUnmeteredOnly: Boolean = false,
)

/** Someone whose voice the app knows (or a named speaker when voices are not remembered). */
data class Person(
    val id: String,
    val name: String,
    /** The tablet's owner ("This is me"). At most one person. */
    val isMe: Boolean,
    /** Stable index into the speaker palette (Inkwell.colors speaker colors). */
    val colorIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** Voiceprint samples stored for this person (0 = name only, not recognisable). */
    val voiceSamples: Int,
    /** Recordings this person is labelled in. */
    val recordingCount: Int = 0,
)

/** One voice detected in one recording. [label] is stable within the recording ("S1", "S2", ...). */
data class RecordingSpeaker(
    val recordingId: String,
    val label: String,
    /** The person this voice belongs to, or null while unnamed. */
    val personId: String?,
    /** The person's name, or "Speaker N" while unnamed. */
    val displayName: String,
    /** Voiceprint similarity 0..1 when matched automatically; null when named by the user or unknown. */
    val confidence: Float?,
    /** True once the user named or confirmed this voice. */
    val confirmed: Boolean,
    /** Total speech of this voice in the recording. */
    val speechMs: Long,
    /** A representative 2-10 s clip on the recording timeline, for "play sample". */
    val sampleStartMs: Long,
    val sampleEndMs: Long,
    val colorIndex: Int,
)

/** A stretch of the recording timeline where one voice speaks. */
data class SpeakerTurn(
    val label: String,
    val startMs: Long,
    val endMs: Long,
)
