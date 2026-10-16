package com.inkwell.notes.settings.logic

import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ToolType
import java.util.Locale

/** Pure edits of the pen preset list ([com.inkwell.notes.core.model.AppSettings.penPresets]). */
object PresetOps {
    const val MAX_NAME_CHARS = 32

    /** Trimmed, single-spaced, capped name; null when nothing is left. */
    fun cleanName(raw: String): String? {
        val t = raw.replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        return if (t.length <= MAX_NAME_CHARS) t else t.take(MAX_NAME_CHARS).trimEnd()
    }

    fun rename(list: List<PenPreset>, id: String, newName: String): List<PenPreset> {
        val name = cleanName(newName) ?: return list
        return list.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun delete(list: List<PenPreset>, id: String): List<PenPreset> = list.filterNot { it.id == id }

    /** Puts a deleted preset back at [index] (clamped), unless a preset with its id exists. */
    fun restore(list: List<PenPreset>, preset: PenPreset, index: Int): List<PenPreset> {
        if (list.any { it.id == preset.id }) return list
        val i = index.coerceIn(0, list.size)
        return list.toMutableList().apply { add(i, preset) }
    }

    /** Moves the preset [id] by [delta] positions (negative = up), clamped to the list. */
    fun move(list: List<PenPreset>, id: String, delta: Int): List<PenPreset> {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return list
        return moveTo(list, from, from + delta)
    }

    fun moveTo(list: List<PenPreset>, from: Int, to: Int): List<PenPreset> {
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        if (target == from) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(target, item)
        return m
    }

    fun defaults(): List<PenPreset> = PenPreset.Defaults

    fun isDefault(list: List<PenPreset>): Boolean = list == PenPreset.Defaults

    fun penTypeLabel(type: PenType): String = when (type) {
        PenType.BALLPOINT -> "Ballpoint"
        PenType.FOUNTAIN -> "Fountain pen"
        PenType.BRUSH -> "Brush pen"
        PenType.PENCIL -> "Pencil"
        PenType.HIGHLIGHTER -> "Highlighter"
    }

    /** "Fountain pen · 1.8 pt" style summary. */
    fun describe(preset: PenPreset): String {
        val kind = when (preset.tool) {
            ToolType.HIGHLIGHTER -> "Highlighter"
            ToolType.PENCIL -> "Pencil"
            else -> penTypeLabel(preset.pen.type)
        }
        return "$kind · ${formatWidth(preset.pen.width)} pt"
    }

    fun formatWidth(width: Float): String {
        val rounded = Math.round(width * 10f) / 10f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else String.format(Locale.US, "%.1f", rounded)
    }
}
