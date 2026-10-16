package com.inkwell.notes.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE documentId = :docId ORDER BY startedAt")
    fun observeForDocument(docId: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE documentId = :docId ORDER BY startedAt")
    suspend fun forDocument(docId: String): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE status IN ('RECORDING','PAUSED')")
    suspend fun unfinished(): List<RecordingEntity>

    @Upsert
    suspend fun upsert(r: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs")
    fun observe(recordingId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun forRecording(recordingId: String): List<TranscriptSegmentEntity>

    @Insert
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: String)

    @Transaction
    suspend fun replace(recordingId: String, segments: List<TranscriptSegmentEntity>) {
        deleteForRecording(recordingId)
        insertAll(segments)
    }
}

/** Row returned by full-text search. */
data class TextMatchRow(
    val rowId: Long,
    val documentId: String,
    val pageId: String?,
    val recordingId: String?,
    val source: String,
    val text: String,
    val confidence: Float?,
    val timestampMs: Long?,
    val bounds: String?,
)

@Dao
interface TextIndexDao {
    @Query(
        """SELECT t.rowId, t.documentId, t.pageId, t.recordingId, t.source, t.text, t.confidence, t.timestampMs, t.bounds
           FROM text_index t JOIN text_index_fts f ON t.rowId = f.rowid
           WHERE text_index_fts MATCH :ftsQuery LIMIT :limit"""
    )
    suspend fun search(ftsQuery: String, limit: Int): List<TextMatchRow>

    @Query("SELECT * FROM text_index WHERE documentId = :docId ORDER BY pageId, rowId")
    suspend fun forDocument(docId: String): List<TextIndexEntity>

    @Query("SELECT * FROM text_index WHERE rowId IN (:rowIds)")
    suspend fun byIds(rowIds: List<Long>): List<TextIndexEntity>

    @Insert
    suspend fun insertAll(rows: List<TextIndexEntity>): List<Long>

    @Query("DELETE FROM text_index WHERE documentId = :docId AND pageId IS :pageId AND source = :source")
    suspend fun deleteFor(docId: String, pageId: String?, source: String)

    @Query("DELETE FROM text_index WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: String)

    @Query("DELETE FROM text_index WHERE documentId = :docId")
    suspend fun deleteDocument(docId: String)

    @Transaction
    suspend fun replace(docId: String, pageId: String?, source: String, rows: List<TextIndexEntity>): List<Long> {
        deleteFor(docId, pageId, source)
        return insertAll(rows)
    }
}

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM embeddings WHERE model = :model")
    suspend fun all(model: String): List<EmbeddingEntity>

    @Insert
    suspend fun insertAll(rows: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE documentId = :docId")
    suspend fun deleteDocument(docId: String)

    @Query("SELECT textRowId FROM embeddings WHERE model = :model")
    suspend fun embeddedRowIds(model: String): List<Long>
}

/** Monthly/total usage aggregates. */
data class UsageTotalRow(val provider: String, val model: String, val inputTokens: Long, val outputTokens: Long, val costUsd: Double?, val requests: Int)

@Dao
interface AiUsageDao {
    @Insert
    suspend fun insert(row: AiUsageEntity)

    @Query("SELECT * FROM ai_usage ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AiUsageEntity>>

    @Query(
        """SELECT provider, model, SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens,
           SUM(estimatedCostUsd) AS costUsd, COUNT(*) AS requests
           FROM ai_usage WHERE timestamp >= :since GROUP BY provider, model ORDER BY costUsd DESC"""
    )
    fun observeTotalsSince(since: Long): Flow<List<UsageTotalRow>>

    @Query("SELECT COALESCE(SUM(estimatedCostUsd), 0) FROM ai_usage WHERE timestamp >= :since")
    suspend fun costSince(since: Long): Double

    @Query("DELETE FROM ai_usage")
    suspend fun clear()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY createdAt")
    fun observeThread(threadId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT DISTINCT threadId FROM chat_messages WHERE documentId = :docId ORDER BY createdAt DESC")
    suspend fun threadsForDocument(docId: String): List<String>

    @Upsert
    suspend fun upsert(msg: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)
}

@Dao
interface SyncQueueDao {
    @Insert
    suspend fun enqueue(item: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY enqueuedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM sync_queue ORDER BY enqueuedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SyncQueueEntity>>

    @Upsert
    suspend fun update(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE status = 'DONE' AND enqueuedAt < :before")
    suspend fun pruneDone(before: Long)

    @Query("DELETE FROM sync_queue WHERE documentId = :docId AND kind = :kind AND target = :target AND status = 'PENDING'")
    suspend fun dropPendingDuplicates(docId: String, kind: String, target: String)
}

@Dao
interface ReusableElementDao {
    @Query("SELECT * FROM reusable_elements ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ReusableElementEntity>>

    @Upsert
    suspend fun upsert(e: ReusableElementEntity)

    @Query("DELETE FROM reusable_elements WHERE id = :id")
    suspend fun delete(id: String)
}
