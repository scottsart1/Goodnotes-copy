package com.inkwell.notes.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ai.context.Citations
import com.inkwell.notes.ui.theme.Inkwell

/** Light Markdown model: enough for assistant answers, nothing that could run or load content. */
object Markdown {
    sealed interface Block {
        data class Paragraph(val text: String) : Block
        data class Heading(val level: Int, val text: String) : Block
        data class Bullets(val items: List<Item>) : Block
        data class Numbered(val items: List<Item>) : Block
        data class Code(val language: String?, val code: String) : Block
        data class Math(val tex: String) : Block
        data class Quote(val text: String) : Block
        data class Table(val rows: List<List<String>>) : Block
        data object Rule : Block
    }

    data class Item(val text: String, val indent: Int = 0, val number: Int? = null)

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val math: Boolean = false,
        /** Source citation like "p.3" (without brackets). */
        val citation: String? = null,
    )

    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,4})[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(markdown: String): List<Block> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<Block>()
        var i = 0
        fun isBlockStart(l: String): Boolean {
            val t = l.trim()
            return t.startsWith("```") || t.startsWith("$$") || t == "\\[" || heading.matches(t) || bullet.matches(l) ||
                numbered.matches(l) || t.startsWith(">") || t.startsWith("|") || rule.matches(l)
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.isEmpty() -> i++
                t.startsWith("```") -> {
                    val lang = t.removePrefix("```").trim().ifEmpty { null }
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                    i++ // closing fence (or end)
                    out += Block.Code(lang, code.joinToString("\n").trimEnd())
                }
                t.startsWith("$$") -> {
                    val rest = t.removePrefix("$$")
                    if (rest.endsWith("$$") && rest.length >= 2) {
                        out += Block.Math(rest.removeSuffix("$$").trim())
                        i++
                    } else {
                        val tex = ArrayList<String>()
                        if (rest.isNotBlank()) tex += rest
                        i++
                        while (i < lines.size && !lines[i].contains("$$")) { tex += lines[i]; i++ }
                        if (i < lines.size) {
                            lines[i].substringBefore("$$").takeIf { it.isNotBlank() }?.let { tex += it }
                            i++
                        }
                        out += Block.Math(tex.joinToString("\n").trim())
                    }
                }
                t == "\\[" -> {
                    val tex = ArrayList<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != "\\]") { tex += lines[i]; i++ }
                    i++
                    out += Block.Math(tex.joinToString("\n").trim())
                }
                heading.matches(t) -> {
                    val m = heading.matchEntire(t)!!
                    out += Block.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
                    i++
                }
                rule.matches(line) -> { out += Block.Rule; i++ }
                bullet.matches(line) -> {
                    val items = ArrayList<Item>()
                    while (i < lines.size) {
                        val m = bullet.matchEntire(lines[i])
                        if (m != null) {
                            items += Item(m.groupValues[2], indentLevel(m.groupValues[1]))
                            i++
                        } else if (items.isNotEmpty() && lines[i].isNotBlank() && lines[i].startsWith("  ") && !isBlockStart(lines[i])) {
                            val last = items.removeAt(items.lastIndex)
                            items += last.copy(text = last.text + "\n" + lines[i].trim())
                            i++
                        } else break
                    }
                    out += Block.Bullets(items)
                }
                numbered.matches(line) -> {
                    val items = ArrayList<Item>()
                    while (i < lines.size) {
                        val m = numbered.matchEntire(lines[i])
                        if (m != null) {
                            items += Item(m.groupValues[3], indentLevel(m.groupValues[1]), m.groupValues[2].toIntOrNull())
                            i++
                        } else if (items.isNotEmpty() && lines[i].isNotBlank() && lines[i].startsWith("  ") && !isBlockStart(lines[i])) {
                            val last = items.removeAt(items.lastIndex)
                            items += last.copy(text = last.text + "\n" + lines[i].trim())
                            i++
                        } else break
                    }
                    out += Block.Numbered(items)
                }
                t.startsWith(">") -> {
                    val q = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q += lines[i].trim().removePrefix(">").removePrefix(" "); i++ }
                    out += Block.Quote(q.joinToString("\n"))
                }
                t.startsWith("|") -> {
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i].trim()
                        if (!tableSep.matches(row)) rows += row.trim('|').split('|').map { it.trim() }
                        i++
                    }
                    out += Block.Table(rows)
                }
                else -> {
                    val para = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() && (para.isEmpty() || !isBlockStart(lines[i]))) { para += lines[i].trim(); i++ }
                    out += Block.Paragraph(para.joinToString("\n"))
                }
            }
        }
        return out
    }

    private fun indentLevel(ws: String): Int = (ws.replace("\t", "    ").length / 2).coerceAtMost(4)

    /** Inline spans: **bold**, *italic*, `code`, $math$, \(math\), [p.3] citations, [text](url) as text. */
    fun inline(text: String): List<Span> {
        val out = ArrayList<Span>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        fun flush() {
            if (buf.isNotEmpty()) { out += Span(buf.toString(), bold, italic); buf.setLength(0) }
        }
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < n && text[i + 1] == '(' -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end > i + 2) { flush(); out += Span(text.substring(i + 2, end), math = true); i = end + 2 } else { buf.append(c); i++ }
                }
                c == '\\' && i + 1 < n && text[i + 1] in "*_`$[]\\" -> { buf.append(text[i + 1]); i += 2 }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) { flush(); out += Span(text.substring(i + 1, end), code = true); i = end + 1 } else { buf.append(c); i++ }
                }
                c == '$' -> {
                    val double = i + 1 < n && text[i + 1] == '$'
                    val open = if (double) 2 else 1
                    val end = if (double) text.indexOf("$$", i + 2) else findInlineMathEnd(text, i + 1)
                    val inner = if (end > i + open) text.substring(i + open, end) else ""
                    if (inner.isNotBlank() && (double || (!inner.first().isWhitespace() && !inner.last().isWhitespace()))) {
                        flush(); out += Span(inner.trim(), math = true); i = end + open
                    } else { buf.append(c); i++ }
                }
                c == '*' && i + 1 < n && text[i + 1] == '*' || c == '_' && i + 1 < n && text[i + 1] == '_' -> {
                    val marker = text.substring(i, i + 2)
                    if (bold || text.indexOf(marker, i + 2) > i + 2) { flush(); bold = !bold } else buf.append(marker)
                    i += 2
                }
                c == '*' || c == '_' -> {
                    val prev = if (i > 0) text[i - 1] else ' '
                    val next = if (i + 1 < n) text[i + 1] else ' '
                    val canOpen = !next.isWhitespace() && (c == '*' || !prev.isLetterOrDigit())
                    val canClose = italic && !prev.isWhitespace() && (c == '*' || !next.isLetterOrDigit())
                    when {
                        canClose -> { flush(); italic = false }
                        !italic && canOpen && text.indexOf(c, i + 1) > i + 1 -> { flush(); italic = true }
                        else -> buf.append(c)
                    }
                    i++
                }
                c == '[' -> {
                    val end = text.indexOf(']', i + 1)
                    val inner = if (end > i) text.substring(i + 1, end) else null
                    when {
                        inner != null && end + 1 < n && text[end + 1] == '(' && text.indexOf(')', end + 2) > end -> {
                            // Link: keep the text, drop the target (no navigation from AI output).
                            buf.append(inner)
                            i = text.indexOf(')', end + 2) + 1
                        }
                        inner != null && !inner.contains('\n') && Citations.parseInner(inner)?.firstOrNull() != null -> {
                            // The normalised label ("p.3", not "p. 3" or "pp. 3-4") is what source chips are keyed by.
                            val label = Citations.parseInner(inner)!!.first().label
                            flush(); out += Span("[$inner]", citation = label); i = end + 1
                        }
                        else -> { buf.append(c); i++ }
                    }
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return out
    }

    private fun findInlineMathEnd(text: String, from: Int): Int {
        var j = text.indexOf('$', from)
        while (j > 0) {
            val after = if (j + 1 < text.length) text[j + 1] else ' '
            if (!after.isDigit() && text[j - 1] != '\\') return j
            j = text.indexOf('$', j + 1)
        }
        return -1
    }

    /** Plain text for inserting into a page: no emphasis markers, bullets as "•", math and citations kept. */
    fun toPlainText(markdown: String): String {
        fun plain(s: String) = inline(s).joinToString("") { sp -> if (sp.math) "$" + sp.text + "$" else sp.text }
        return parse(markdown).joinToString("\n\n") { b ->
            when (b) {
                is Block.Paragraph -> plain(b.text)
                is Block.Heading -> plain(b.text)
                is Block.Bullets -> b.items.joinToString("\n") { "  ".repeat(it.indent) + "• " + plain(it.text) }
                is Block.Numbered -> b.items.mapIndexed { idx, it -> "  ".repeat(it.indent) + "${it.number ?: idx + 1}. " + plain(it.text) }.joinToString("\n")
                is Block.Code -> b.code
                is Block.Math -> "$$" + b.tex + "$$"
                is Block.Quote -> b.text.lines().joinToString("\n") { "> " + plain(it) }
                is Block.Table -> b.rows.joinToString("\n") { r -> r.joinToString("  |  ") { plain(it) } }
                Block.Rule -> "———"
            }
        }.trim()
    }
}

// ---------------------------------------------------------------------------------------------
// Rendering

@Composable
internal fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Inkwell.colors.ink,
    onCitation: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    val c = Inkwell.colors
    val body = MaterialTheme.typography.bodyMedium.copy(color = color)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b) {
                is Markdown.Block.Paragraph -> Text(rememberInline(b.text, onCitation), style = body)
                is Markdown.Block.Heading -> Text(
                    rememberInline(b.text, onCitation),
                    style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall).copy(color = color),
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Markdown.Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (item in b.items) ListRow("•", item, body, onCitation)
                }
                is Markdown.Block.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    b.items.forEachIndexed { idx, item -> ListRow("${item.number ?: idx + 1}.", item, body, onCitation) }
                }
                is Markdown.Block.Code -> MonoBlock(b.code)
                is Markdown.Block.Math -> MonoBlock(b.tex)
                is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(c.outline))
                    Text(rememberInline(b.text, onCitation), style = body.copy(color = c.inkMuted), modifier = Modifier.padding(start = 10.dp))
                }
                is Markdown.Block.Table -> TableBlock(b.rows, body, onCitation)
                Markdown.Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
            }
        }
    }
}

@Composable
private fun ListRow(marker: String, item: Markdown.Item, style: androidx.compose.ui.text.TextStyle, onCitation: ((String) -> Unit)?) {
    Row(Modifier.padding(start = (item.indent * 16).dp)) {
        Text(marker, style = style.copy(color = Inkwell.colors.inkMuted), modifier = Modifier.widthIn(min = 18.dp))
        Text(rememberInline(item.text, onCitation), style = style)
    }
}

@Composable
private fun MonoBlock(text: String) {
    val c = Inkwell.colors
    Box(
        Modifier.fillMaxWidth()
            .background(c.surfaceAlt, RoundedCornerShape(8.dp))
            .border(1.dp, c.outline, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = c.ink))
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>, style: androidx.compose.ui.text.TextStyle, onCitation: ((String) -> Unit)?) {
    val c = Inkwell.colors
    val cols = rows.maxOfOrNull { it.size } ?: 0
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.outline, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.background(if (r == 0) c.surfaceAlt else Color.Transparent)) {
                for (col in 0 until cols) {
                    Text(
                        rememberInline(row.getOrElse(col) { "" }, onCitation),
                        style = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                        modifier = Modifier.widthIn(min = 72.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (r < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        }
    }
}

@Composable
private fun rememberInline(text: String, onCitation: ((String) -> Unit)?): AnnotatedString {
    val c = Inkwell.colors
    val callback by rememberUpdatedState(onCitation)
    return remember(text, c, onCitation != null) {
        val spans = Markdown.inline(text)
        buildAnnotatedString {
            for (s in spans) {
                when {
                    s.citation != null -> {
                        val style = SpanStyle(color = c.accent, fontWeight = FontWeight.Medium)
                        if (onCitation != null) {
                            withLink(LinkAnnotation.Clickable(tag = s.citation, styles = TextLinkStyles(style)) { callback?.invoke(s.citation) }) { append(s.text) }
                        } else withStyle(style) { append(s.text) }
                    }
                    s.code || s.math -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.surfaceAlt)) { append(s.text) }
                    else -> withStyle(
                        SpanStyle(
                            fontWeight = if (s.bold) FontWeight.SemiBold else null,
                            fontStyle = if (s.italic) FontStyle.Italic else null,
                        ),
                    ) { append(s.text) }
                }
            }
        }
    }
}
