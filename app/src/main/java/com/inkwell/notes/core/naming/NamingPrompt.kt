package com.inkwell.notes.core.naming

/** The strict prompt used for AI topic inference, and parsing of the model's reply. */
object NamingPrompt {
    const val MAX_CONTENT_CHARS = 6_000
    const val MAX_OUTPUT_TOKENS = 32
    const val INSUFFICIENT = "INSUFFICIENT"

    val SYSTEM: String = """
        You name notebooks. Read the notes and reply with ONLY a concise topic of 1 to 5 words in Title Case.
        No date, no quotes, no punctuation, no explanation.
        If there is not enough meaningful content to tell what the notes are about, reply with exactly $INSUFFICIENT.
        Prefer specific subject terms (for example Monetary Policy, Organic Chemistry, Q3 Hiring Plan) over generic
        words such as Notes, Meeting, Lecture, Ideas or Summary, unless that is genuinely the subject.
        The text may contain handwriting-recognition errors; ignore fragments that make no sense.
    """.trimIndent()

    /**
     * The user message: headings first, then the repeated key terms, then the text in reading
     * order, cut at a line or word boundary so the whole message stays within [maxChars].
     */
    fun userMessage(content: NamingContent, keyTerms: List<String>, maxChars: Int = MAX_CONTENT_CHARS): String {
        val sb = StringBuilder()
        fun remaining() = maxChars - sb.length

        val headings = content.segments.filter { it.kind == SegmentKind.HEADING }
            .map { collapse(it.text) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (headings.isNotEmpty()) {
            sb.append("Headings:\n")
            for (h in headings) {
                val line = "- $h\n"
                if (line.length > remaining()) break
                sb.append(line)
            }
            sb.append('\n')
        }
        if (keyTerms.isNotEmpty()) {
            val line = "Repeated terms: ${keyTerms.joinToString(", ")}\n\n"
            if (line.length <= remaining()) sb.append(line)
        }
        val header = "Notes:\n"
        if (remaining() > header.length) {
            sb.append(header)
            for (segment in content.segments) {
                if (segment.kind == SegmentKind.HEADING) continue
                val text = collapse(segment.text)
                if (text.isEmpty()) continue
                val room = remaining() - 1
                if (room <= 0) break
                if (text.length <= room) {
                    sb.append(text).append('\n')
                } else {
                    val cut = text.take(room).substringBeforeLast(' ', "")
                    if (cut.isNotBlank()) sb.append(cut).append('\n')
                    break
                }
            }
        }
        return sb.toString().trimEnd().take(maxChars)
    }

    sealed interface Reply {
        data class Topic(val topic: String) : Reply
        data object Insufficient : Reply
        /** Empty, generic ("Notes") or otherwise unusable answer. */
        data object Unusable : Reply
    }

    fun parseReply(raw: String): Reply {
        var line = raw.replace(THINK_BLOCK, "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return Reply.Unusable
        line = line.replace("**", "").replace("__", "").trim()
        line = line.trim('"', '\'', '`', '“', '”', '‘', '’', '*', '#', ' ')
        if (INSUFFICIENT_REPLY.containsMatchIn(line)) return Reply.Insufficient
        line = line.replace(LABEL, "").replace(LEADING_DATE, "").trim()
        val topic = DateNaming.cleanTopic(line) ?: return Reply.Unusable
        return Reply.Topic(topic)
    }

    private fun collapse(s: String) = s.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
    private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")
    private val INSUFFICIENT_REPLY = Regex("^insufficient\\b", RegexOption.IGNORE_CASE)
    private val LABEL = Regex("^(topic|title|name|subject)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    private val LEADING_DATE = Regex("^(\\d{8}|\\d{4}-\\d{2}-\\d{2}|\\d{4}/\\d{2}/\\d{2})[\\s_\\-:]*")
}
