package com.inkwell.notes.audio.logic

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock

/**
 * Turns the light Markdown that AI answers use into typed-document blocks: headings,
 * bullet / numbered / check lists (with indentation), quotes, fenced code, dividers and
 * paragraphs, with **bold**, *italic* / _italic_ and `code` spans.
 */
object MarkdownBlocks {
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val CHECK = Regex("""^[-*+]\s+\[([ xX])]\s+(.*)$""")
    private val BULLET = Regex("""^[-*+•]\s+(.*)$""")
    private val NUMBERED = Regex("""^\d{1,3}[.)]\s+(.*)$""")
    private val DIVIDER = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

    fun parse(markdown: String, newId: () -> String): List<TypedBlock> {
        val out = ArrayList<TypedBlock>()
        val paragraph = StringBuilder()
        var code: StringBuilder? = null
        var codeLang: String? = null

        fun flushParagraph() {
            if (paragraph.isNotBlank()) out += inlineBlock(newId(), BlockType.PARAGRAPH, paragraph.toString().trim())
            paragraph.setLength(0)
        }

        for (rawLine in markdown.replace("\r\n", "\n").split('\n')) {
            val fence = rawLine.trimStart()
            if (code != null) {
                if (fence.startsWith("```")) {
                    out += TypedBlock(newId(), BlockType.CODE, code.toString().trimEnd('\n'), language = codeLang)
                    code = null
                    codeLang = null
                } else code.append(rawLine).append('\n')
                continue
            }
            if (fence.startsWith("```")) {
                flushParagraph()
                code = StringBuilder()
                codeLang = fence.removePrefix("```").trim().ifEmpty { null }
                continue
            }
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) { flushParagraph(); continue }
            val indent = indentLevel(line)

            val heading = HEADING.matchEntire(trimmed)
            val check = if (heading == null) CHECK.matchEntire(trimmed) else null
            val bullet = if (heading == null && check == null) BULLET.matchEntire(trimmed) else null
            val numbered = if (heading == null && check == null && bullet == null) NUMBERED.matchEntire(trimmed) else null
            when {
                heading != null -> {
                    flushParagraph()
                    val level = heading.groupValues[1].length.coerceAtMost(3)
                    out += inlineBlock(newId(), BlockType.HEADING, heading.groupValues[2].trim().trimEnd('#').trim(), level = level)
                    continue
                }
                DIVIDER.matches(trimmed) -> {
                    flushParagraph()
                    out += TypedBlock(newId(), BlockType.DIVIDER)
                    continue
                }
                check != null -> {
                    flushParagraph()
                    out += inlineBlock(newId(), BlockType.CHECKLIST, check.groupValues[2], level = indent, checked = check.groupValues[1].isNotBlank())
                    continue
                }
                bullet != null -> {
                    flushParagraph()
                    out += inlineBlock(newId(), BlockType.BULLET, bullet.groupValues[1], level = indent)
                    continue
                }
                numbered != null -> {
                    flushParagraph()
                    out += inlineBlock(newId(), BlockType.NUMBERED, numbered.groupValues[1], level = indent)
                    continue
                }
            }
            if (trimmed.startsWith(">")) {
                flushParagraph()
                out += inlineBlock(newId(), BlockType.QUOTE, trimmed.removePrefix(">").trim())
                continue
            }
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(trimmed)
        }
        code?.let { out += TypedBlock(newId(), BlockType.CODE, it.toString().trimEnd('\n'), language = codeLang) }
        flushParagraph()
        return out
    }

    /** Two spaces (or one tab) per nesting level, capped at 3. */
    private fun indentLevel(line: String): Int {
        var spaces = 0
        for (ch in line) {
            spaces += when (ch) { ' ' -> 1; '\t' -> 2; else -> break }
        }
        return (spaces / 2).coerceAtMost(3)
    }

    private fun inlineBlock(id: String, type: BlockType, text: String, level: Int = 0, checked: Boolean = false): TypedBlock {
        val (plain, spans) = inline(text)
        return TypedBlock(id = id, type = type, text = plain, spans = spans, level = level, checked = checked)
    }

    /** Strips inline markers, returning the plain text and the formatting spans over it. */
    fun inline(text: String): Pair<String, List<TextSpan>> {
        val sb = StringBuilder()
        val spans = ArrayList<TextSpan>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        val start = sb.length
                        sb.append(text, i + 1, end)
                        spans += TextSpan(start, sb.length, code = true)
                        i = end + 1
                        continue
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    var end = text.indexOf(marker, i + 2)
                    // "***" closing "**...*...***": the bold marker is the last two.
                    while (end > 0 && end + 2 < text.length && text[end + 2] == marker[0]) end++
                    if (end > i + 2) {
                        val (inner, innerSpans) = inline(text.substring(i + 2, end))
                        val start = sb.length
                        sb.append(inner)
                        innerSpans.forEach { spans += it.copy(start = it.start + start, end = it.end + start) }
                        spans += TextSpan(start, sb.length, bold = true)
                        i = end + 2
                        continue
                    }
                }
                c == '*' || c == '_' -> {
                    val end = text.indexOf(c, i + 1)
                    // Avoid treating snake_case words or lone asterisks as emphasis.
                    val opensWord = i + 1 < text.length && !text[i + 1].isWhitespace()
                    val boundaryBefore = i == 0 || !text[i - 1].isLetterOrDigit()
                    if (end > i + 1 && opensWord && boundaryBefore && !text[end - 1].isWhitespace() &&
                        (end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
                    ) {
                        val start = sb.length
                        sb.append(text, i + 1, end)
                        spans += TextSpan(start, sb.length, italic = true)
                        i = end + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString() to spans
    }
}
