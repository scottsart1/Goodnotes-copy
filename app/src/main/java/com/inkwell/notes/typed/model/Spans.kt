package com.inkwell.notes.typed.model

import com.inkwell.notes.core.model.TextSpan

/** Inline styles that can be toggled from the formatting toolbar. Links are handled separately. */
enum class InlineStyle { BOLD, ITALIC, UNDERLINE, STRIKE, CODE }

/** The full inline formatting of one character. */
data class Marks(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
) {
    val isPlain: Boolean get() = this == PLAIN

    fun has(style: InlineStyle): Boolean = when (style) {
        InlineStyle.BOLD -> bold
        InlineStyle.ITALIC -> italic
        InlineStyle.UNDERLINE -> underline
        InlineStyle.STRIKE -> strike
        InlineStyle.CODE -> code
    }

    fun with(style: InlineStyle, on: Boolean): Marks = when (style) {
        InlineStyle.BOLD -> copy(bold = on)
        InlineStyle.ITALIC -> copy(italic = on)
        InlineStyle.UNDERLINE -> copy(underline = on)
        InlineStyle.STRIKE -> copy(strike = on)
        InlineStyle.CODE -> copy(code = on)
    }

    companion object {
        val PLAIN = Marks()
    }
}

/**
 * Pure operations on [TextSpan] lists. Spans are kept normalised: sorted, non-overlapping,
 * non-empty, clamped to the text, with adjacent runs of identical formatting merged.
 * Internally every operation works on a per-character [Marks] array, which keeps the
 * offsets exact under any combination of inserts, deletes and toggles.
 */
object SpanOps {

    fun marksOf(spans: List<TextSpan>, length: Int): Array<Marks> {
        val out = Array(length) { Marks.PLAIN }
        for (s in spans) {
            val start = s.start.coerceIn(0, length)
            val end = s.end.coerceIn(start, length)
            for (i in start until end) {
                val m = out[i]
                out[i] = Marks(
                    bold = m.bold || s.bold,
                    italic = m.italic || s.italic,
                    underline = m.underline || s.underline,
                    strike = m.strike || s.strike,
                    code = m.code || s.code,
                    link = s.link ?: m.link,
                )
            }
        }
        return out
    }

    fun spansOf(marks: Array<Marks>): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        var i = 0
        while (i < marks.size) {
            val m = marks[i]
            var j = i + 1
            while (j < marks.size && marks[j] == m) j++
            if (!m.isPlain) {
                out += TextSpan(i, j, m.bold, m.italic, m.underline, m.strike, m.code, m.link)
            }
            i = j
        }
        return out
    }

    fun normalize(spans: List<TextSpan>, length: Int): List<TextSpan> = spansOf(marksOf(spans, length))

    /**
     * Adjusts spans after the text [start, start + deletedLength) of a text of length
     * [oldLength] was replaced by [insertedLength] new characters. Inserted characters take
     * [insertMarks] when given (pending toolbar formatting), otherwise they continue the
     * formatting of the character before (bold/italic/underline/strike), and stay inside an
     * inline code run or link only when both neighbours belong to it.
     */
    fun replace(
        spans: List<TextSpan>,
        oldLength: Int,
        start: Int,
        deletedLength: Int,
        insertedLength: Int,
        insertMarks: Marks? = null,
    ): List<TextSpan> {
        val s = start.coerceIn(0, oldLength)
        val del = deletedLength.coerceIn(0, oldLength - s)
        val ins = insertedLength.coerceAtLeast(0)
        val old = marksOf(spans, oldLength)
        val inherited = insertMarks ?: inheritedMarks(old, s, del)
        val newLength = oldLength - del + ins
        val out = Array(newLength) { Marks.PLAIN }
        for (i in 0 until s) out[i] = old[i]
        for (i in 0 until ins) out[s + i] = inherited
        for (i in s + del until oldLength) out[i - del + ins] = old[i]
        return spansOf(out)
    }

    /** Formatting that text typed at [start] (replacing [deletedLength] chars) would get. */
    fun inheritedMarks(old: Array<Marks>, start: Int, deletedLength: Int = 0): Marks {
        val before = old.getOrNull(start - 1)
        val after = old.getOrNull(start + deletedLength)
        if (before == null) {
            // At the very start: continue the basic styles of the following text.
            return after?.copy(code = false, link = null) ?: Marks.PLAIN
        }
        val code = before.code && after?.code == true
        val link = if (before.link != null && before.link == after?.link) before.link else null
        return before.copy(code = code, link = link)
    }

    /** Marks that typing at a collapsed caret [offset] would produce. */
    fun marksAt(spans: List<TextSpan>, length: Int, offset: Int): Marks =
        inheritedMarks(marksOf(spans, length), offset.coerceIn(0, length))

    /** True when every character of [start, end) has [style]; for a collapsed range, the caret's formatting. */
    fun isActive(spans: List<TextSpan>, length: Int, start: Int, end: Int, style: InlineStyle): Boolean {
        val a = minOf(start, end).coerceIn(0, length)
        val b = maxOf(start, end).coerceIn(0, length)
        if (a == b) return marksAt(spans, length, a).has(style)
        val marks = marksOf(spans, length)
        for (i in a until b) if (!marks[i].has(style)) return false
        return true
    }

    /** Toggles [style] over [start, end): removes it when the whole range has it, otherwise applies it. */
    fun toggle(spans: List<TextSpan>, length: Int, start: Int, end: Int, style: InlineStyle): List<TextSpan> {
        val a = minOf(start, end).coerceIn(0, length)
        val b = maxOf(start, end).coerceIn(0, length)
        if (a == b) return normalize(spans, length)
        val on = !isActive(spans, length, a, b, style)
        val marks = marksOf(spans, length)
        for (i in a until b) marks[i] = marks[i].with(style, on)
        return spansOf(marks)
    }

    /** Sets (or with null removes) a link over [start, end). */
    fun setLink(spans: List<TextSpan>, length: Int, start: Int, end: Int, url: String?): List<TextSpan> {
        val a = minOf(start, end).coerceIn(0, length)
        val b = maxOf(start, end).coerceIn(0, length)
        val marks = marksOf(spans, length)
        val value = url?.takeIf { it.isNotBlank() }
        for (i in a until b) marks[i] = marks[i].copy(link = value)
        return spansOf(marks)
    }

    /** The whole link run touching [offset] (inside it, or right after its last character). */
    fun linkRangeAt(spans: List<TextSpan>, length: Int, offset: Int): Pair<IntRange, String>? {
        val marks = marksOf(spans, length)
        val idx = when {
            offset in 0 until length && marks[offset].link != null -> offset
            offset - 1 in 0 until length && marks[offset - 1].link != null -> offset - 1
            else -> return null
        }
        val url = marks[idx].link ?: return null
        var a = idx
        while (a > 0 && marks[a - 1].link == url) a--
        var b = idx + 1
        while (b < length && marks[b].link == url) b++
        return (a until b) to url
    }

    /** Spans of the text [start, end), re-based to start at 0. */
    fun slice(spans: List<TextSpan>, length: Int, start: Int, end: Int): List<TextSpan> {
        val a = start.coerceIn(0, length)
        val b = end.coerceIn(a, length)
        val marks = marksOf(spans, length)
        return spansOf(Array(b - a) { marks[a + it] })
    }

    /** Spans of two texts joined end to end. */
    fun concat(first: List<TextSpan>, firstLength: Int, second: List<TextSpan>, secondLength: Int): List<TextSpan> {
        val a = marksOf(first, firstLength)
        val b = marksOf(second, secondLength)
        return spansOf(Array(firstLength + secondLength) { if (it < firstLength) a[it] else b[it - firstLength] })
    }

    /** Removes the first [count] characters' formatting and shifts the rest left. */
    fun dropPrefix(spans: List<TextSpan>, length: Int, count: Int): List<TextSpan> =
        slice(spans, length, count.coerceIn(0, length), length)
}
