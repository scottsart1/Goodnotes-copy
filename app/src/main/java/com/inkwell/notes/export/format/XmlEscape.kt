package com.inkwell.notes.export.format

/** XML 1.0 text and attribute escaping for the OOXML writer. */
object XmlEscape {
    /**
     * Escapes markup characters and drops code points XML 1.0 forbids (C0 controls other
     * than tab/newline/carriage return, unpaired surrogates, U+FFFE and U+FFFF), which
     * would otherwise make Word refuse the whole file.
     */
    fun text(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1])
                        i++
                    }
                }
                Character.isLowSurrogate(c) -> Unit
                isAllowed(c) -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Attribute values use the same escaping (quotes included). */
    fun attr(s: String): String = text(s)

    /** Removes characters XML cannot carry without escaping anything else. */
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                Character.isHighSurrogate(c) -> if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                Character.isLowSurrogate(c) -> Unit
                isAllowed(c) -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun isAllowed(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\r' || (c >= ' ' && c != '￾' && c != '￿')
}
