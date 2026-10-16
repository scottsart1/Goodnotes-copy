package com.inkwell.notes.core.model

import kotlinx.serialization.Serializable

/** A structured typed document, stored as JSON in files/docs/<docId>/typed.json. */
@Serializable
data class TypedDocument(
    val version: Int = 1,
    val blocks: List<TypedBlock> = emptyList(),
)

@Serializable
enum class BlockType { PARAGRAPH, HEADING, BULLET, NUMBERED, CHECKLIST, QUOTE, CODE, TABLE, IMAGE, DIVIDER }

/**
 * One block. [text] holds the plain text; [spans] mark inline formatting over it.
 * HEADING uses [level] 1..3; BULLET/NUMBERED/CHECKLIST use [level] as indent depth.
 * TABLE uses [rows] (first row = header). IMAGE uses [assetId] (file in the doc's assets) and [text] as caption.
 */
@Serializable
data class TypedBlock(
    val id: String,
    val type: BlockType = BlockType.PARAGRAPH,
    val text: String = "",
    val spans: List<TextSpan> = emptyList(),
    val level: Int = 0,
    val checked: Boolean = false,
    val rows: List<List<String>> = emptyList(),
    val assetId: String? = null,
    val language: String? = null,
)

@Serializable
data class TextSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)
