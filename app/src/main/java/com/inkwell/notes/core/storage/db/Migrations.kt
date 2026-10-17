package com.inkwell.notes.core.storage.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations. Every statement must match Room's own CREATE statements in
 * app/schemas (MigrationSchemaTest checks this), or Room rejects the database on open.
 */
object Migrations {
    /** v2: speaker identification (people, voices per recording, who spoke when). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V2_STATEMENTS.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    internal val V2_STATEMENTS = listOf(
        "ALTER TABLE `transcript_segments` ADD COLUMN `speakerLabel` TEXT",
        "CREATE TABLE IF NOT EXISTS `people` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `isMe` INTEGER NOT NULL, " +
            "`colorIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`voiceSamples` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_people_name` ON `people` (`name`)",
        "CREATE TABLE IF NOT EXISTS `recording_speakers` (`recordingId` TEXT NOT NULL, `label` TEXT NOT NULL, " +
            "`personId` TEXT, `confidence` REAL, `confirmed` INTEGER NOT NULL, `speechMs` INTEGER NOT NULL, " +
            "`sampleStartMs` INTEGER NOT NULL, `sampleEndMs` INTEGER NOT NULL, `engine` TEXT NOT NULL, " +
            "PRIMARY KEY(`recordingId`, `label`), " +
            "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_recording_speakers_personId` ON `recording_speakers` (`personId`)",
        "CREATE TABLE IF NOT EXISTS `speaker_turns` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`recordingId` TEXT NOT NULL, `label` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, " +
            "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_speaker_turns_recordingId` ON `speaker_turns` (`recordingId`)",
    )
}
