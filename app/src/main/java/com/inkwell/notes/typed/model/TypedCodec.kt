package com.inkwell.notes.typed.model

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import kotlinx.serialization.json.Json

/** JSON persistence of [TypedDocument] (files/docs/<docId>/typed.json). */
object TypedCodec {
    const val CURRENT_VERSION = 1

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    fun encode(doc: TypedDocument): String = json.encodeToString(TypedDocument.serializer(), doc.copy(version = CURRENT_VERSION))

    /** Parses and sanitises a document. Throws on malformed JSON. */
    fun decode(text: String): TypedDocument = sanitize(json.decodeFromString(TypedDocument.serializer(), text))

    fun decodeOrNull(text: String?): TypedDocument? =
        if (text.isNullOrBlank()) null else runCatching { decode(text) }.getOrNull()

    /**
     * Repairs anything that would break the editor: spans outside the text, duplicate or
     * blank ids, out-of-range levels and ragged tables. Never drops text.
     */
    fun sanitize(doc: TypedDocument): TypedDocument {
        val seen = HashSet<String>()
        val blocks = doc.blocks.map { b0 ->
            var b = b0
            if (b.id.isBlank() || !seen.add(b.id)) b = b.copy(id = BlockOps.newId()).also { seen.add(it.id) }
            b = when (b.type) {
                BlockType.HEADING -> b.copy(level = b.level.coerceIn(1, 3))
                BlockType.BULLET, BlockType.NUMBERED, BlockType.CHECKLIST -> b.copy(level = b.level.coerceIn(0, BlockOps.MAX_INDENT))
                else -> if (b.level != 0) b.copy(level = 0) else b
            }
            b = if (BlockOps.supportsSpans(b.type)) {
                val normalized = SpanOps.normalize(b.spans, b.text.length)
                if (normalized != b.spans) b.copy(spans = normalized) else b
            } else if (b.spans.isNotEmpty()) b.copy(spans = emptyList()) else b
            if (b.type == BlockType.TABLE) b = b.copy(rows = TableOps.normalize(b.rows))
            b
        }
        return TypedDocument(version = CURRENT_VERSION, blocks = blocks)
    }

    /** A new document's content: one empty paragraph. */
    fun emptyDocument(): TypedDocument = TypedDocument(CURRENT_VERSION, listOf(TypedBlock(BlockOps.newId())))
}
