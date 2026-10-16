package com.inkwell.notes.ocr.text

/** A vision model's transcription, cleaned up, with the places it could not read. */
data class ParsedTranscription(
    val text: String,
    val paragraphs: List<String>,
    /** "[illegible]" and "[?word?]" markers in order of appearance, kept verbatim. */
    val uncertain: List<String>,
)

/**
 * Parses vision OCR output. The model is told to write [illegible] for unreadable text and
 * [?word?] for uncertain readings; those markers stay in the text (nothing is guessed or
 * filled in) and are also collected so the UI can point them out.
 */
object AiTranscription {
    private const val MAX_MARKERS = 200
    private val MARKER = Regex("""\[(?:illegible|\?[^\[\]\n]{1,80}\?)]""", RegexOption.IGNORE_CASE)
    private val FENCE_START = Regex("""^```[A-Za-z0-9_-]*[ \t]*\n""")
    private val FENCE_END = Regex("""\n?```\s*$""")

    fun parse(raw: String): ParsedTranscription {
        val text = stripFence(raw.replace("\r\n", "\n")).trim()
        val uncertain = MARKER.findAll(text).map { it.value }.take(MAX_MARKERS).toList()
        val paragraphs = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        return ParsedTranscription(text, paragraphs, uncertain)
    }

    /** Removes one Markdown code fence wrapped around the whole answer. */
    fun stripFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        val start = FENCE_START.find(t) ?: return t
        val body = t.substring(start.range.last + 1)
        val end = FENCE_END.find(body) ?: return t
        return body.substring(0, end.range.first)
    }
}
