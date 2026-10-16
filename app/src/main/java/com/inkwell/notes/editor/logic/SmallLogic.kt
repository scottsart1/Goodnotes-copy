package com.inkwell.notes.editor.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lets at most one event through per [intervalMs]. */
class Throttler(private val intervalMs: Long) {
    private var last = Long.MIN_VALUE

    fun tryAcquire(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < intervalMs && nowMs >= last) return false
        last = nowMs
        return true
    }

    fun reset() { last = Long.MIN_VALUE }
}

data class OpenTab(val docId: String, val title: String)

/** Documents opened in this session, shown as a slim tab strip in the editor. */
class SessionTabs(private val maxTabs: Int = 12) {
    private val _tabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val tabs: StateFlow<List<OpenTab>> = _tabs.asStateFlow()

    /** Adds [docId] (at the end) or updates its title; the oldest other tab goes past [maxTabs]. */
    fun open(docId: String, title: String?) {
        _tabs.update { list ->
            val i = list.indexOfFirst { it.docId == docId }
            if (i >= 0) {
                if (title == null || list[i].title == title) list else list.toMutableList().also { it[i] = OpenTab(docId, title) }
            } else {
                val next = list + OpenTab(docId, title.orEmpty())
                if (next.size > maxTabs) next.drop(next.size - maxTabs) else next
            }
        }
    }

    fun rename(docId: String, title: String) {
        _tabs.update { list -> list.map { if (it.docId == docId && it.title != title) it.copy(title = title) else it } }
    }

    /** Closes [docId]; returns the tab to show instead (right neighbour, else left), or null. */
    fun close(docId: String): String? {
        var neighbour: String? = null
        _tabs.update { list ->
            val i = list.indexOfFirst { it.docId == docId }
            if (i < 0) return@update list
            neighbour = list.getOrNull(i + 1)?.docId ?: list.getOrNull(i - 1)?.docId
            list.toMutableList().also { it.removeAt(i) }
        }
        return neighbour
    }

    fun clear() { _tabs.value = emptyList() }
}

/** Keys the editor understands, independent of the platform key codes. */
enum class ShortcutKey { Z, Y, F, P, E, H, L, N, B, C, X, V, D, A, EQUALS, PLUS, MINUS, DELETE, ESCAPE, PAGE_UP, PAGE_DOWN }

enum class EditorAction {
    UNDO, REDO, FIND, TOOL_PEN, TOOL_ERASER, TOOL_HIGHLIGHTER, TOOL_LASSO, ADD_PAGE, ZOOM_IN, ZOOM_OUT,
    FOCUS_MODE, BOOKMARK, COPY, CUT, PASTE, DUPLICATE, DELETE_SELECTION, SELECT_ALL, ESCAPE, PREV_PAGE, NEXT_PAGE,
}

/** Hardware keyboard shortcuts (Samsung DeX, Book Cover Keyboard). */
object EditorShortcuts {
    fun resolve(key: ShortcutKey, ctrl: Boolean, shift: Boolean, alt: Boolean): EditorAction? {
        if (alt) return null
        if (ctrl) {
            return when (key) {
                ShortcutKey.Z -> if (shift) EditorAction.REDO else EditorAction.UNDO
                ShortcutKey.Y -> if (shift) null else EditorAction.REDO
                ShortcutKey.F -> if (shift) EditorAction.FOCUS_MODE else EditorAction.FIND
                ShortcutKey.N -> if (shift) null else EditorAction.ADD_PAGE
                ShortcutKey.B -> if (shift) null else EditorAction.BOOKMARK
                ShortcutKey.EQUALS, ShortcutKey.PLUS -> EditorAction.ZOOM_IN
                ShortcutKey.MINUS -> if (shift) null else EditorAction.ZOOM_OUT
                ShortcutKey.C -> if (shift) null else EditorAction.COPY
                ShortcutKey.X -> if (shift) null else EditorAction.CUT
                ShortcutKey.V -> if (shift) null else EditorAction.PASTE
                ShortcutKey.D -> if (shift) null else EditorAction.DUPLICATE
                ShortcutKey.A -> if (shift) null else EditorAction.SELECT_ALL
                else -> null
            }
        }
        if (shift) return null
        return when (key) {
            ShortcutKey.P -> EditorAction.TOOL_PEN
            ShortcutKey.E -> EditorAction.TOOL_ERASER
            ShortcutKey.H -> EditorAction.TOOL_HIGHLIGHTER
            ShortcutKey.L -> EditorAction.TOOL_LASSO
            ShortcutKey.DELETE -> EditorAction.DELETE_SELECTION
            ShortcutKey.ESCAPE -> EditorAction.ESCAPE
            ShortcutKey.PAGE_UP -> EditorAction.PREV_PAGE
            ShortcutKey.PAGE_DOWN -> EditorAction.NEXT_PAGE
            else -> null
        }
    }
}

/** Image sizing for inserted pictures and canvas decoding. */
object ImageMath {
    const val MAX_STORED_PX = 4096
    const val MAX_DECODE_PX = 2048

    /** Largest power-of-two sample size that keeps the longer side at least [maxDim]. */
    fun sampleSize(srcW: Int, srcH: Int, maxDim: Int): Int {
        if (srcW <= 0 || srcH <= 0 || maxDim <= 0) return 1
        var sample = 1
        val longer = max(srcW, srcH)
        while (longer / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    /** Dimensions scaled down (never up) so the longer side is at most [maxDim]. */
    fun fitWithin(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val longer = max(w, h)
        if (longer <= maxDim) return w to h
        val scale = maxDim.toDouble() / longer
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }
}

/** Colour helpers for the custom colour picker (ARGB ints). */
object ColorMath {
    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Parses "#RRGGBB", "RRGGBB", "#RGB" or "#AARRGGBB"; null if malformed. */
    fun parseHex(text: String): Int? {
        val s = text.trim().removePrefix("#")
        val v = when (s.length) {
            3 -> s.map { "$it$it" }.joinToString("").toLongOrNull(16)?.or(0xFF000000)
            6 -> s.toLongOrNull(16)?.or(0xFF000000)
            8 -> s.toLongOrNull(16)
            else -> null
        } ?: return null
        return v.toInt()
    }

    /** h in 0..360, s and v in 0..1. */
    fun hsvToArgb(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v.coerceIn(0f, 1f) * s.coerceIn(0f, 1f)
        val x = c * (1 - abs(hh % 2 - 1))
        val m = v.coerceIn(0f, 1f) - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (alpha.coerceIn(0, 255) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Returns [h, s, v]. */
    fun argbToHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(h, s, mx)
    }

    /** Relative luminance-ish test for choosing a contrasting check mark. */
    fun isLight(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }
}
