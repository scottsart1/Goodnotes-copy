package com.inkwell.notes.editor.logic

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned bounds in page points. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(o: Bounds) = Bounds(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun toArray() = floatArrayOf(left, top, right, bottom)
}

/** Pure operations on page-content fragments (clipboard, "my elements", sending between panes). */
object FragmentOps {

    fun strokeBounds(points: FloatArray, halfWidth: Float = 0f): Bounds? {
        val n = points.size / InkPoints.STRIDE
        if (n == 0) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = points[i * InkPoints.STRIDE + InkPoints.X]
            val y = points[i * InkPoints.STRIDE + InkPoints.Y]
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return Bounds(l - halfWidth, t - halfWidth, r + halfWidth, b + halfWidth)
    }

    fun pairBounds(points: FloatArray, halfWidth: Float = 0f): Bounds? {
        val n = points.size / 2
        if (n == 0) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = points[i * 2]; val y = points[i * 2 + 1]
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return Bounds(l - halfWidth, t - halfWidth, r + halfWidth, b + halfWidth)
    }

    /** Union bounds of every item, or null for an empty fragment. */
    fun bounds(c: PageContent): Bounds? {
        var acc: Bounds? = null
        fun add(b: Bounds?) { if (b != null) acc = acc?.union(b) ?: b }
        c.strokes.forEach { add(strokeBounds(it.points, it.width / 2f)) }
        c.shapes.forEach { add(pairBounds(it.points, it.strokeWidth / 2f)) }
        c.texts.forEach { add(Bounds(it.x, it.y, it.x + it.width, it.y + it.height)) }
        c.images.forEach { add(Bounds(it.x, it.y, it.x + it.width, it.y + it.height)) }
        c.stickies.forEach { add(Bounds(it.x, it.y, it.x + it.width, it.y + it.height)) }
        c.connectors.forEach { add(Bounds(min(it.fromX, it.toX), min(it.fromY, it.toY), max(it.fromX, it.toX), max(it.fromY, it.toY))) }
        return acc
    }

    fun translate(c: PageContent, dx: Float, dy: Float): PageContent {
        if (dx == 0f && dy == 0f) return c
        return c.copy(
            strokes = c.strokes.map { s ->
                val p = s.points.copyOf()
                var i = 0
                while (i + InkPoints.Y < p.size) {
                    p[i + InkPoints.X] += dx
                    p[i + InkPoints.Y] += dy
                    i += InkPoints.STRIDE
                }
                s.copy(points = p)
            },
            shapes = c.shapes.map { s ->
                val p = s.points.copyOf()
                var i = 0
                while (i + 1 < p.size) { p[i] += dx; p[i + 1] += dy; i += 2 }
                s.copy(points = p)
            },
            texts = c.texts.map { it.copy(x = it.x + dx, y = it.y + dy) },
            images = c.images.map { it.copy(x = it.x + dx, y = it.y + dy) },
            stickies = c.stickies.map { it.copy(x = it.x + dx, y = it.y + dy) },
            connectors = c.connectors.map { it.copy(fromX = it.fromX + dx, fromY = it.fromY + dy, toX = it.toX + dx, toY = it.toY + dy) },
        )
    }

    /** Moves the fragment so its bounds start at (0, 0); returns it with its size. */
    fun normalizeToOrigin(c: PageContent): Pair<PageContent, Bounds> {
        val b = bounds(c) ?: return c to Bounds(0f, 0f, 0f, 0f)
        return translate(c, -b.left, -b.top) to Bounds(0f, 0f, b.width, b.height)
    }

    /**
     * Gives every item, group and connector link a new id so a pasted copy never collides with
     * the original. Audio anchors are dropped when [keepAudio] is false (pasting into another
     * document whose recordings differ).
     */
    fun withFreshIds(c: PageContent, keepAudio: Boolean = true, newId: () -> String = { UUID.randomUUID().toString() }): PageContent {
        val ids = HashMap<String, String>()
        val groups = HashMap<String, String>()
        fun id(old: String) = ids.getOrPut(old) { newId() }
        fun group(old: String?) = old?.let { groups.getOrPut(it) { newId() } }
        fun audio(a: AudioAnchor?) = if (keepAudio) a else null
        val strokes = c.strokes.map { it.copy(id = id(it.id), groupId = group(it.groupId), audio = audio(it.audio)) }
        val shapes = c.shapes.map { it.copy(id = id(it.id), groupId = group(it.groupId), audio = audio(it.audio)) }
        val texts = c.texts.map { it.copy(id = id(it.id), groupId = group(it.groupId), audio = audio(it.audio)) }
        val images = c.images.map { it.copy(id = id(it.id), groupId = group(it.groupId), audio = audio(it.audio)) }
        val stickies = c.stickies.map { it.copy(id = id(it.id), groupId = group(it.groupId), audio = audio(it.audio)) }
        val connectors = c.connectors.map {
            it.copy(
                id = id(it.id),
                fromItemId = it.fromItemId?.let { f -> ids[f] },
                toItemId = it.toItemId?.let { t -> ids[t] },
            )
        }
        return PageContent(c.version, strokes, shapes, texts, images, stickies, connectors)
    }

    /** Image asset ids the fragment references (files to copy when it changes document). */
    fun assetIds(c: PageContent): Set<String> = c.images.mapTo(LinkedHashSet()) { it.assetId }

    /** The earliest audio anchor among the items, or null. */
    fun firstAudioAnchor(c: PageContent): AudioAnchor? =
        (c.strokes.mapNotNull { it.audio } + c.shapes.mapNotNull { it.audio } + c.texts.mapNotNull { it.audio } + c.images.mapNotNull { it.audio } +
            c.stickies.mapNotNull { it.audio })
            .minByOrNull { it.offsetMs }

    /** Typed text of the fragment in reading order (top to bottom, then left to right). */
    fun typedText(c: PageContent): String {
        val boxes = c.texts.map { Triple(it.y, it.x, it.text) } + c.stickies.map { Triple(it.y, it.x, it.text) }
        return boxes.sortedWith(compareBy({ it.first }, { it.second })).map { it.third.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun itemCount(c: PageContent): Int =
        c.strokes.size + c.shapes.size + c.texts.size + c.images.size + c.stickies.size + c.connectors.size

    /** True when the selection has more than one item and they are not already one group. */
    fun canGroup(c: PageContent): Boolean {
        if (itemCount(c) < 2) return false
        val groups = groupIds(c)
        return groups.size != 1 || hasUngrouped(c)
    }

    fun canUngroup(c: PageContent): Boolean = groupIds(c).isNotEmpty()

    private fun groupIds(c: PageContent): Set<String?> =
        (c.strokes.map { it.groupId } + c.shapes.map { it.groupId } + c.texts.map { it.groupId } + c.images.map { it.groupId } + c.stickies.map { it.groupId })
            .filterNotNull().toSet()

    private fun hasUngrouped(c: PageContent): Boolean =
        c.strokes.any { it.groupId == null } || c.shapes.any { it.groupId == null } || c.texts.any { it.groupId == null } ||
            c.images.any { it.groupId == null } || c.stickies.any { it.groupId == null }
}
