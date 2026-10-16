package com.inkwell.notes.ocr.text

/** Splits long text into readable passages at paragraph, sentence or word boundaries. */
object TextSplitter {
    /**
     * Paragraph-sized passages of at most [max] characters: blank-line separated paragraphs,
     * small neighbours merged up to [target], oversized ones cut with [split].
     */
    fun passages(text: String, target: Int = 600, max: Int = 1_000): List<String> {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { out += current.toString(); current.setLength(0) }
        }
        for (p in paragraphs) {
            if (p.length > max) {
                flush()
                out += split(p, target, max)
                continue
            }
            if (current.isNotEmpty() && current.length + 2 + p.length > target) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)
        }
        flush()
        return out
    }

    /**
     * Cuts [text] into pieces of at most [max] characters, preferring to end a piece after a
     * sentence (". ", "? ", "! ", line break) once it reaches [target], then at a space.
     */
    fun split(text: String, target: Int = 600, max: Int = 900): List<String> {
        val t = text.trim()
        if (t.length <= max) return if (t.isEmpty()) emptyList() else listOf(t)
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            if (t.length - start <= max) {
                out += t.substring(start).trim()
                break
            }
            val hardEnd = start + max
            var cut = -1
            // Sentence end between target and max.
            var i = hardEnd - 1
            val lowest = (start + target / 2).coerceAtMost(hardEnd - 1)
            while (i > lowest) {
                val c = t[i]
                if (c == '\n' || ((c == ' ') && i > 0 && t[i - 1] in SENTENCE_END)) { cut = i; break }
                i--
            }
            if (cut < 0) {
                i = hardEnd - 1
                while (i > lowest) {
                    if (t[i].isWhitespace()) { cut = i; break }
                    i--
                }
            }
            // No boundary at all (a long URL, an emoji run): cut hard, but never through a surrogate pair.
            if (cut < 0) cut = IndexText.safeCut(t, hardEnd).takeIf { it > start } ?: hardEnd
            val piece = t.substring(start, cut).trim()
            if (piece.isNotEmpty()) out += piece
            start = cut
            while (start < t.length && t[start].isWhitespace()) start++
        }
        return out.filter { it.isNotEmpty() }
    }

    private val SENTENCE_END = charArrayOf('.', '?', '!', ';', ':')
}
