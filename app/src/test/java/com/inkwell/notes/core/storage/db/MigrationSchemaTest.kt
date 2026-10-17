package com.inkwell.notes.core.storage.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room validates every table against its entity when the database opens, so a migration that
 * creates a table even slightly differently crashes upgraded installs. These tests compare the
 * hand-written migration SQL with the schema Room exports for the current version, and the
 * exported v1 schema (generated from the last v1 commit) with v2, so the migration is known to
 * carry every v1 table over untouched.
 */
class MigrationSchemaTest {
    private val schemaDir = File("schemas/com.inkwell.notes.core.storage.db.AppDatabase")

    private fun schema(version: Int) =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun entities(db: JsonObject): Map<String, JsonObject> =
        db["entities"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }

    private fun fields(entity: JsonObject): Map<String, JsonObject> =
        entity["fields"]!!.jsonArray.map { it.jsonObject }.associateBy { it["columnName"]!!.jsonPrimitive.content }

    private fun normalize(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    private fun createSql(entity: JsonObject, table: String) =
        normalize(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))

    private val v1Tables: Set<String> get() = entities(schema(1)).keys

    @Test
    fun v2TablesMatchRoomsCreateStatements() {
        val db = schema(2)
        val statements = Migrations.V2_STATEMENTS.map(::normalize)
        for (table in listOf("people", "recording_speakers", "speaker_turns")) {
            val entity = entities(db).getValue(table)
            val create = createSql(entity, table)
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
        val field = fields(entities(db).getValue("transcript_segments")).getValue("speakerLabel")
        assertEquals("TEXT", field["affinity"]!!.jsonPrimitive.content)
        assertEquals("false", field["notNull"]?.jsonPrimitive?.content ?: "false")
        assertTrue(Migrations.V2_STATEMENTS.any { normalize(it) == "ALTER TABLE `transcript_segments` ADD COLUMN `speakerLabel` TEXT" })
    }

    @Test
    fun everyTableAddedSinceV1IsCreatedByTheMigration() {
        val v2Tables = entities(schema(2)).keys
        val added = v2Tables - v1Tables
        assertEquals(setOf("people", "recording_speakers", "speaker_turns"), added)
        val created = Migrations.V2_STATEMENTS.mapNotNull { Regex("CREATE TABLE IF NOT EXISTS `([a-z_]+)`").find(it)?.groupValues?.get(1) }.toSet()
        assertEquals(added, created)
        assertTrue("v2 must not drop a v1 table", (v1Tables - v2Tables).isEmpty())
    }

    // ------------------------------------------------------------------ v1 -> v2

    @Test
    fun exportedV1SchemaIsVersionOneWithTheThirteenOriginalTables() {
        val v1 = schema(1)
        assertEquals(1, v1["version"]!!.jsonPrimitive.int)
        assertEquals(
            setOf(
                "folders", "documents", "pages", "outline", "recordings", "transcript_segments", "text_index", "text_index_fts",
                "embeddings", "ai_usage", "chat_messages", "sync_queue", "reusable_elements",
            ),
            v1Tables,
        )
    }

    /** Every v1 table is identical in v2 (the migration never touches it), except transcript_segments. */
    @Test
    fun v1TablesAreUnchangedInV2ExceptTranscriptSegments() {
        val e1 = entities(schema(1))
        val e2 = entities(schema(2))
        for ((table, before) in e1) {
            val after = e2[table]
            assertNotNull("v2 dropped $table", after)
            after!!
            if (table != "transcript_segments") {
                assertEquals("$table CREATE statement changed without a migration", createSql(before, table), createSql(after, table))
                assertEquals("$table fields changed", fields(before), fields(after))
            }
            assertEquals("$table indices changed", before["indices"], after["indices"])
            assertEquals("$table foreign keys changed", before["foreignKeys"], after["foreignKeys"])
            assertEquals("$table primary key changed", before["primaryKey"], after["primaryKey"])
        }
    }

    @Test
    fun transcriptSegmentsOnlyGainsSpeakerLabel() {
        val before = entities(schema(1)).getValue("transcript_segments")
        val after = entities(schema(2)).getValue("transcript_segments")
        val f1 = fields(before)
        val f2 = fields(after)
        assertEquals(f1.keys + "speakerLabel", f2.keys)
        for ((name, def) in f1) assertEquals("column $name changed", def, f2[name])
        // The v2 CREATE is the v1 CREATE with the new column appended after `source`.
        val v2WithoutNewColumn = createSql(after, "transcript_segments").replace(", `speakerLabel` TEXT", "")
        assertEquals(createSql(before, "transcript_segments"), v2WithoutNewColumn)
    }

    /**
     * Columns added to a v1 table must arrive through ALTER TABLE ADD COLUMN, nullable or with a
     * default: a NOT NULL column without default cannot be added to a table that holds rows, and
     * recreating the table would risk the user's transcripts.
     */
    @Test
    fun columnsAddedToV1TablesAreAddedNullableByAlterTable() {
        val e1 = entities(schema(1))
        val e2 = entities(schema(2))
        val alters = Migrations.V2_STATEMENTS.map(::normalize).filter { it.startsWith("ALTER TABLE") }
        var addedColumns = 0
        for ((table, before) in e1) {
            val added = fields(e2.getValue(table)) - fields(before).keys
            for ((column, def) in added) {
                addedColumns++
                val notNull = def["notNull"]?.jsonPrimitive?.content == "true"
                val hasDefault = def["defaultValue"] != null && def["defaultValue"]!!.jsonPrimitive.content.isNotEmpty()
                assertTrue("$table.$column would fail on existing rows", !notNull || hasDefault)
                val affinity = def["affinity"]!!.jsonPrimitive.content
                val expected = "ALTER TABLE `$table` ADD COLUMN `$column` $affinity" + if (notNull) " NOT NULL" else ""
                assertTrue("missing $expected", alters.any { it.startsWith(expected) })
            }
        }
        assertEquals("exactly one column (speakerLabel) is added", 1, addedColumns)
        assertEquals("no stray ALTER statements", addedColumns, alters.size)
        assertTrue("the migration never drops or recreates a table", Migrations.V2_STATEMENTS.none { it.contains("DROP", ignoreCase = true) })
    }

    @Test
    fun migrationsFormAnUnbrokenChainToTheCurrentVersion() {
        val current = schema(2)["version"]!!.jsonPrimitive.int
        val steps = Migrations.ALL.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        assertEquals(listOf(1 to 2), steps)
        assertEquals("every version up to the current one is reachable", current, steps.last().second)
        for (i in 1 until steps.size) assertEquals(steps[i - 1].second, steps[i].first)
    }

    // ------------------------------------------------------------------ cascades

    @Test
    fun speakerRowsFollowTheirRecordingAndPeopleDeletesOnlyClearTheLink() {
        val e2 = entities(schema(2))
        fun fks(table: String) = e2.getValue(table)["foreignKeys"]!!.jsonArray.map { it.jsonObject }
        fun fk(table: String, column: String) = fks(table).first { it["columns"]!!.jsonArray.single().jsonPrimitive.content == column }

        val speakersRec = fk("recording_speakers", "recordingId")
        assertEquals("recordings", speakersRec["table"]!!.jsonPrimitive.content)
        assertEquals("CASCADE", speakersRec["onDelete"]!!.jsonPrimitive.content)

        val speakersPerson = fk("recording_speakers", "personId")
        assertEquals("people", speakersPerson["table"]!!.jsonPrimitive.content)
        assertEquals("deleting a person must keep the voice row, unnamed", "SET NULL", speakersPerson["onDelete"]!!.jsonPrimitive.content)
        // Room leaves "notNull" out of the JSON for nullable columns.
        assertFalse("personId must be nullable for SET NULL", fields(e2.getValue("recording_speakers")).getValue("personId")["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false)

        val turnsRec = fk("speaker_turns", "recordingId")
        assertEquals("recordings", turnsRec["table"]!!.jsonPrimitive.content)
        assertEquals("CASCADE", turnsRec["onDelete"]!!.jsonPrimitive.content)

        // recordings and transcripts cascade from documents, so a document delete leaves no speaker rows.
        assertEquals("CASCADE", fk("recordings", "documentId")["onDelete"]!!.jsonPrimitive.content)
        assertEquals("CASCADE", fk("transcript_segments", "recordingId")["onDelete"]!!.jsonPrimitive.content)

        // Every foreign key is on an indexed column (Room warns, SQLite scans otherwise).
        for (table in listOf("recording_speakers", "speaker_turns")) {
            val indexed = e2.getValue(table)["indices"]!!.jsonArray.flatMap { it.jsonObject["columnNames"]!!.jsonArray.map { c -> c.jsonPrimitive.content } }.toSet()
            val pk = e2.getValue(table)["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
            for (f in fks(table)) {
                val col = f["columns"]!!.jsonArray.single().jsonPrimitive.content
                assertTrue("$table.$col needs an index", col in indexed || pk.firstOrNull() == col)
            }
        }
    }

    @Test
    fun peopleTableHoldsNamesOnlyNeverVoiceData() {
        val e2 = entities(schema(2))
        val blobColumns = listOf("people", "recording_speakers", "speaker_turns", "transcript_segments").flatMap { t ->
            fields(e2.getValue(t)).filterValues { it["affinity"]!!.jsonPrimitive.content == "BLOB" }.keys.map { "$t.$it" }
        }
        assertEquals("voiceprints live in encrypted files, never in Room", emptyList<String>(), blobColumns)
        val names = fields(e2.getValue("people")).keys + fields(e2.getValue("recording_speakers")).keys
        for (n in names) {
            for (word in listOf("embedding", "vector", "centroid", "clip", "wav", "print")) {
                assertFalse("column $n looks like voice data", n.contains(word, ignoreCase = true))
            }
        }
    }
}
