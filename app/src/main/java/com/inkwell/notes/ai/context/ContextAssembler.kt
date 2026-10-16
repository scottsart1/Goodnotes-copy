package com.inkwell.notes.ai.context

import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.ocr.IndexedPassage
import com.inkwell.notes.ocr.SearchIndex

/** What the conversation is about. */
enum class ContextScope(val label: String) { SELECTION("Selection"), PAGE("This page"), DOCUMENT("Whole document") }

/** The panel's current context in plain types (the selection image already encoded as PNG). */
data class ContextInput(
    val docId: String,
    val selectionText: String? = null,
    val selectionPng: ByteArray? = null,
    val pageId: String? = null,
    val pageIndex: Int? = null,
)

data class AssembledContext(
    /** `<document>` blocks to put before the user's question, or null when nothing is attached. */
    val block: String?,
    val images: List<AiPart.Image>,
    val sources: List<SourceRef>,
    /** Explanations shown to the user (retrieval used, empty page, ...). */
    val notes: List<String>,
    val usedRetrieval: Boolean,
) {
    val isEmpty: Boolean get() = block == null && images.isEmpty()

    companion object {
        val NONE = AssembledContext(null, emptyList(), emptyList(), emptyList(), false)
    }
}

/**
 * Builds the document context for a question. Large documents (over [retrievalThresholdChars])
 * are reduced to the most relevant passages when retrieval is on, so only what the task needs
 * is sent. Every passage is prefixed with a source label the model is asked to cite.
 */
class ContextAssembler(
    private val search: SearchIndex,
    private val docTitle: suspend (String) -> String?,
    private val retrievalThresholdChars: Int = 12_000,
    private val retrievalBudgetChars: Int = 12_000,
    /** Hard cap per document when retrieval is off (the engine still checks the context window). */
    private val maxDocumentChars: Int = 300_000,
    private val maxExtraDocs: Int = 5,
) {
    suspend fun assemble(
        scope: ContextScope?,
        input: ContextInput,
        question: String,
        extraDocIds: List<String>,
        retrieval: Boolean,
        includeImage: Boolean,
    ): AssembledContext {
        val blocks = ArrayList<String>()
        val sources = LinkedHashMap<String, SourceRef>()
        val notes = ArrayList<String>()
        val images = ArrayList<AiPart.Image>()
        var usedRetrieval = false
        val title = runCatching { docTitle(input.docId) }.getOrNull()

        when (scope) {
            ContextScope.SELECTION -> {
                val text = input.selectionText?.trim().orEmpty()
                val label = input.pageIndex?.let { SourceLabels.page(it) } ?: "selection"
                val body = StringBuilder()
                if (text.isNotEmpty()) body.append('[').append(label).append("] ").append(text)
                if (includeImage && input.selectionPng != null) {
                    images += AiPart.Image(input.selectionPng, "image/png")
                    if (body.isNotEmpty()) body.append("\n\n")
                    body.append("[").append(label).append("] (The selection is also attached as an image; prefer the image where the recognised text looks wrong.)")
                }
                if (body.isNotEmpty()) {
                    blocks += documentBlock(title, "selection", body.toString())
                    sources[label] = SourceRef(label, null, input.docId, title, input.pageIndex)
                } else {
                    notes += "The selection has no recognised text yet."
                }
            }
            ContextScope.PAGE -> {
                val passages = runCatching { search.documentText(input.docId) }.getOrDefault(emptyList())
                    .filter { usable(it) && ((input.pageId != null && it.pageId == input.pageId) || (input.pageId == null && it.pageIndex == input.pageIndex)) }
                val (text, refs) = render(passages, null, input.docId, title, maxDocumentChars)
                if (text.isNotEmpty()) {
                    blocks += documentBlock(title, input.pageIndex?.let { "page ${it + 1}" } ?: "this page", text)
                    refs.forEach { sources.putIfAbsent(it.label, it) }
                } else {
                    notes += "This page has no recognised text yet."
                }
            }
            ContextScope.DOCUMENT -> {
                val r = documentContext(input.docId, null, title, question, retrieval)
                if (r.text.isNotEmpty()) {
                    blocks += documentBlock(title, "this document", r.text)
                    r.refs.forEach { sources.putIfAbsent(it.label, it) }
                    usedRetrieval = usedRetrieval || r.retrieved
                    r.note?.let { notes += it }
                } else {
                    notes += "This document has no recognised text yet."
                }
            }
            null -> Unit
        }

        extraDocIds.filter { it != input.docId }.distinct().take(maxExtraDocs).forEachIndexed { i, id ->
            val tag = "D${i + 2}"
            val t = runCatching { docTitle(id) }.getOrNull()
            val r = documentContext(id, tag, t, question, retrieval)
            if (r.text.isNotEmpty()) {
                blocks += documentBlock(t, tag, r.text)
                r.refs.forEach { sources.putIfAbsent(it.label, it) }
                usedRetrieval = usedRetrieval || r.retrieved
                r.note?.let { notes += "${t ?: tag}: $it" }
            } else {
                notes += "${t ?: "An added document"} has no recognised text yet."
            }
        }
        if (usedRetrieval) {
            notes.add(0, "Large document: only the passages most relevant to your question were sent.")
        }
        return AssembledContext(
            block = blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            images = images,
            sources = sources.values.toList(),
            notes = notes,
            usedRetrieval = usedRetrieval,
        )
    }

    private data class DocResult(val text: String, val refs: List<SourceRef>, val retrieved: Boolean, val note: String?)

    private suspend fun documentContext(docId: String, tag: String?, title: String?, question: String, retrieval: Boolean): DocResult {
        val all = runCatching { search.documentText(docId) }.getOrDefault(emptyList()).filter(::usable)
        val total = all.sumOf { it.text.length }
        if (total == 0) return DocResult("", emptyList(), false, null)
        if (total <= retrievalThresholdChars) {
            val (t, refs) = render(all, tag, docId, title, maxDocumentChars)
            return DocResult(t, refs, false, null)
        }
        if (!retrieval) {
            val (t, refs) = render(all, tag, docId, title, maxDocumentChars)
            val note = if (total > maxDocumentChars) "Very long document: only the first part was sent." else null
            return DocResult(t, refs, false, note)
        }
        val retrieved = runCatching { search.retrieve(docId, question, retrievalBudgetChars) }.getOrDefault(emptyList()).filter(::usable)
        val chosen = if (retrieved.isNotEmpty()) {
            // Present in reading order: easier for the model to follow than relevance order.
            val order = all.withIndex().associate { (i, p) -> key(p) to i }
            retrieved.sortedWith(compareBy({ order[key(it)] ?: Int.MAX_VALUE }, { it.pageIndex ?: Int.MAX_VALUE }, { it.timestampMs ?: 0 }))
        } else {
            spread(all, retrievalBudgetChars)
        }
        val (t, refs) = render(chosen, tag, docId, title, retrievalBudgetChars + 2_000)
        return DocResult(t, refs, true, null)
    }

    private fun key(p: IndexedPassage) = "${p.pageId}|${p.recordingId}|${p.timestampMs}|${p.text.hashCode()}"

    /** Evenly spaced passages across the document within [budget] characters (no keyword match). */
    private fun spread(all: List<IndexedPassage>, budget: Int): List<IndexedPassage> {
        val avg = (all.sumOf { it.text.length } / all.size.coerceAtLeast(1)).coerceAtLeast(1)
        val count = (budget / avg).coerceIn(1, all.size)
        val step = all.size.toDouble() / count
        val out = ArrayList<IndexedPassage>()
        var used = 0
        var i = 0.0
        while (i < all.size) {
            val p = all[i.toInt()]
            if (used + p.text.length > budget && out.isNotEmpty()) break
            out += p
            used += p.text.length
            i += step
        }
        return out
    }

    private fun usable(p: IndexedPassage): Boolean =
        p.text.isNotBlank() && p.source != TextSource.TITLE && p.source != TextSource.FOLDER && p.source != TextSource.TAG

    /** Renders passages grouped under their labels, within [maxChars]. */
    private fun render(passages: List<IndexedPassage>, tag: String?, docId: String, title: String?, maxChars: Int): Pair<String, List<SourceRef>> {
        val sb = StringBuilder()
        val refs = LinkedHashMap<String, SourceRef>()
        var currentLabel: String? = null
        for (p in passages) {
            val label = labelFor(p, tag)
            val text = p.text.trim()
            val addition = (if (label != currentLabel) (if (sb.isEmpty()) "" else "\n") + "[$label] " else "\n") + text
            if (sb.length + addition.length > maxChars) {
                val room = maxChars - sb.length
                if (room > 200) sb.append(addition.take(room)).append(" …")
                if (label != currentLabel && room > 200) refs.putIfAbsent(label, ref(p, label, tag, docId, title))
                break
            }
            sb.append(addition)
            if (label != currentLabel) refs.putIfAbsent(label, ref(p, label, tag, docId, title))
            currentLabel = label
        }
        return sb.toString() to refs.values.toList()
    }

    private fun ref(p: IndexedPassage, label: String, tag: String?, docId: String, title: String?) =
        SourceRef(label, tag, docId, title, p.pageIndex, p.recordingId, p.timestampMs)

    private fun documentBlock(title: String?, label: String, body: String): String {
        val safeTitle = (title ?: "Untitled").replace("\"", "'")
        return "<document title=\"$safeTitle\" label=\"$label\">\n$body\n</document>"
    }

    companion object {
        fun labelFor(p: IndexedPassage, tag: String? = null): String = when {
            p.recordingId != null && p.timestampMs != null -> SourceLabels.recording(p.timestampMs, tag)
            p.pageIndex != null -> SourceLabels.page(p.pageIndex, tag)
            p.source == TextSource.TRANSCRIPT -> SourceLabels.withTag(tag, "recording")
            else -> SourceLabels.withTag(tag, "doc")
        }
    }
}
