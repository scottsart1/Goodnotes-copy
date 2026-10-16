package com.inkwell.notes.typed

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.typed.model.BlockEditResult
import com.inkwell.notes.typed.model.BlockOps
import com.inkwell.notes.typed.model.History
import com.inkwell.notes.typed.model.InlineStyle
import com.inkwell.notes.typed.model.MarkdownBlocks
import com.inkwell.notes.typed.model.MarkdownShortcuts
import com.inkwell.notes.typed.model.Marks
import com.inkwell.notes.typed.model.SpanOps
import com.inkwell.notes.typed.model.TableOps
import com.inkwell.notes.typed.model.TextDiff
import com.inkwell.notes.typed.model.TextEdit
import com.inkwell.notes.whiteboard.kit.DocAssets
import com.inkwell.notes.whiteboard.kit.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Editor state of one text block: text, selection and IME composition. */
class FieldState(initial: TextFieldValue) {
    var value by mutableStateOf(initial)
}

/** Where the caret should go next. [seq] makes repeated requests distinct. */
data class FocusRequest(val blockId: String, val selection: TextRange? = null, val cell: Pair<Int, Int>? = null, val seq: Long = 0)

/** A focused table cell. */
data class CellRef(val blockId: String, val row: Int, val column: Int)

enum class SaveState { SAVED, SAVING, ERROR }

private data class Snapshot(val blocks: List<TypedBlock>, val focusId: String?, val selection: TextRange?)

/**
 * The typed-document editor model: block list, per-block field state, focus, formatting,
 * structural edits, undo/redo and autosave. UI-thread only; I/O goes through [TypedStore].
 */
class TypedDocController(val graph: AppGraph, val docId: String) {

    sealed interface LoadState {
        data object Loading : LoadState
        data object Missing : LoadState
        data object Unreadable : LoadState
        data object Failed : LoadState
        data object Ready : LoadState
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = TypedStore.forDocument(graph, docId)
    private val history = History<Snapshot>()
    private val fields = HashMap<String, FieldState>()
    private var focusSeq = 0L

    var loadState by mutableStateOf<LoadState>(LoadState.Loading); private set
    var doc by mutableStateOf<DocumentMeta?>(null); private set
    var blocks by mutableStateOf<List<TypedBlock>>(emptyList()); private set
    var focusedBlockId by mutableStateOf<String?>(null); private set
    var focusedCell by mutableStateOf<CellRef?>(null); private set
    var focusRequest by mutableStateOf<FocusRequest?>(null); private set
    /** Formatting for the next typed characters when the toolbar was used with no selection. */
    var pendingMarks by mutableStateOf<Marks?>(null); private set
    var saveState by mutableStateOf(SaveState.SAVED); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    /** Bumped on every structural/text change; observers recompute derived values. */
    var revision by mutableStateOf(0L); private set

    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)

    private val saveListener: (Throwable?, Boolean) -> Unit = { error, retry ->
        scope.launch {
            if (error == null) {
                if (!store.saver.hasPending) saveState = SaveState.SAVED
            } else {
                saveState = SaveState.ERROR
                messages.tryEmit(UiMessage(if (retry) R.string.cd_save_retrying else R.string.cd_save_failed, long = !retry))
            }
        }
    }

    init {
        store.addListener(saveListener)
    }

    // ------------------------------------------------------------------ Lifecycle

    suspend fun load() {
        loadState = LoadState.Loading
        try {
            val d = graph.library.getDocument(docId)
            if (d == null || d.trashedAt != null) { loadState = LoadState.Missing; return }
            doc = d
            when (val r = store.load()) {
                TypedLoad.Unreadable -> { loadState = LoadState.Unreadable; return }
                is TypedLoad.Loaded -> setDocument(r.document)
            }
            loadState = LoadState.Ready
            scope.launch { runCatching { graph.library.touch(docId) } }
            scope.launch { graph.library.observeDocument(docId).collect { if (it != null) doc = it } }
            blocks.firstOrNull()?.takeIf { blocks.size == 1 && it.text.isEmpty() && BlockOps.isTextual(it.type) }?.let {
                requestFocus(it.id, TextRange(0))
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Loading typed document failed", t)
            loadState = LoadState.Failed
        }
    }

    private fun setDocument(d: TypedDocument) {
        fields.clear()
        blocks = d.blocks.ifEmpty { listOf(BlockOps.paragraph()) }
        history.clear()
        updateHistoryFlags()
        revision++
    }

    /** Writes pending edits now (lifecycle pause). */
    fun flush() {
        store.saver.flushAsync()
    }

    suspend fun flushAndWait() = store.flush()

    fun dispose() {
        store.saver.flushAsync()
        store.removeListener(saveListener)
        scope.cancel()
    }

    // ------------------------------------------------------------------ Field state

    /**
     * The field holder of a block for the UI. Never writes state (safe during composition);
     * the UI shows [TextFieldValue] with the block's text if the two ever disagree.
     */
    fun fieldFor(block: TypedBlock): FieldState =
        fields.getOrPut(block.id) { FieldState(TextFieldValue(block.text, TextRange(block.text.length))) }

    /** Field holder synchronised with the block's text (event handlers only). */
    private fun field(block: TypedBlock): FieldState {
        val f = fieldFor(block)
        if (f.value.text != block.text) {
            f.value = TextFieldValue(block.text, clamp(f.value.selection, block.text.length))
        }
        return f
    }

    private fun setField(id: String, text: String, selection: TextRange) {
        val f = fields.getOrPut(id) { FieldState(TextFieldValue(text, selection)) }
        f.value = TextFieldValue(text, clamp(selection, text.length))
    }

    private fun clamp(r: TextRange, len: Int) = TextRange(r.start.coerceIn(0, len), r.end.coerceIn(0, len))

    fun indexOf(id: String): Int = blocks.indexOfFirst { it.id == id }

    fun selectionOf(id: String): TextRange = fields[id]?.value?.selection ?: TextRange.Zero

    val focusedBlock: TypedBlock? get() = focusedBlockId?.let { id -> blocks.firstOrNull { it.id == id } }

    // ------------------------------------------------------------------ Focus

    fun requestFocus(blockId: String, selection: TextRange? = null, cell: Pair<Int, Int>? = null) {
        if (selection != null) blocks.firstOrNull { it.id == blockId }?.let { setField(blockId, it.text, selection) }
        focusRequest = FocusRequest(blockId, selection, cell, ++focusSeq)
    }

    /** Called by the block that handled [req]. */
    fun consumeFocus(req: FocusRequest) {
        if (focusRequest == req) focusRequest = null
    }

    fun onFocused(blockId: String) {
        if (focusedBlockId != blockId) pendingMarks = null
        focusedBlockId = blockId
        focusedCell = null
        history.breakCoalescing()
    }

    fun onCellFocused(blockId: String, row: Int, column: Int) {
        focusedBlockId = blockId
        focusedCell = CellRef(blockId, row, column)
        pendingMarks = null
    }

    /** Puts the caret at the end of the last text block, appending a paragraph after a table/image/divider. */
    fun focusLastOrAppend() {
        val last = blocks.lastOrNull()
        if (last != null && BlockOps.isTextual(last.type)) {
            requestFocus(last.id, TextRange(last.text.length))
            return
        }
        val para = BlockOps.paragraph()
        commit(blocks + para)
        requestFocus(para.id, TextRange(0))
    }

    /** Moves the caret to the neighbouring text block ([direction] -1 = previous end, +1 = next start). */
    fun focusAdjacent(blockId: String, direction: Int): Boolean {
        var i = indexOf(blockId) + direction
        while (i in blocks.indices) {
            val b = blocks[i]
            if (BlockOps.isTextual(b.type)) {
                requestFocus(b.id, TextRange(if (direction < 0) b.text.length else 0))
                return true
            }
            if (b.type == BlockType.TABLE) {
                val row = if (direction < 0) TableOps.normalize(b.rows).lastIndex else 0
                requestFocus(b.id, cell = row to 0)
                return true
            }
            i += direction
        }
        return false
    }

    // ------------------------------------------------------------------ History

    private fun record(key: String? = null) {
        history.record(Snapshot(blocks, focusedBlockId, focusedBlockId?.let { selectionOf(it) }), key)
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    fun undo() {
        val prev = history.undo(Snapshot(blocks, focusedBlockId, focusedBlockId?.let { selectionOf(it) })) ?: return
        restore(prev)
    }

    fun redo() {
        val next = history.redo(Snapshot(blocks, focusedBlockId, focusedBlockId?.let { selectionOf(it) })) ?: return
        restore(next)
    }

    private fun restore(s: Snapshot) {
        blocks = s.blocks
        for (b in s.blocks) fields[b.id]?.let { f -> if (f.value.text != b.text) f.value = TextFieldValue(b.text, clamp(f.value.selection, b.text.length)) }
        fields.keys.retainAll(s.blocks.map { it.id }.toSet())
        updateHistoryFlags()
        changed()
        val focus = s.focusId?.takeIf { id -> s.blocks.any { it.id == id } } ?: s.blocks.firstOrNull()?.id
        if (focus != null) requestFocus(focus, s.selection)
    }

    // ------------------------------------------------------------------ Saving

    private fun changed() {
        revision++
        saveState = SaveState.SAVING
        store.submit(TypedDocument(blocks = blocks))
    }

    private fun commit(newBlocks: List<TypedBlock>, historyKey: String? = null) {
        record(historyKey)
        blocks = newBlocks
        changed()
    }

    // ------------------------------------------------------------------ Typing

    /** Every edit of a text block's field. Handles span offsets, Enter, Markdown shortcuts. */
    fun onFieldChange(blockId: String, value: TextFieldValue) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val block = blocks[idx]
        val f = field(block)
        if (value.text == block.text) {
            if (value.selection != f.value.selection) pendingMarks = null
            f.value = value
            return
        }
        val edit = TextDiff.compute(block.text, value.text, value.selection.end)
        if (edit.inserted.contains('\n') && block.type != BlockType.CODE) {
            handleNewlines(idx, block, edit, value)
            return
        }
        if (block.type == BlockType.CODE && edit.inserted == "\n" && edit.deletedLength == 0 &&
            edit.start == block.text.length && block.text.endsWith("\n")
        ) {
            // Enter on an empty last line leaves the code block.
            val trimmed = block.copy(text = block.text.dropLast(1))
            val para = BlockOps.paragraph()
            commit(BlockOps.insertAfter(blocks.replaceAt(idx, trimmed), idx, listOf(para)))
            setField(trimmed.id, trimmed.text, TextRange(trimmed.text.length))
            requestFocus(para.id, TextRange(0))
            return
        }
        val spans = if (BlockOps.supportsSpans(block.type)) {
            SpanOps.replace(block.spans, block.text.length, edit.start, edit.deletedLength, edit.inserted.length, pendingMarks.takeIf { edit.inserted.isNotEmpty() })
        } else emptyList()
        var updated = block.copy(text = value.text, spans = spans)
        var newValue = value
        if (block.type == BlockType.PARAGRAPH && edit.inserted == " " && edit.deletedLength == 0) {
            val match = MarkdownShortcuts.detect(value.text, value.selection.end)
            if (match != null) {
                if (match.type == BlockType.DIVIDER) {
                    val rest = value.text.substring(match.prefixLength)
                    val divider = TypedBlock(block.id, BlockType.DIVIDER)
                    val para = BlockOps.paragraph(text = rest).copy(spans = SpanOps.dropPrefix(spans, value.text.length, match.prefixLength))
                    commit(BlockOps.insertAfter(blocks.replaceAt(idx, divider), idx, listOf(para)), historyKey = null)
                    fields.remove(block.id)
                    requestFocus(para.id, TextRange(0))
                    return
                }
                val text = value.text.substring(match.prefixLength)
                updated = TypedBlock(
                    id = block.id,
                    type = match.type,
                    text = text,
                    spans = if (match.type == BlockType.CODE) emptyList() else SpanOps.dropPrefix(spans, value.text.length, match.prefixLength),
                    level = if (match.type == BlockType.HEADING) match.level else 0,
                    checked = match.checked,
                    language = match.language,
                )
                newValue = TextFieldValue(text, TextRange((value.selection.end - match.prefixLength).coerceIn(0, text.length)))
                record(null)
                blocks = blocks.replaceAt(idx, updated)
                fields[block.id]?.value = newValue
                pendingMarks = null
                changed()
                return
            }
        }
        record("type:$blockId")
        blocks = blocks.replaceAt(idx, updated)
        f.value = newValue
        changed()
    }

    private fun handleNewlines(idx: Int, block: TypedBlock, edit: TextEdit, value: TextFieldValue) {
        // Enter on an empty list item: outdent, or leave the list.
        if (edit.inserted == "\n" && block.text.isEmpty() && (BlockOps.isList(block.type) || block.type == BlockType.QUOTE)) {
            val replacement = if (BlockOps.isList(block.type) && block.level > 0) block.copy(level = block.level - 1)
            else block.copy(type = BlockType.PARAGRAPH, level = 0, checked = false)
            commit(blocks.replaceAt(idx, replacement))
            setField(block.id, "", TextRange(0))
            requestFocus(block.id, TextRange(0))
            return
        }
        val supports = BlockOps.supportsSpans(block.type)
        var text = value.text
        var spans = if (supports) SpanOps.replace(block.spans, block.text.length, edit.start, edit.deletedLength, edit.inserted.length, pendingMarks) else emptyList()
        var list = blocks
        var cursorIdx = idx
        var current = block.copy(text = text, spans = spans)
        list = list.replaceAt(cursorIdx, current)
        // Positions of inserted newlines, in the coordinates of the current (last) block.
        var consumed = 0
        val insertEnd = edit.start + edit.inserted.length
        var searchFrom = edit.start
        var lastBlockStart = 0
        while (true) {
            val nl = value.text.indexOf('\n', searchFrom)
            if (nl < 0 || nl >= insertEnd) break
            val local = nl - consumed
            text = current.text
            spans = current.spans
            val without = text.removeRange(local, local + 1)
            val withoutSpans = if (supports) SpanOps.replace(spans, text.length, local, 1, 0) else emptyList()
            list = list.replaceAt(cursorIdx, current.copy(text = without, spans = withoutSpans))
            val r = BlockOps.split(list, cursorIdx, local)
            list = r.blocks
            cursorIdx = r.focusIndex
            current = list[cursorIdx]
            consumed = nl + 1
            lastBlockStart = nl + 1
            searchFrom = nl + 1
        }
        commit(list)
        pendingMarks = null
        // Sync fields of every block touched.
        for (i in idx..cursorIdx) {
            val b = list[i]
            setField(b.id, b.text, TextRange(b.text.length))
        }
        val caret = (value.selection.end - lastBlockStart).coerceIn(0, current.text.length)
        requestFocus(current.id, TextRange(caret))
    }

    /** Hardware/IME backspace with the caret at offset 0. Returns true when handled. */
    fun backspaceAtStart(blockId: String): Boolean {
        val idx = indexOf(blockId)
        if (idx < 0) return false
        val r = BlockOps.backspaceAtStart(blocks, idx) ?: return false
        applyResult(r)
        return true
    }

    private fun applyResult(r: BlockEditResult) {
        commit(r.blocks)
        fields.keys.retainAll(r.blocks.map { it.id }.toSet())
        val target = r.blocks.getOrNull(r.focusIndex) ?: return
        if (r.caret >= 0 && BlockOps.isTextual(target.type)) {
            setField(target.id, target.text, TextRange(r.caret))
            requestFocus(target.id, TextRange(r.caret))
        } else if (target.type == BlockType.TABLE) {
            requestFocus(target.id, cell = 0 to 0)
        }
    }

    // ------------------------------------------------------------------ Inline formatting

    fun isStyleActive(style: InlineStyle): Boolean {
        val b = focusedBlock ?: return false
        if (!BlockOps.supportsSpans(b.type)) return false
        val sel = selectionOf(b.id)
        if (sel.collapsed) {
            val marks = pendingMarks ?: SpanOps.marksAt(b.spans, b.text.length, sel.start)
            return marks.has(style)
        }
        return SpanOps.isActive(b.spans, b.text.length, sel.min, sel.max, style)
    }

    fun toggleStyle(style: InlineStyle) {
        val b = focusedBlock ?: return
        if (!BlockOps.supportsSpans(b.type)) return
        val sel = selectionOf(b.id)
        if (sel.collapsed) {
            val base = pendingMarks ?: SpanOps.marksAt(b.spans, b.text.length, sel.start)
            pendingMarks = base.with(style, !base.has(style))
            return
        }
        val idx = indexOf(b.id)
        commit(blocks.replaceAt(idx, b.copy(spans = SpanOps.toggle(b.spans, b.text.length, sel.min, sel.max, style))))
    }

    /** The link at the caret/selection: the range to edit and its current URL (null = new link). */
    fun linkTarget(): Triple<String, IntRange, String?>? {
        val b = focusedBlock ?: return null
        if (!BlockOps.supportsSpans(b.type)) return null
        val sel = selectionOf(b.id)
        SpanOps.linkRangeAt(b.spans, b.text.length, sel.min)?.let { (range, url) ->
            if (sel.collapsed || (sel.min >= range.first && sel.max <= range.last + 1)) return Triple(b.id, range, url)
        }
        if (sel.collapsed) return null
        return Triple(b.id, sel.min until sel.max, null)
    }

    /** URL of the link under the caret, if any. */
    fun linkAtCaret(): String? {
        val b = focusedBlock ?: return null
        if (!BlockOps.supportsSpans(b.type)) return null
        return SpanOps.linkRangeAt(b.spans, b.text.length, selectionOf(b.id).min)?.second
    }

    fun setLink(blockId: String, range: IntRange, url: String?) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val b = blocks[idx]
        val normalized = url?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeUrl(it) }
        commit(blocks.replaceAt(idx, b.copy(spans = SpanOps.setLink(b.spans, b.text.length, range.first, range.last + 1, normalized))))
        requestFocus(blockId, TextRange(range.last + 1))
    }

    // ------------------------------------------------------------------ Block operations

    fun turnInto(blockId: String, type: BlockType, level: Int = 0) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val b = blocks[idx]
        val converted = BlockOps.turnInto(b, type, level)
        if (converted == b) return
        commit(blocks.replaceAt(idx, converted))
        if (type == BlockType.DIVIDER) {
            // Keep writing below a new divider.
            if (idx == blocks.lastIndex) {
                val para = BlockOps.paragraph()
                blocks = BlockOps.insertAfter(blocks, idx, listOf(para))
                changed()
                requestFocus(para.id, TextRange(0))
            }
            return
        }
        if (BlockOps.isTextual(converted.type)) {
            setField(converted.id, converted.text, selectionOf(blockId))
            requestFocus(converted.id, selectionOf(blockId))
        } else if (converted.type == BlockType.TABLE) {
            requestFocus(converted.id, cell = 0 to 0)
        }
    }

    /** Current block type for the toolbar (heading level folded in). */
    fun focusedType(): Pair<BlockType, Int>? = focusedBlock?.let { it.type to it.level }

    fun indent(delta: Int) {
        val b = focusedBlock ?: return
        val idx = indexOf(b.id)
        if (b.type == BlockType.CODE && delta > 0) {
            insertTextAtCaret("    ")
            return
        }
        val out = BlockOps.indent(blocks, idx, delta)
        if (out !== blocks) commit(out)
    }

    private fun insertTextAtCaret(s: String) {
        val b = focusedBlock ?: return
        val v = field(b).value
        val text = v.text.replaceRange(v.selection.min, v.selection.max, s)
        onFieldChange(b.id, TextFieldValue(text, TextRange(v.selection.min + s.length)))
    }

    fun toggleChecked(blockId: String) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val b = blocks[idx]
        commit(blocks.replaceAt(idx, b.copy(checked = !b.checked)))
    }

    fun setLanguage(blockId: String, language: String) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val clean = language.trim().take(24).ifEmpty { null }
        if (blocks[idx].language == clean) return
        commit(blocks.replaceAt(idx, blocks[idx].copy(language = clean)), historyKey = "lang:$blockId")
    }

    fun setCaption(blockId: String, caption: String) {
        val idx = indexOf(blockId)
        if (idx < 0 || blocks[idx].text == caption) return
        commit(blocks.replaceAt(idx, blocks[idx].copy(text = caption.replace('\n', ' '))), historyKey = "caption:$blockId")
    }

    fun duplicate(blockId: String) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        commit(BlockOps.duplicate(blocks, idx))
    }

    fun delete(blockId: String) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val out = BlockOps.delete(blocks, idx)
        commit(out)
        fields.remove(blockId)
        val next = out.getOrNull((idx - 1).coerceAtLeast(0)) ?: return
        if (BlockOps.isTextual(next.type)) requestFocus(next.id, TextRange(next.text.length))
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in blocks.indices || to !in blocks.indices) return
        commit(BlockOps.move(blocks, from, to), historyKey = "drag")
    }

    fun moveBy(blockId: String, delta: Int) {
        val idx = indexOf(blockId)
        move(idx, (idx + delta).coerceIn(0, blocks.lastIndex))
    }

    /** Ends a drag gesture so the next drag becomes its own undo step. */
    fun endDrag() = history.breakCoalescing()

    /** Inserts a new block after the focused one (or at the end). Empty paragraphs are replaced. */
    fun insertBlock(type: BlockType) {
        val anchor = focusedBlock
        val idx = anchor?.let { indexOf(it.id) } ?: blocks.lastIndex
        val block = when (type) {
            BlockType.TABLE -> TypedBlock(BlockOps.newId(), BlockType.TABLE, rows = TableOps.newTable())
            BlockType.DIVIDER -> TypedBlock(BlockOps.newId(), BlockType.DIVIDER)
            BlockType.CODE -> TypedBlock(BlockOps.newId(), BlockType.CODE)
            else -> TypedBlock(BlockOps.newId(), type, level = if (type == BlockType.HEADING) 1 else 0)
        }
        insertBlocks(idx, listOf(block), replaceEmptyAnchor = true)
        when {
            type == BlockType.TABLE -> requestFocus(block.id, cell = 0 to 0)
            BlockOps.isTextual(type) -> requestFocus(block.id, TextRange(0))
        }
    }

    private fun insertBlocks(anchorIdx: Int, newBlocks: List<TypedBlock>, replaceEmptyAnchor: Boolean) {
        val anchor = blocks.getOrNull(anchorIdx)
        var out = if (replaceEmptyAnchor && anchor != null && anchor.type == BlockType.PARAGRAPH && anchor.text.isEmpty()) {
            blocks.toMutableList().apply { removeAt(anchorIdx); addAll(anchorIdx, newBlocks) }
        } else {
            BlockOps.insertAfter(blocks, anchorIdx, newBlocks)
        }
        // Always leave a paragraph to type into after a trailing non-text block.
        if (!BlockOps.isTextual(out.last().type)) out = out + BlockOps.paragraph()
        commit(out)
    }

    /** Imports a picked image into the document's assets and inserts it below the focused block. */
    fun insertImage(context: Context, uri: Uri, replaceBlockId: String? = null) {
        scope.launch {
            try {
                val stored = DocAssets.importImage(context, graph, docId, uri)
                val replaceIdx = replaceBlockId?.let { indexOf(it) } ?: -1
                if (replaceIdx >= 0) {
                    val b = blocks[replaceIdx]
                    commit(blocks.replaceAt(replaceIdx, b.copy(type = BlockType.IMAGE, assetId = stored.assetId)))
                } else {
                    val anchor = focusedBlock?.let { indexOf(it.id) } ?: blocks.lastIndex
                    insertBlocks(anchor, listOf(TypedBlock(BlockOps.newId(), BlockType.IMAGE, assetId = stored.assetId)), replaceEmptyAnchor = true)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Image import failed: ${t.javaClass.simpleName}")
                messages.tryEmit(UiMessage(R.string.cd_image_import_failed))
            }
        }
    }

    /** Adds assistant output (Markdown) as blocks after the focused block. */
    fun insertAiText(text: String) {
        val parsed = MarkdownBlocks.parse(text.trim())
        if (parsed.isEmpty()) return
        val anchor = focusedBlock?.let { indexOf(it.id) } ?: blocks.lastIndex
        insertBlocks(anchor, parsed, replaceEmptyAnchor = true)
        val last = parsed.last()
        if (BlockOps.isTextual(last.type)) requestFocus(last.id, TextRange(last.text.length))
        messages.tryEmit(UiMessage(R.string.td_ai_inserted))
    }

    // ------------------------------------------------------------------ Tables

    fun setCell(blockId: String, row: Int, column: Int, text: String) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val b = blocks[idx]
        if (TableOps.cell(b.rows, row, column) == text) return
        commit(blocks.replaceAt(idx, b.copy(rows = TableOps.setCell(b.rows, row, column, text))), historyKey = "cell:$blockId:$row:$column")
    }

    fun tableEdit(blockId: String, transform: (List<List<String>>) -> List<List<String>>, focus: Pair<Int, Int>? = null) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val b = blocks[idx]
        val rows = transform(b.rows)
        if (rows == b.rows) return
        commit(blocks.replaceAt(idx, b.copy(rows = rows)))
        if (focus != null) {
            val grid = TableOps.normalize(rows)
            requestFocus(blockId, cell = focus.first.coerceIn(0, grid.lastIndex) to focus.second.coerceIn(0, grid[0].lastIndex))
        }
    }

    /** Tab / Enter in a table: next cell, adding a row after the last one. */
    fun nextCell(blockId: String, row: Int, column: Int, downwards: Boolean) {
        val idx = indexOf(blockId)
        if (idx < 0) return
        val grid = TableOps.normalize(blocks[idx].rows)
        var r = row
        var c = column
        if (downwards) r++ else { c++; if (c >= grid[0].size) { c = 0; r++ } }
        if (r >= grid.size) {
            tableEdit(blockId, { TableOps.insertRow(it, grid.size) }, focus = r to c)
        } else {
            requestFocus(blockId, cell = r to c)
        }
    }

    // ------------------------------------------------------------------ Text for AI / outline

    /** Selected text of the focused block, or the whole document as plain text. */
    fun aiText(): Pair<String?, Boolean> {
        val b = focusedBlock
        if (b != null && BlockOps.isTextual(b.type)) {
            val sel = selectionOf(b.id)
            if (!sel.collapsed) return b.text.substring(sel.min.coerceAtMost(b.text.length), sel.max.coerceAtMost(b.text.length)) to true
        }
        val all = BlockOps.plainText(blocks)
        return all.ifBlank { null } to false
    }

    companion object {
        private const val TAG = "TypedDoc"

        fun normalizeUrl(url: String): String {
            val u = url.trim()
            return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) u else "https://$u"
        }

        fun isPlausibleUrl(url: String): Boolean {
            val u = url.trim()
            if (u.isEmpty() || u.contains(' ')) return false
            return u.startsWith("mailto:") || u.contains('.') || u.startsWith("http")
        }
    }
}

private fun List<TypedBlock>.replaceAt(index: Int, b: TypedBlock): List<TypedBlock> = toMutableList().also { it[index] = b }
