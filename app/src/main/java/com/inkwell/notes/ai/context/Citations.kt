package com.inkwell.notes.ai.context

import kotlinx.serialization.Serializable

/** A passage the model was given, addressable by its [label] (e.g. "p.3", "rec 18:40", "D2 p.4"). */
@Serializable
data class SourceRef(
    val label: String,
    /** Null for the current document; "D2", "D3"... for documents the user added. */
    val docTag: String? = null,
    val documentId: String,
    val documentTitle: String? = null,
    /** 0-based page index, when the passage belongs to a page. */
    val pageIndex: Int? = null,
    val recordingId: String? = null,
    val timestampMs: Long? = null,
)

/** A source citation found in model output. */
data class Citation(
    /** Normalised label: "p.3", "rec 18:40", "D2 p.3". */
    val label: String,
    val docTag: String?,
    /** 1-based page number. */
    val page: Int?,
    val recordingMs: Long?,
)

object SourceLabels {
    fun page(index0: Int, docTag: String? = null): String = withTag(docTag, "p.${index0 + 1}")

    fun recording(ms: Long, docTag: String? = null): String = withTag(docTag, "rec ${timestamp(ms)}")

    fun withTag(docTag: String?, core: String) = if (docTag == null) core else "$docTag $core"

    /** m:ss, or h:mm:ss from one hour. */
    fun timestamp(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        // Locale.ROOT: labels go to the model and back through [Citations], whose \d only matches ASCII digits.
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }
}

/** Parses citations like [p.3], [p. 3, 5], [pp. 3-4], [rec 18:40], [D2 p.4] from model output. */
object Citations {
    private val bracket = Regex("""\[([^\[\]\n]{1,80})]""")
    private val pageRe = Regex("""^(?:(D\d+)\s+)?(?:pages?|pp?)\.?\s*(\d{1,5})(?:\s*[-–]\s*(\d{1,5}))?$""", RegexOption.IGNORE_CASE)
    private val bareRe = Regex("""^(\d{1,5})(?:\s*[-–]\s*(\d{1,5}))?$""")
    private val recRe = Regex("""^(?:(D\d+)\s+)?rec(?:ording)?\.?\s+(?:(\d{1,2}):)?(\d{1,3}):(\d{2})$""", RegexOption.IGNORE_CASE)
    private const val MAX_RANGE = 12

    /** All citations in [text], de-duplicated, in order of first appearance. */
    fun parse(text: String): List<Citation> {
        val out = LinkedHashMap<String, Citation>()
        for (m in bracket.findAll(text)) parseInner(m.groupValues[1])?.forEach { out.putIfAbsent(it.label, it) }
        return out.values.toList()
    }

    /** Citations inside one bracket's content, or null when it is not a citation (e.g. [illegible]). */
    fun parseInner(inner: String): List<Citation>? {
        val pieces = inner.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.isEmpty()) return null
        val out = ArrayList<Citation>()
        var lastTag: String? = null
        var inPages = false
        for (piece in pieces) {
            val pm = pageRe.matchEntire(piece)
            if (pm != null) {
                val tag = pm.groupValues[1].ifEmpty { null }?.uppercase()
                lastTag = tag
                inPages = true
                out += pages(tag, pm.groupValues[2].toInt(), pm.groupValues[3].toIntOrNull()) ?: return null
                continue
            }
            val rm = recRe.matchEntire(piece)
            if (rm != null) {
                val tag = rm.groupValues[1].ifEmpty { null }?.uppercase()
                lastTag = tag
                inPages = false
                val h = rm.groupValues[2].toLongOrNull() ?: 0
                val min = rm.groupValues[3].toLong()
                val sec = rm.groupValues[4].toLong()
                if (sec >= 60) return null
                val ms = ((h * 3600) + min * 60 + sec) * 1000
                out += Citation(SourceLabels.recording(ms, tag), tag, null, ms)
                continue
            }
            val bm = bareRe.matchEntire(piece)
            if (bm != null && inPages) {
                out += pages(lastTag, bm.groupValues[1].toInt(), bm.groupValues[2].toIntOrNull()) ?: return null
                continue
            }
            return null
        }
        return out
    }

    private fun pages(tag: String?, from: Int, to: Int?): List<Citation>? {
        if (from < 1) return null
        val end = to ?: from
        if (end < from || end - from >= MAX_RANGE) return listOf(page(tag, from))
        return (from..end).map { page(tag, it) }
    }

    private fun page(tag: String?, n: Int) = Citation(SourceLabels.withTag(tag, "p.$n"), tag, n, null)
}
