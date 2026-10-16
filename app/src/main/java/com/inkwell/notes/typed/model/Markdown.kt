package com.inkwell.notes.typed.model

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock

/** A Markdown-style prefix typed at the start of a paragraph, e.g. "## " or "[] ". */
data class ShortcutMatch(
    val type: BlockType,
    /** Heading level 1..3, or 0. */
    val level: Int = 0,
    val checked: Boolean = false,
    val language: String? = null,
    /** Characters to remove from the start of the block. */
    val prefixLength: Int,
)

/** Detects block shortcuts while typing: "# ", "## ", "### ", "- ", "1. ", "[] ", "> ", "``` ", "--- ". */
object MarkdownShortcuts {
    private val numbered = Regex("^\\d{1,3}[.)] ")
    private val code = Regex("^```([A-Za-z0-9_+#.-]{0,20}) ")

    /**
     * Returns the shortcut whose prefix ends exactly at [caret] (the user just typed its
     * trailing space), or null. Only call this for PARAGRAPH blocks.
     */
    fun detect(text: String, caret: Int): ShortcutMatch? {
        if (caret <= 0 || caret > text.length || text[caret - 1] != ' ') return null
        val prefix = text.substring(0, caret)
        val m = when (prefix) {
            "# " -> ShortcutMatch(BlockType.HEADING, level = 1, prefixLength = 2)
            "## " -> ShortcutMatch(BlockType.HEADING, level = 2, prefixLength = 3)
            "### " -> ShortcutMatch(BlockType.HEADING, level = 3, prefixLength = 4)
            "- ", "* ", "+ " -> ShortcutMatch(BlockType.BULLET, prefixLength = 2)
            "[] " -> ShortcutMatch(BlockType.CHECKLIST, prefixLength = 3)
            "[ ] " -> ShortcutMatch(BlockType.CHECKLIST, prefixLength = 4)
            "[x] ", "[X] " -> ShortcutMatch(BlockType.CHECKLIST, checked = true, prefixLength = 4)
            "> " -> ShortcutMatch(BlockType.QUOTE, prefixLength = 2)
            "--- " -> ShortcutMatch(BlockType.DIVIDER, prefixLength = 4)
            else -> null
        }
        if (m != null) return m
        if (numbered.matches(prefix)) return ShortcutMatch(BlockType.NUMBERED, prefixLength = prefix.length)
        code.matchEntire(prefix)?.let { r ->
            return ShortcutMatch(BlockType.CODE, language = r.groupValues[1].ifEmpty { null }, prefixLength = prefix.length)
        }
        return null
    }
}

/** Text with inline formatting parsed from Markdown. */
data class StyledText(val text: String, val spans: List<TextSpan>)

/**
 * Converts Markdown (e.g. AI output) into typed blocks: headings, lists with indentation,
 * checklists, quotes, fenced code, tables, dividers and paragraphs, with inline
 * **bold**, *italic*, ~~strike~~, `code` and [links](url).
 */
object MarkdownBlocks {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val check = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val number = Regex("^(\\s*)\\d{1,4}[.)]\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^\\s*```\\s*([A-Za-z0-9_+#.-]*)\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(markdown: String, newId: () -> String = BlockOps::newId): List<TypedBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<TypedBlock>()
        val para = StringBuilder()

        fun flushPara() {
            if (para.isNotBlank()) {
                val st = parseInline(para.toString().trim())
                out += TypedBlock(newId(), BlockType.PARAGRAPH, st.text, st.spans)
            }
            para.setLength(0)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = fence.matchEntire(line)
            if (fenceMatch != null) {
                flushPara()
                val lang = fenceMatch.groupValues[1].ifEmpty { null }
                val body = ArrayList<String>()
                i++
                while (i < lines.size && fence.matchEntire(lines[i])?.groupValues?.get(1)?.isEmpty() != true) {
                    body += lines[i]; i++
                }
                i++ // closing fence (or end)
                out += TypedBlock(newId(), BlockType.CODE, body.joinToString("\n"), language = lang)
                continue
            }
            if (line.isBlank()) { flushPara(); i++; continue }
            if (line.trimStart().startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                val rows = ArrayList<List<String>>()
                rows += splitRow(line)
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += splitRow(lines[i]); i++ }
                out += TypedBlock(newId(), BlockType.TABLE, rows = TableOps.normalize(rows))
                continue
            }
            if (rule.matches(line)) { flushPara(); out += TypedBlock(newId(), BlockType.DIVIDER); i++; continue }
            val h = heading.matchEntire(line)
            val c = if (h == null) check.matchEntire(line) else null
            val b = if (h == null && c == null) bullet.matchEntire(line) else null
            val n = if (h == null && c == null && b == null) number.matchEntire(line) else null
            val q = if (h == null && c == null && b == null && n == null) quote.matchEntire(line) else null
            when {
                h != null -> {
                    flushPara()
                    val st = parseInline(h.groupValues[2].trim().trimEnd('#').trim())
                    out += TypedBlock(newId(), BlockType.HEADING, st.text, st.spans, level = h.groupValues[1].length.coerceAtMost(3))
                }
                c != null -> {
                    flushPara()
                    val st = parseInline(c.groupValues[3])
                    out += TypedBlock(newId(), BlockType.CHECKLIST, st.text, st.spans, level = indentLevel(c.groupValues[1]), checked = c.groupValues[2].isNotBlank())
                }
                b != null -> {
                    flushPara()
                    val st = parseInline(b.groupValues[2])
                    out += TypedBlock(newId(), BlockType.BULLET, st.text, st.spans, level = indentLevel(b.groupValues[1]))
                }
                n != null -> {
                    flushPara()
                    val st = parseInline(n.groupValues[2])
                    out += TypedBlock(newId(), BlockType.NUMBERED, st.text, st.spans, level = indentLevel(n.groupValues[1]))
                }
                q != null -> {
                    flushPara()
                    val st = parseInline(q.groupValues[1])
                    val prev = out.lastOrNull()
                    // Consecutive quote lines form one quote.
                    if (prev != null && prev.type == BlockType.QUOTE && i > 0 && quote.matches(lines[i - 1])) {
                        val sep = if (prev.text.isEmpty() || st.text.isEmpty()) "" else " "
                        val spans = SpanOps.concat(prev.spans, prev.text.length + sep.length, st.spans, st.text.length)
                        out[out.lastIndex] = prev.copy(text = prev.text + sep + st.text, spans = spans)
                    } else {
                        out += TypedBlock(newId(), BlockType.QUOTE, st.text, st.spans)
                    }
                }
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(line.trim())
                }
            }
            i++
        }
        flushPara()
        return out
    }

    private fun indentLevel(ws: String): Int {
        var width = 0
        for (ch in ws) width += if (ch == '\t') 4 else 1
        return (width / 2).coerceIn(0, BlockOps.MAX_INDENT)
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < s.length) {
            val ch = s[k]
            if (ch == '\\' && k + 1 < s.length && s[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (ch == '|') { cells += cur.toString().trim(); cur.setLength(0) } else cur.append(ch)
            k++
        }
        cells += cur.toString().trim()
        return cells.map { parseInline(it).text }
    }

    /** Parses inline Markdown into plain text plus spans. Unmatched markers are kept literally. */
    fun parseInline(src: String): StyledText {
        val text = StringBuilder()
        val marks = ArrayList<Marks>()
        var bold = false; var italic = false; var strike = false
        var i = 0
        fun emit(ch: Char, extra: Marks? = null) {
            text.append(ch)
            marks += extra ?: Marks(bold = bold, italic = italic, strike = strike)
        }
        while (i < src.length) {
            val ch = src[i]
            when {
                ch == '\\' && i + 1 < src.length && src[i + 1] in "\\`*_~[]()#|" -> { emit(src[i + 1]); i += 2 }
                ch == '`' -> {
                    val close = src.indexOf('`', i + 1)
                    if (close > i + 1) {
                        for (k in i + 1 until close) emit(src[k], Marks(bold = bold, italic = italic, strike = strike, code = true))
                        i = close + 1
                    } else { emit(ch); i++ }
                }
                src.startsWith("**", i) || src.startsWith("__", i) -> {
                    val marker = src.substring(i, i + 2)
                    if (bold || src.indexOf(marker, i + 2) > i + 2) { bold = !bold; i += 2 } else { emit(ch); emit(src[i + 1]); i += 2 }
                }
                src.startsWith("~~", i) -> {
                    if (strike || src.indexOf("~~", i + 2) > i + 2) { strike = !strike; i += 2 } else { emit('~'); emit('~'); i += 2 }
                }
                (ch == '*' || ch == '_') -> {
                    val wordInner = ch == '_' && i > 0 && src[i - 1].isLetterOrDigit() && i + 1 < src.length && src[i + 1].isLetterOrDigit()
                    if (!wordInner && (italic || src.indexOf(ch, i + 1) > i + 1)) { italic = !italic; i++ } else { emit(ch); i++ }
                }
                ch == '[' -> {
                    val closeText = src.indexOf("](", i + 1)
                    val closeUrl = if (closeText > 0) src.indexOf(')', closeText + 2) else -1
                    if (closeText > i && closeUrl > closeText + 2) {
                        val label = parseInline(src.substring(i + 1, closeText))
                        val url = src.substring(closeText + 2, closeUrl).trim()
                        val labelMarks = SpanOps.marksOf(label.spans, label.text.length)
                        for (k in label.text.indices) {
                            val m = labelMarks[k]
                            emit(label.text[k], m.copy(bold = m.bold || bold, italic = m.italic || italic, strike = m.strike || strike, link = url))
                        }
                        i = closeUrl + 1
                    } else { emit(ch); i++ }
                }
                else -> { emit(ch); i++ }
            }
        }
        return StyledText(text.toString(), SpanOps.spansOf(marks.toTypedArray()))
    }
}
