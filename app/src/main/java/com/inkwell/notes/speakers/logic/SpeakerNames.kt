package com.inkwell.notes.speakers.logic

/**
 * Display names written into transcripts, exports, search and sync. A named voice shows
 * the person's name; an unnamed one "Speaker N" from its label ("S2" -> "Speaker 2"). Pure.
 */
object SpeakerNames {
    const val UNNAMED_PREFIX = "Speaker"
    const val MAX_NAME_LENGTH = 80

    fun display(label: String, personName: String?): String =
        clean(personName) ?: unnamed(label)

    fun unnamed(label: String): String {
        val n = number(label)
        return if (n != null) "$UNNAMED_PREFIX $n" else "$UNNAMED_PREFIX ${label.trim()}".trimEnd()
    }

    /** The number of a "S<n>" label, or null. */
    fun number(label: String): Int? {
        val t = label.trim()
        if (t.length < 2 || (t[0] != 'S' && t[0] != 's')) return null
        return t.substring(1).toIntOrNull()?.takeIf { it > 0 }
    }

    /** The label after the highest "S<n>" in [labels]. */
    fun nextLabel(labels: Collection<String>): String = "S${(labels.mapNotNull { number(it) }.maxOrNull() ?: 0) + 1}"

    /**
     * A typed name made safe to store: whitespace collapsed, control and invisible format
     * characters removed, at most [MAX_NAME_LENGTH] characters. Null when nothing is left.
     * The zero-width joiner and non-joiner stay: they shape letters in Persian, Arabic and
     * Indic names and hold emoji sequences together.
     */
    fun clean(name: String?): String? {
        if (name == null) return null
        val sb = StringBuilder()
        var space = false
        for (ch in name) {
            val type = Character.getType(ch)
            when {
                ch.isWhitespace() || Character.isSpaceChar(ch) -> space = true
                ch == ZWNJ || ch == ZWJ -> if (sb.isNotEmpty() && !space) sb.append(ch)
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                else -> {
                    if (space && sb.isNotEmpty()) sb.append(' ')
                    space = false
                    sb.append(ch)
                }
            }
        }
        var s = sb.toString().trimEnd(ZWNJ, ZWJ)
        if (s.length > MAX_NAME_LENGTH) {
            var cut = MAX_NAME_LENGTH
            // Never split a surrogate pair.
            if (Character.isHighSurrogate(s[cut - 1])) cut--
            s = s.substring(0, cut).trimEnd().trimEnd(ZWNJ, ZWJ)
        }
        return s.ifEmpty { null }
    }

    private const val ZWNJ = '‌'
    private const val ZWJ = '‍'
}

/** Stable colours for people and voices (indices into the speaker palette). Pure. */
object SpeakerColors {
    const val PALETTE = 8

    /**
     * The lowest palette index nobody uses; when all are taken, the least used one (lowest
     * first), so colours cycle evenly.
     */
    fun next(used: Collection<Int>): Int {
        val counts = IntArray(PALETTE)
        for (u in used) counts[Math.floorMod(u, PALETTE)]++
        var best = 0
        for (i in 1 until PALETTE) if (counts[i] < counts[best]) best = i
        return best
    }

    /**
     * Colours for the voices of one recording: a named voice keeps its person's colour; unnamed
     * voices take, in label order, colours not yet used in this recording.
     */
    fun forRecording(labels: List<String>, personColor: Map<String, Int?>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val used = ArrayList<Int>()
        for (l in labels) personColor[l]?.let { out[l] = Math.floorMod(it, PALETTE); used += it }
        val ordered = labels.filter { it !in out }.sortedWith(compareBy<String> { SpeakerNames.number(it) ?: Int.MAX_VALUE }.thenBy { it })
        for (l in ordered) {
            val c = next(used)
            out[l] = c
            used += c
        }
        return out
    }
}
