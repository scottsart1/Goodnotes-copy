package com.inkwell.notes.search

import com.inkwell.notes.core.model.TranscriptSegment

/**
 * What the search index stores for a transcript block: "Priya: text" when the block has a
 * person's name, so searching a person finds the blocks where they speak. The excerpt and its
 * highlight are computed on this same text, and each row keeps its recording and start time,
 * so a hit still opens the recording at that moment.
 */
object TranscriptSearchText {
    /** Provisional names of voices nobody has named ("Speaker 2"): not a person, so not indexed. */
    private val placeholder = Regex("^speaker\\s*\\d+$", RegexOption.IGNORE_CASE)

    /** The person's name worth indexing for [segment], or null. */
    fun personName(segment: TranscriptSegment): String? =
        segment.speaker?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() && !placeholder.matches(it) }

    /** "Name: text", or the text alone when no person is named (or the text already starts with the name). */
    fun indexText(segment: TranscriptSegment): String {
        val text = segment.text.trim()
        val name = personName(segment) ?: return text
        if (text.isEmpty()) return text
        if (text.startsWith("$name:", ignoreCase = true)) return text
        return "$name: $text"
    }

    /** [segments] as the index stores them; timing and recording labels untouched. */
    fun forIndex(segments: List<TranscriptSegment>): List<TranscriptSegment> =
        segments.map { s -> indexText(s).let { if (it == s.text) s else s.copy(text = it) } }
}
