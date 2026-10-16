package com.inkwell.notes.core.naming

/** How much a piece of text says about a document's subject. */
enum class SegmentKind(val weight: Double) {
    /** Typed headings, large or bold text boxes, tags. */
    HEADING(3.0),
    /** The first line of a page or document. */
    LEAD(2.0),
    BODY(1.0),
}

data class NamingSegment(val text: String, val kind: SegmentKind)

/** Everything known about a document's text, gathered for topic inference. */
data class NamingContent(val segments: List<NamingSegment>) {
    val isBlank: Boolean get() = segments.all { it.text.isBlank() }

    companion object {
        val EMPTY = NamingContent(emptyList())

        /** Plain text: the first non-blank line is the lead, the rest body. */
        fun fromPlainText(text: String, headings: List<String> = emptyList()): NamingContent {
            val segments = ArrayList<NamingSegment>()
            headings.filter { it.isNotBlank() }.mapTo(segments) { NamingSegment(it, SegmentKind.HEADING) }
            var leadDone = false
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                segments += NamingSegment(line, if (leadDone) SegmentKind.BODY else SegmentKind.LEAD)
                leadDone = true
            }
            return NamingContent(segments)
        }
    }
}
