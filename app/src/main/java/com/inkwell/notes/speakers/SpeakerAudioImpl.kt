package com.inkwell.notes.speakers

import com.inkwell.notes.app.AppGraph
import java.io.File

/** SCAFFOLD STUB — replaced in place by the platform decoder (MediaExtractor/MediaCodec). */
class SpeakerAudioImpl(private val graph: AppGraph) : SpeakerAudio {
    override suspend fun decode(file: File, startMs: Long, endMs: Long): FloatArray = FloatArray(0)
    override suspend fun clip(recordingId: String, startMs: Long, endMs: Long): ByteArray? = null
}
