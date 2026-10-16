package com.inkwell.notes.audio.logic

import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.TranscriptSegment

/** One-tap AI actions over a recording's transcript. */
enum class TranscriptAction(val label: String, val needsPassage: Boolean = false, val needsNotes: Boolean = false) {
    SUMMARIZE("Summarize"),
    DECISIONS("Decisions"),
    KEY_TOPICS("Key topics"),
    ACTION_ITEMS("Action items"),
    REVISION_NOTES("Revision notes"),
    EXPLAIN("Explain a passage", needsPassage = true),
    COMPARE("Compare with my notes", needsNotes = true),
}

/** Builds the requests for [TranscriptAction]s. Pure; no Android or network. */
object TranscriptPrompts {
    const val TRUNCATION_NOTE = "[The transcript continues but was shortened to fit the model.]"

    val SYSTEM = """
        You help a student or professional work with a recording they made inside a note-taking app.
        Work only from the transcript (and the user's notes when they are provided). Never invent content.
        Transcript lines start with a [m:ss] timestamp; cite the timestamp of the passage you rely on, like [12:34].
        If speech is unclear, missing or ambiguous, say so instead of guessing.
        Answer in the language of the transcript. Use concise Markdown: short headings, bullet lists, no preamble.
    """.trimIndent()

    /** "[m:ss] text" lines, one per segment. */
    fun formatTranscript(segments: List<TranscriptSegment>): String =
        segments.joinToString("\n") { "[${AudioText.clock(it.startMs)}] ${it.text.trim()}" }

    /**
     * Keeps whole lines of [text] up to [maxChars], marking the cut with [TRUNCATION_NOTE].
     * Returns the text and whether it was shortened.
     */
    fun fit(text: String, maxChars: Int): Pair<String, Boolean> {
        if (text.length <= maxChars) return text to false
        val budget = (maxChars - TRUNCATION_NOTE.length - 1).coerceAtLeast(0)
        val cut = text.lastIndexOf('\n', budget).takeIf { it > 0 } ?: budget
        return text.substring(0, cut).trimEnd() + "\n" + TRUNCATION_NOTE to true
    }

    /** Character budget for the transcript given the model's context window (null = unknown). */
    fun transcriptBudget(contextTokens: Int?): Int {
        val ctx = contextTokens?.takeIf { it > 0 } ?: 32_000
        // ~3 characters per token, leaving room for instructions, notes and the answer.
        return ((ctx - 8_000).coerceAtLeast(4_000) * 3).coerceAtMost(600_000)
    }

    fun instruction(action: TranscriptAction): String = when (action) {
        TranscriptAction.SUMMARIZE ->
            "Summarize this recording: a two or three sentence overview, then the main points as bullets, each with its timestamp."
        TranscriptAction.DECISIONS ->
            "List every decision that was made, with who made it when that is stated and the timestamp. " +
                "Then list open questions that were left undecided. If no decisions were made, say so."
        TranscriptAction.KEY_TOPICS ->
            "List the key topics in the order they were discussed. For each: a short heading, one line on what was said, and the timestamp where it starts."
        TranscriptAction.ACTION_ITEMS ->
            "Extract the action items as a Markdown checklist (- [ ] ...). Include the owner and due date when mentioned, and the timestamp. If there are none, say so."
        TranscriptAction.REVISION_NOTES ->
            "Turn this recording into revision notes: a heading per topic, key definitions, formulas and examples as they were given, " +
                "and finish with five short self-test questions (answers below them)."
        TranscriptAction.EXPLAIN ->
            "Explain the selected passage in plain language: what it means, the terms it uses, and a short example. " +
                "Use the rest of the transcript only as context."
        TranscriptAction.COMPARE ->
            "Compare the recording with my notes. First list what was discussed but is missing from my notes, most important first, " +
                "each with its timestamp. Then list anything in my notes that disagrees with the recording. Keep it brief."
    }

    /**
     * The request for [action]. [passage] is the selected transcript excerpt (EXPLAIN);
     * [notes] is the document's recognised handwriting and typed text (COMPARE).
     */
    fun request(
        action: TranscriptAction,
        recordingTitle: String,
        segments: List<TranscriptSegment>,
        documentId: String?,
        contextTokens: Int?,
        passage: String? = null,
        notes: String? = null,
    ): AiRequest {
        val budget = transcriptBudget(contextTokens)
        val notesBudget = if (action == TranscriptAction.COMPARE) budget / 3 else 0
        val (transcript, _) = fit(formatTranscript(segments), budget - notesBudget)
        val body = buildString {
            append("Recording: ").append(recordingTitle.trim()).append("\n\n")
            append("<transcript>\n").append(transcript).append("\n</transcript>\n\n")
            if (action == TranscriptAction.COMPARE) {
                val (n, _) = fit(notes.orEmpty().trim(), notesBudget.coerceAtLeast(2_000))
                append("<my_notes>\n").append(n).append("\n</my_notes>\n\n")
            }
            if (action == TranscriptAction.EXPLAIN && !passage.isNullOrBlank()) {
                append("<selected_passage>\n").append(passage.trim()).append("\n</selected_passage>\n\n")
            }
            append(instruction(action))
        }
        return AiRequest(
            task = AiTask.SUMMARIZE,
            messages = listOf(AiMessage(AiRole.USER, body)),
            system = SYSTEM,
            maxOutputTokens = if (action == TranscriptAction.REVISION_NOTES) 4096 else 2048,
            documentId = documentId,
        )
    }

    /** The segment at [index] with up to [around] neighbours each side, as "[m:ss] text" lines. */
    fun passageAround(segments: List<TranscriptSegment>, index: Int, around: Int = 1): String {
        if (index !in segments.indices) return ""
        val from = (index - around).coerceAtLeast(0)
        val to = (index + around).coerceAtMost(segments.lastIndex)
        return formatTranscript(segments.subList(from, to + 1))
    }
}
