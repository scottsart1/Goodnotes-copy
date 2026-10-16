package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.ItemRef
import com.inkwell.notes.ink.ItemType

/**
 * Uniform accessors over the page item model classes (Stroke, ShapeItem, TextItem, ImageItem,
 * StickyItem, ConnectorItem). Items are passed around as [Any]; every function accepts only
 * those six types. Pure Kotlin, unit tested.
 */
object Items {
    fun type(item: Any): ItemType = when (item) {
        is Stroke -> ItemType.STROKE
        is ShapeItem -> ItemType.SHAPE
        is TextItem -> ItemType.TEXT
        is ImageItem -> ItemType.IMAGE
        is StickyItem -> ItemType.STICKY
        is ConnectorItem -> ItemType.CONNECTOR
        else -> throw IllegalArgumentException("Not a page item: ${item::class.java.simpleName}")
    }

    fun id(item: Any): String = when (item) {
        is Stroke -> item.id
        is ShapeItem -> item.id
        is TextItem -> item.id
        is ImageItem -> item.id
        is StickyItem -> item.id
        is ConnectorItem -> item.id
        else -> throw IllegalArgumentException("Not a page item")
    }

    fun z(item: Any): Int = when (item) {
        is Stroke -> item.z
        is ShapeItem -> item.z
        is TextItem -> item.z
        is ImageItem -> item.z
        is StickyItem -> item.z
        is ConnectorItem -> item.z
        else -> 0
    }

    fun groupId(item: Any): String? = when (item) {
        is Stroke -> item.groupId
        is ShapeItem -> item.groupId
        is TextItem -> item.groupId
        is ImageItem -> item.groupId
        is StickyItem -> item.groupId
        else -> null
    }

    fun audio(item: Any): AudioAnchor? = when (item) {
        is Stroke -> item.audio
        is ShapeItem -> item.audio
        is TextItem -> item.audio
        is ImageItem -> item.audio
        else -> null
    }

    fun ref(pageId: String, item: Any): ItemRef = ItemRef(pageId, id(item), type(item))

    fun isHighlighter(item: Any): Boolean = item is Stroke && item.pen == PenType.HIGHLIGHTER
    fun isHandwriting(item: Any): Boolean = item is Stroke && item.pen != PenType.HIGHLIGHTER

    fun withZ(item: Any, z: Int): Any = when (item) {
        is Stroke -> item.copy(z = z)
        is ShapeItem -> item.copy(z = z)
        is TextItem -> item.copy(z = z)
        is ImageItem -> item.copy(z = z)
        is StickyItem -> item.copy(z = z)
        is ConnectorItem -> item.copy(z = z)
        else -> item
    }

    fun withId(item: Any, id: String): Any = when (item) {
        is Stroke -> item.copy(id = id)
        is ShapeItem -> item.copy(id = id)
        is TextItem -> item.copy(id = id)
        is ImageItem -> item.copy(id = id)
        is StickyItem -> item.copy(id = id)
        is ConnectorItem -> item.copy(id = id)
        else -> item
    }

    fun withGroup(item: Any, groupId: String?): Any = when (item) {
        is Stroke -> item.copy(groupId = groupId)
        is ShapeItem -> item.copy(groupId = groupId)
        is TextItem -> item.copy(groupId = groupId)
        is ImageItem -> item.copy(groupId = groupId)
        is StickyItem -> item.copy(groupId = groupId)
        else -> item
    }

    /** Attaches an audio anchor where the model supports one (stickies/connectors have none). */
    fun withAudio(item: Any, anchor: AudioAnchor?): Any = when (item) {
        is Stroke -> item.copy(audio = anchor)
        is ShapeItem -> item.copy(audio = anchor)
        is TextItem -> item.copy(audio = anchor)
        is ImageItem -> item.copy(audio = anchor)
        else -> item
    }

    /** Remaps connector attachments after ids were regenerated (e.g. paste). */
    fun remapConnector(item: Any, ids: Map<String, String>): Any =
        if (item is ConnectorItem) item.copy(fromItemId = item.fromItemId?.let { ids[it] }, toItemId = item.toItemId?.let { ids[it] }) else item

    fun translate(item: Any, dx: Float, dy: Float): Any = when (item) {
        is Stroke -> {
            val p = item.points.copyOf()
            var i = 0
            while (i + 1 < p.size) { p[i] += dx; p[i + 1] += dy; i += InkPoints.STRIDE }
            item.copy(points = p)
        }
        is ShapeItem -> {
            val p = item.points.copyOf()
            var i = 0
            while (i + 1 < p.size) { p[i] += dx; p[i + 1] += dy; i += 2 }
            item.copy(points = p)
        }
        is TextItem -> item.copy(x = item.x + dx, y = item.y + dy)
        is ImageItem -> item.copy(x = item.x + dx, y = item.y + dy)
        is StickyItem -> item.copy(x = item.x + dx, y = item.y + dy)
        is ConnectorItem -> item.copy(fromX = item.fromX + dx, fromY = item.fromY + dy, toX = item.toX + dx, toY = item.toY + dy)
        else -> item
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF

    /** Applies a new colour while keeping each item's own translucency (highlighters stay translucent). */
    fun recolor(item: Any, color: Int): Any {
        fun keepAlpha(old: Int): Int = withAlpha(color, alphaOf(old) * alphaOf(color) / 255)
        return when (item) {
            is Stroke -> item.copy(color = keepAlpha(item.color))
            is ShapeItem -> item.copy(strokeColor = keepAlpha(item.strokeColor), fillColor = if (item.fillColor == 0) 0 else keepAlpha(item.fillColor))
            is TextItem -> item.copy(color = keepAlpha(item.color))
            is StickyItem -> item.copy(color = keepAlpha(item.color))
            is ConnectorItem -> item.copy(color = keepAlpha(item.color))
            else -> item
        }
    }

    fun withWidth(item: Any, width: Float): Any {
        val w = width.coerceIn(0.1f, 200f)
        return when (item) {
            is Stroke -> item.copy(width = w)
            is ShapeItem -> item.copy(strokeWidth = w)
            is ConnectorItem -> item.copy(width = w)
            else -> item
        }
    }

    fun withOpacity(item: Any, opacity: Float): Any {
        val a = (opacity.coerceIn(0.05f, 1f) * 255f + 0.5f).toInt()
        return when (item) {
            is Stroke -> item.copy(color = withAlpha(item.color, a))
            is ShapeItem -> item.copy(strokeColor = withAlpha(item.strokeColor, a), fillColor = if (item.fillColor == 0) 0 else withAlpha(item.fillColor, a))
            is TextItem -> item.copy(color = withAlpha(item.color, a))
            is ImageItem -> item.copy(opacity = opacity.coerceIn(0.05f, 1f))
            is StickyItem -> item.copy(color = withAlpha(item.color, a))
            is ConnectorItem -> item.copy(color = withAlpha(item.color, a))
            else -> item
        }
    }
}
