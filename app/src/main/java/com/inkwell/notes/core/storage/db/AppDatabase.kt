package com.inkwell.notes.core.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        DocumentEntity::class,
        PageEntity::class,
        OutlineEntity::class,
        RecordingEntity::class,
        TranscriptSegmentEntity::class,
        TextIndexEntity::class,
        TextIndexFts::class,
        EmbeddingEntity::class,
        AiUsageEntity::class,
        ChatMessageEntity::class,
        SyncQueueEntity::class,
        ReusableElementEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun documents(): DocumentDao
    abstract fun pages(): PageDao
    abstract fun outline(): OutlineDao
    abstract fun recordings(): RecordingDao
    abstract fun transcripts(): TranscriptDao
    abstract fun textIndex(): TextIndexDao
    abstract fun embeddings(): EmbeddingDao
    abstract fun aiUsage(): AiUsageDao
    abstract fun chat(): ChatDao
    abstract fun syncQueue(): SyncQueueDao
    abstract fun reusableElements(): ReusableElementDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "inkwell.db")
                // WAL keeps writes durable and readers unblocked while ink autosaves.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
