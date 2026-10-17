package com.inkwell.notes.audio.transcription

import com.inkwell.notes.audio.logic.TranscriptCheckpoint
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.speakers.cloud.CloudSpeakerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** How one transcription job treats speakers. Pure. */
internal object SpeakerJobPlan {
    data class Plan(
        /** Transcribe with OpenAI speaker labels (gpt-4o-transcribe-diarize). */
        val cloud: Boolean,
        /** Continue from the saved checkpoint; false starts over. */
        val resume: Boolean,
        /** Drop the checkpoint's speaker labels: labelling stopped being available mid-job. */
        val stripLabels: Boolean,
        /** Speakers were meant to be labelled by OpenAI but cannot be: tell the user. */
        val cloudUnavailable: Boolean,
    )

    /**
     * [checkpointDone] segments were already transcribed, labelled when [checkpointDiarized].
     * Labels stay consistent only when every segment is labelled the same way, so a job that
     * started without labels starts over to get them, and one that loses OpenAI keeps its text
     * and drops its labels.
     */
    fun plan(engine: SpeakerEngine, canDiarize: Boolean, checkpointDone: Int, checkpointDiarized: Boolean): Plan {
        val wanted = engine == SpeakerEngine.OPENAI
        val cloud = wanted && canDiarize
        val unavailable = wanted && !canDiarize
        return when {
            checkpointDone <= 0 || checkpointDiarized == cloud -> Plan(cloud, resume = true, stripLabels = false, cloudUnavailable = unavailable)
            checkpointDiarized -> Plan(cloud = false, resume = true, stripLabels = true, cloudUnavailable = unavailable)
            else -> Plan(cloud = true, resume = false, stripLabels = false, cloudUnavailable = false)
        }
    }
}

/**
 * The job's checkpoint file (transcript.partial.json): a [TranscriptCheckpoint] with the cloud
 * speaker state under [SPEAKERS_KEY] (label mapping, person links and clip windows in ms; never
 * audio). Files written before speaker labelling decode with an empty state, and
 * [TranscriptCheckpoint.decode] still reads these files because it ignores unknown keys.
 */
internal object JobCheckpoint {
    const val SPEAKERS_KEY = "cloudSpeakers"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(checkpoint: TranscriptCheckpoint, speakers: CloudSpeakerState): String {
        val base = json.parseToJsonElement(TranscriptCheckpoint.encode(checkpoint)).jsonObject
        val merged = JsonObject(base + (SPEAKERS_KEY to json.encodeToJsonElement(CloudSpeakerState.serializer(), speakers)))
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    fun decode(text: String): Pair<TranscriptCheckpoint, CloudSpeakerState>? {
        val checkpoint = TranscriptCheckpoint.decode(text) ?: return null
        val speakers = runCatching {
            json.parseToJsonElement(text).jsonObject[SPEAKERS_KEY]?.let { json.decodeFromJsonElement(CloudSpeakerState.serializer(), it) }
        }.getOrNull() ?: CloudSpeakerState()
        return checkpoint to speakers
    }

    /** [checkpoint] with every block's speaker removed (text and timing kept). */
    fun withoutSpeakers(checkpoint: TranscriptCheckpoint): TranscriptCheckpoint =
        checkpoint.copy(segments = checkpoint.segments.map { it.copy(speaker = null, speakerLabel = null) })
}
