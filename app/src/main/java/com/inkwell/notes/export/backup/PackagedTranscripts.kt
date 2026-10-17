package com.inkwell.notes.export.backup

import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity

/**
 * Transcript rows restored from a package: text, timing, speaker names and per-recording voice
 * labels ("S1"). Packages never carry voiceprints, so a restored recording's speakers are
 * named but not recognisable until someone is enrolled again on this device.
 */
internal object PackagedTranscripts {
    const val IMPORT_SOURCE = "import"

    fun entities(recordingId: String, segments: List<TranscriptSegment>, source: String?): List<TranscriptSegmentEntity> =
        segments.map {
            TranscriptSegmentEntity(
                recordingId = recordingId, startMs = it.startMs, endMs = it.endMs, text = it.text,
                speaker = it.speaker?.takeIf { s -> s.isNotBlank() }, source = source ?: IMPORT_SOURCE,
                speakerLabel = it.speakerLabel?.takeIf { l -> l.isNotBlank() },
            )
        }
}
