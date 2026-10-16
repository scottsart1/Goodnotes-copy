package com.inkwell.notes.ink.render

import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke

/**
 * Paint order of a page's items. Items merge by z (images, stickies and connectors sit beneath
 * strokes, shapes and text of the same z). Within every run of consecutive strokes, highlighter
 * strokes are drawn first so highlighting always lies beneath handwriting, while images and
 * shapes keep their stacking relative to ink. Pure Kotlin.
 */
object RenderOrder {
    private class Entry(val key: Long, val seq: Int, val item: Any)

    fun order(content: PageContent): List<Any> {
        val n = content.strokes.size + content.shapes.size + content.texts.size + content.images.size +
            content.stickies.size + content.connectors.size
        val entries = ArrayList<Entry>(n)
        var seq = 0
        content.strokes.forEach { entries += Entry(it.z * 2L + 1, seq++, it) }
        content.shapes.forEach { entries += Entry(it.z * 2L + 1, seq++, it) }
        content.texts.forEach { entries += Entry(it.z * 2L + 1, seq++, it) }
        content.images.forEach { entries += Entry(it.z * 2L, seq++, it) }
        content.stickies.forEach { entries += Entry(it.z * 2L, seq++, it) }
        content.connectors.forEach { entries += Entry(it.z * 2L, seq++, it) }
        entries.sortWith(compareBy<Entry> { it.key }.thenBy { it.seq })
        val out = ArrayList<Any>(n)
        var i = 0
        while (i < entries.size) {
            if (entries[i].item !is Stroke) { out += entries[i].item; i++; continue }
            var j = i
            while (j < entries.size && entries[j].item is Stroke) j++
            // Run [i, j): highlighters first, then ink, each in their original order.
            for (k in i until j) { val s = entries[k].item as Stroke; if (s.pen == PenType.HIGHLIGHTER) out += s }
            for (k in i until j) { val s = entries[k].item as Stroke; if (s.pen != PenType.HIGHLIGHTER) out += s }
            i = j
        }
        return out
    }
}
