package com.inkwell.notes.core.storage.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TranscriptStatus

@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val color: Int,
    val icon: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val sortOrder: Int,
    val trashedAt: Long?,
) {
    fun toModel() = Folder(id, name, parentId, color, icon, createdAt, modifiedAt, sortOrder, trashedAt)

    companion object {
        fun from(f: Folder) = FolderEntity(f.id, f.name, f.parentId, f.color, f.icon, f.createdAt, f.modifiedAt, f.sortOrder, f.trashedAt)
    }
}

@Entity(tableName = "documents", indices = [Index("folderId"), Index("modifiedAt"), Index("autoNameStatus")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: DocKind,
    val folderId: String?,
    val createdAt: Long,
    val modifiedAt: Long,
    val openedAt: Long,
    val autoNameStatus: AutoNameStatus,
    val autoNameAttempts: Int,
    val topic: String?,
    val originalName: String?,
    val favorite: Boolean,
    val pinned: Boolean,
    val archived: Boolean,
    val trashedAt: Long?,
    val pageCount: Int,
    val lastPageIndex: Int,
    val lastZoom: Float,
    val lastScrollX: Float,
    val lastScrollY: Float,
    val thumbnailPath: String?,
    val coverColor: Int,
    /** Tags joined with '\u001F'. */
    val tags: String,
    val sizeBytes: Long,
    val lastSyncedAt: Long,
) {
    fun toModel() = DocumentMeta(
        id = id, title = title, kind = kind, folderId = folderId, createdAt = createdAt, modifiedAt = modifiedAt,
        openedAt = openedAt, autoNameStatus = autoNameStatus, autoNameAttempts = autoNameAttempts, topic = topic,
        originalName = originalName, favorite = favorite, pinned = pinned, archived = archived, trashedAt = trashedAt,
        pageCount = pageCount, lastPageIndex = lastPageIndex, lastZoom = lastZoom, lastScrollX = lastScrollX,
        lastScrollY = lastScrollY, thumbnailPath = thumbnailPath, coverColor = coverColor,
        tags = if (tags.isEmpty()) emptyList() else tags.split(TAG_SEP), sizeBytes = sizeBytes, lastSyncedAt = lastSyncedAt,
    )

    companion object {
        const val TAG_SEP = "\u001F"
        fun from(d: DocumentMeta) = DocumentEntity(
            d.id, d.title, d.kind, d.folderId, d.createdAt, d.modifiedAt, d.openedAt, d.autoNameStatus, d.autoNameAttempts,
            d.topic, d.originalName, d.favorite, d.pinned, d.archived, d.trashedAt, d.pageCount, d.lastPageIndex, d.lastZoom,
            d.lastScrollX, d.lastScrollY, d.thumbnailPath, d.coverColor, d.tags.joinToString(TAG_SEP), d.sizeBytes, d.lastSyncedAt,
        )
    }
}

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId"), Index(value = ["documentId", "pageIndex"])],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val pageIndex: Int,
    val widthPt: Float,
    val heightPt: Float,
    val paperStyle: PaperStyle,
    val paperColor: Int,
    val lineColor: Int,
    val spacingPt: Float,
    val rotation: Int,
    val bookmarked: Boolean,
    val bookmarkTitle: String?,
    val pdfPageIndex: Int?,
    val modifiedAt: Long,
    val thumbnailPath: String?,
    /** Last time this page's text was (re)indexed; 0 = never. */
    val indexedAt: Long,
) {
    fun toModel() = PageMeta(
        id, documentId, pageIndex, widthPt, heightPt, PaperSpec(paperStyle, paperColor, lineColor, spacingPt),
        rotation, bookmarked, bookmarkTitle, pdfPageIndex, modifiedAt, thumbnailPath,
    )

    companion object {
        fun from(p: PageMeta, indexedAt: Long = 0) = PageEntity(
            p.id, p.documentId, p.index, p.widthPt, p.heightPt, p.paper.style, p.paper.color, p.paper.lineColor,
            p.paper.spacingPt, p.rotation, p.bookmarked, p.bookmarkTitle, p.pdfPageIndex, p.modifiedAt, p.thumbnailPath, indexedAt,
        )
    }
}

@Entity(
    tableName = "outline",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")],
)
data class OutlineEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val title: String,
    val pageId: String,
    val parentId: String?,
    val sortOrder: Int,
) {
    fun toModel() = OutlineEntry(id, documentId, title, pageId, parentId, sortOrder)

    companion object {
        fun from(o: OutlineEntry) = OutlineEntity(o.id, o.documentId, o.title, o.pageId, o.parentId, o.sortOrder)
    }
}

@Entity(
    tableName = "recordings",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")],
)
data class RecordingEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val title: String,
    /** Segment file paths joined with '\n'. */
    val segmentFiles: String,
    /** Segment durations (ms) joined with ','. */
    val segmentDurations: String,
    val durationMs: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val status: RecordingStatus,
    val transcriptStatus: TranscriptStatus,
    val waveformPath: String?,
) {
    fun toModel() = Recording(
        id, documentId, title,
        if (segmentFiles.isEmpty()) emptyList() else segmentFiles.split('\n'),
        if (segmentDurations.isEmpty()) emptyList() else segmentDurations.split(',').map { it.toLong() },
        durationMs, startedAt, endedAt, status, transcriptStatus, waveformPath,
    )

    companion object {
        fun from(r: Recording) = RecordingEntity(
            r.id, r.documentId, r.title, r.segmentFiles.joinToString("\n"), r.segmentDurationsMs.joinToString(","),
            r.durationMs, r.startedAt, r.endedAt, r.status, r.transcriptStatus, r.waveformPath,
        )
    }
}

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val recordingId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String?,
    val source: String,
) {
    fun toModel() = TranscriptSegment(startMs, endMs, text, speaker)
}

/**
 * Every searchable piece of text: titles, typed text, recognised handwriting, OCR,
 * PDF text and transcripts. Mirrored into [TextIndexFts] for full-text search.
 */
@Entity(
    tableName = "text_index",
    indices = [Index("documentId"), Index("pageId"), Index("recordingId"), Index(value = ["documentId", "pageId", "source"])],
)
data class TextIndexEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val documentId: String,
    val pageId: String?,
    val recordingId: String?,
    val source: TextSource,
    val text: String,
    val confidence: Float?,
    /** Start of the passage on a recording timeline (transcripts) or creation offset. */
    val timestampMs: Long?,
    /** left,top,right,bottom in page points, comma-separated; null if unknown. */
    val bounds: String?,
    /** e.g. "mlkit-text", "mlkit-ink", "pdfbox", "ai:OPENAI:gpt-4o". */
    val engine: String,
    val updatedAt: Long,
)

@Fts4(contentEntity = TextIndexEntity::class)
@Entity(tableName = "text_index_fts")
data class TextIndexFts(val text: String)

@Entity(tableName = "embeddings", indices = [Index("textRowId"), Index("documentId")])
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val textRowId: Long,
    val documentId: String,
    val model: String,
    /** Little-endian float32 vector. */
    val vector: ByteArray,
)

@Entity(tableName = "ai_usage", indices = [Index("timestamp")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val timestamp: Long,
    val provider: String,
    val model: String,
    val task: String,
    val inputTokens: Int,
    val outputTokens: Int,
    /** Local estimate, never the provider's invoice. Null if the model has no price entry. */
    val estimatedCostUsd: Double?,
    val documentId: String?,
)

@Entity(tableName = "chat_messages", indices = [Index("threadId"), Index("documentId")])
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val documentId: String?,
    /** "user" | "assistant" | "system". */
    val role: String,
    val text: String,
    val model: String?,
    val createdAt: Long,
    /** JSON array of source references (page ids, recording timestamps). */
    val sourcesJson: String?,
)

@Entity(tableName = "sync_queue", indices = [Index("documentId"), Index("status")])
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val documentId: String,
    /** "NOTES" | "TRANSCRIPT" | "AUDIO" | "DELETE". */
    val kind: String,
    /** OutputTarget name. */
    val target: String,
    /** Recording id for TRANSCRIPT/AUDIO. */
    val refId: String?,
    val enqueuedAt: Long,
    val attempts: Int,
    val lastError: String?,
    /** "PENDING" | "DONE" | "FAILED". */
    val status: String,
)

/** Reusable diagrams, signatures and drawings ("my elements"). */
@Entity(tableName = "reusable_elements")
data class ReusableElementEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** "drawing" | "signature" | "diagram" | "sticker". */
    val kind: String,
    /** ProtoBuf-encoded PageContent fragment, normalised to origin. */
    val payload: ByteArray,
    val widthPt: Float,
    val heightPt: Float,
    val thumbnailPath: String?,
    val createdAt: Long,
)
