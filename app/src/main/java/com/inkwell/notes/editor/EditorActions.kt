package com.inkwell.notes.editor

import android.graphics.Bitmap
import android.util.Log
import com.inkwell.notes.R
import com.inkwell.notes.ai.ui.AiPanelContext
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.RecognitionResult
import com.inkwell.notes.editor.logic.AiOutline
import com.inkwell.notes.editor.logic.FragmentOps
import com.inkwell.notes.ink.InkSelection
import com.inkwell.notes.ocr.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "EditorActions"

/** Text recognised from a selection: handwriting (if any) plus typed boxes. */
data class SelectionText(val text: String, val confidence: Float?, val engine: String?, val uncertain: List<String>)

fun NotebookController.handwritingLanguage(): String =
    graph.settings.current.ocr.handwritingLanguages.firstOrNull()?.takeIf { it.isNotBlank() } ?: "en-US"

/**
 * Recognises the handwriting in [sel] on the device (ML Kit) and appends typed text. Returns
 * null and explains why (e.g. offers the model download) when nothing could be recognised.
 */
suspend fun NotebookController.recognizeSelection(sel: InkSelection, reportFailure: Boolean = true): SelectionText? {
    val strokes = sel.content.strokes.filter { it.pen != PenType.HIGHLIGHTER }
    val typed = FragmentOps.typedText(sel.content)
    var result: RecognitionResult? = null
    if (strokes.isNotEmpty()) {
        result = busy.run(str(R.string.editor_busy_reading)) {
            catchingNonCancel { graph.recognition.recognizeStrokes(strokes, handwritingLanguage()) }
                .onFailure { Log.w(TAG, "Handwriting recognition failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
    }
    val handwriting = result?.text?.trim().orEmpty()
    val text = listOf(handwriting, typed).filter { it.isNotEmpty() }.joinToString("\n")
    if (text.isEmpty()) {
        if (reportFailure) {
            if (strokes.isNotEmpty() && result == null) explainRecognitionUnavailable()
            else messages.show(str(R.string.editor_no_text_found))
        }
        return null
    }
    return SelectionText(text, result?.confidence, result?.engine, result?.uncertainSpans.orEmpty())
}

private fun NotebookController.explainRecognitionUnavailable() {
    val lang = handwritingLanguage()
    when (graph.recognition.handwritingModelStatus(lang).value) {
        ModelStatus.NOT_DOWNLOADED -> messages.show(str(R.string.editor_hw_model_missing), str(R.string.editor_download)) {
            uiScope.launch {
                val ok = busy.run(str(R.string.editor_busy_downloading_model)) {
                    catchingNonCancel { graph.recognition.downloadHandwritingModel(lang) }.getOrDefault(false)
                }
                messages.show(str(if (ok) R.string.editor_hw_model_ready else R.string.editor_hw_model_failed))
            }
        }
        ModelStatus.DOWNLOADING -> messages.show(str(R.string.editor_hw_model_downloading))
        else -> messages.show(str(R.string.editor_hw_unavailable), long = true)
    }
}

/** Copies the selection into the editor clipboard. */
fun NotebookController.copySelection(cut: Boolean = false) {
    val content = if (cut) canvas.cutSelection() else canvas.copySelection()
    if (content == null || content.isEmpty) return
    EditorSession.setClipboard(content, docId)
    messages.show(str(if (cut) R.string.editor_cut_done else R.string.editor_copy_done))
}

/** AI panel context for a selection action; the image lets vision models read equations and diagrams. */
suspend fun NotebookController.selectionAiContext(sel: InkSelection, prompt: String?, autoSend: Boolean): AiPanelContext {
    val text = recognizeSelection(sel, reportFailure = false)?.text
    val image: Bitmap? = runCatching { canvas.renderSelectionBitmap(1024) }.getOrNull()
    return AiPanelContext(
        docId = docId,
        selectionText = text,
        selectionImage = image,
        pageId = sel.pageId,
        pageIndex = sel.pageIndex,
        initialPrompt = prompt,
        autoSend = autoSend && prompt != null,
    )
}

fun NotebookController.pageAiContext(): AiPanelContext {
    val page = currentPageMeta
    return AiPanelContext(docId = docId, pageId = page?.id, pageIndex = page?.let { currentPage })
}

/** Saves a copy of the selection to "My elements". */
fun NotebookController.saveSelectionAsElement(name: String) {
    val content = canvas.copySelection() ?: return
    val thumb = runCatching { canvas.renderSelectionBitmap(320) }.getOrNull()
    launchOp(R.string.editor_element_save_failed) {
        elements.save(name, content, docId, thumb)
        messages.show(str(R.string.editor_element_saved, name.trim()))
    }
}

/** Inserts a fragment at the viewport centre of this pane (used by "Send to other pane"). */
fun NotebookController.receiveFragment(content: PageContent, sourceDocId: String) {
    uiScope.launch {
        val fragment = prepareFragment(content, sourceDocId)
        canvas.insertItems(null, fragment, null, select = true)
    }
}

/** Plain text of a page: the PDF text layer when present, else its indexed (recognised) text. */
suspend fun NotebookController.pageText(index: Int): String {
    val page = pages.getOrNull(index) ?: return ""
    val pdfText = page.pdfPageIndex?.let { pdf ->
        catchingNonCancel { graph.pdf.pageText(docId, pdf) }.getOrDefault("")
    }.orEmpty()
    if (pdfText.isNotBlank()) return pdfText.trim()
    return catchingNonCancel { graph.search.documentText(docId) }.getOrDefault(emptyList())
        .filter { it.pageId == page.id }
        .joinToString("\n") { it.text.trim() }
        .trim()
}

/** The request "Generate outline with AI" would send, or null when no page has recognised text. */
suspend fun NotebookController.buildOutlineRequest(): AiRequest? {
    val passages = withContext(Dispatchers.IO) { catchingNonCancel { graph.search.documentText(docId) }.getOrDefault(emptyList()) }
    val indexById = pages.withIndex().associate { it.value.id to it.index }
    val perPage = passages.mapNotNull { p -> (p.pageId?.let(indexById::get) ?: p.pageIndex)?.let { it to p.text } }
        .groupBy({ it.first }, { it.second })
        .map { (i, texts) -> i to texts.joinToString(" ") }
    val prompt = AiOutline.buildPrompt(perPage)
    if (prompt.isBlank()) return null
    return AiRequest(
        task = AiTask.SUMMARIZE,
        system = AiOutline.SYSTEM_PROMPT,
        messages = listOf(AiMessage(AiRole.USER, prompt)),
        maxOutputTokens = 1_500,
        temperature = 0.2f,
        documentId = docId,
    )
}

/** Sends [request], parses the outline and adds the entries. Never touches page content. */
fun NotebookController.generateOutline(request: AiRequest, existingCount: Int) {
    uiScope.launch {
        val result = busy.run(str(R.string.editor_busy_outline)) {
            catchingNonCancel {
                val reply = graph.ai.complete(request)
                val items = AiOutline.parse(reply.text, pages.size)
                val entries = AiOutline.toEntries(items, pages, docId, startSortOrder = existingCount)
                entries.forEach { graph.library.upsertOutlineEntry(it) }
                entries.size
            }
        }
        result.onSuccess { n ->
            messages.show(if (n == 0) str(R.string.editor_outline_empty) else appContext.resources.getQuantityString(R.plurals.editor_outline_added, n, n))
        }.onFailure {
            Log.w(TAG, "Outline generation failed: ${it.javaClass.simpleName}")
            messages.show(str(R.string.editor_outline_failed, it.message ?: ""), long = true)
        }
    }
}
