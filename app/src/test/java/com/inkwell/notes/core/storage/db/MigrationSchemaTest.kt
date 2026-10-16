package com.inkwell.notes.core.storage.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room validates every table against its entity when the database opens, so a migration that
 * creates a table even slightly differently crashes upgraded installs. These tests compare the
 * hand-written migration SQL with the schema Room exports for the current version.
 */
class MigrationSchemaTest {
    private val schemaDir = File("schemas/com.inkwell.notes.core.storage.db.AppDatabase")

    private fun schema(version: Int) =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun normalize(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun v2TablesMatchRoomsCreateStatements() {
        val db = schema(2)
        val statements = Migrations.V2_STATEMENTS.map(::normalize)
        for (table in listOf("people", "recording_speakers", "speaker_turns")) {
            val entity = db["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == table }
            val create = normalize(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            assertTrue("Migration must create $table exactly as Room does:\n$create", create in statements)
            entity["indices"]?.jsonArray?.forEach { idx ->
                val sql = normalize(idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                assertTrue("Migration must create index:\n$sql", sql in statements)
            }
        }
    }

    @Test
    fun v2AddsSpeakerLabelColumnAsNullableText() {
        val db = schema(2)
        val segments = db["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "transcript_segments" }
        val field = segments["fields"]!!.jsonArray.map { it.jsonObject }.first { it["columnName"]!!.jsonPrimitive.content == "speakerLabel" }
        assertEquals("TEXT", field["affinity"]!!.jsonPrimitive.content)
        assertEquals("false", field["notNull"]?.jsonPrimitive?.content ?: "false")
        assertTrue(Migrations.V2_STATEMENTS.any { normalize(it) == "ALTER TABLE `transcript_segments` ADD COLUMN `speakerLabel` TEXT" })
    }

    @Test
    fun everyTableAddedSinceV1IsCreatedByTheMigration() {
        val v1Tables = setOf(
            "folders", "documents", "pages", "outline", "recordings", "transcript_segments", "text_index", "text_index_fts",
            "embeddings", "ai_usage", "chat_messages", "sync_queue", "reusable_elements",
        )
        val v2Tables = schema(2)["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        val added = v2Tables - v1Tables
        val created = Migrations.V2_STATEMENTS.mapNotNull { Regex("CREATE TABLE IF NOT EXISTS `([a-z_]+)`").find(it)?.groupValues?.get(1) }.toSet()
        assertEquals(added, created)
    }
}
