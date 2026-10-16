package com.inkwell.notes.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RecordingStatus { RECORDING, PAUSED, COMPLETE, INTERRUPTED }

@Serializable
enum class TranscriptStatus { NONE, QUEUED, RUNNING, DONE, FAILED }

/**
 * An audio recording attached to a document. Audio is stored as consecutive,
 * independently playable AAC/M4A segments ([segmentFiles], absolute paths, in order)
 * so a crash loses at most the segment in progress.
 */
@Serializable
data class Recording(
    val id: String,
    val documentId: String,
    val title: String,
    val segmentFiles: List<String>,
    /** Duration of each segment in ms, parallel to [segmentFiles]. */
    val segmentDurationsMs: List<Long>,
    val durationMs: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: RecordingStatus,
    val transcriptStatus: TranscriptStatus = TranscriptStatus.NONE,
    /** Downsampled amplitude envelope (0..1), ~10 values per second. */
    val waveformPath: String? = null,
)

@Serializable
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
)

@Serializable
data class Transcript(
    val recordingId: String,
    val segments: List<TranscriptSegment>,
    /** e.g. "openai:whisper-1" or "on-device". */
    val source: String,
    val language: String? = null,
) {
    fun fullText(): String = segments.joinToString("\n") { it.text }
}

/** Origin of a piece of indexed text. */
@Serializable
enum class TextSource { TITLE, TYPED, HANDWRITING, OCR, PDF_TEXT, TRANSCRIPT, TAG, FOLDER }

/** One search result. */
data class SearchHit(
    val documentId: String,
    val documentTitle: String,
    val kind: DocKind,
    val folderPath: String,
    val pageId: String?,
    val pageIndex: Int?,
    val recordingId: String?,
    val timestampMs: Long?,
    val source: TextSource,
    /** Excerpt with the match; [matchStart]/[matchEnd] index into it (-1 if unknown). */
    val excerpt: String,
    val matchStart: Int = -1,
    val matchEnd: Int = -1,
    val score: Double = 0.0,
    val thumbnailPath: String? = null,
    /** Bounds of the match on the page in page points, when known: left, top, right, bottom. */
    val bounds: FloatArray? = null,
)

/** Text recognised by OCR or handwriting recognition, with location. */
data class RecognizedBlock(
    val text: String,
    /** left, top, right, bottom in page points (or image pixels for raw images). */
    val bounds: FloatArray,
    /** 0..1, or null when the engine reports none. */
    val confidence: Float?,
)

data class RecognitionResult(
    val text: String,
    val blocks: List<RecognizedBlock>,
    val confidence: Float?,
    /** e.g. "mlkit-text", "mlkit-ink", "ai:OPENAI:gpt-4o". */
    val engine: String,
    /** Words or regions the engine could not read, kept rather than guessed. */
    val uncertainSpans: List<String> = emptyList(),
)
