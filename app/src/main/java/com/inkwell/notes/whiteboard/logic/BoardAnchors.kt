package com.inkwell.notes.whiteboard.logic

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A board item written while a recording ran, with its bounds in board points. */
data class AnchoredItem(val anchor: AudioAnchor, val bounds: Box, val createdAt: Long = 0)

/** The notes written around one moment of a recording: their union [bounds] and time span. */
data class BoardAnchorHit(val bounds: Box, val firstOffsetMs: Long, val lastOffsetMs: Long, val itemCount: Int) {
    /** Playback-highlight window that covers exactly these items. */
    val spanMs: Long get() = (lastOffsetMs - firstOffsetMs).coerceAtLeast(0)
}

/** Outcome of "show the notes written at this moment" on a board. */
sealed interface AnchorSearch {
    /** Notes written at the position, or within the "near" tolerance of it. */
    data class Found(val hit: BoardAnchorHit) : AnchorSearch

    /** Nothing near the position; [nearest] is the closest moment that has notes. */
    data class OnlyFar(val nearest: BoardAnchorHit, val distanceMs: Long) : AnchorSearch

    /** Nothing on the board belongs to that recording. */
    data object NoNotes : AnchorSearch
}

/**
 * Audio anchors on the whiteboard's single infinite page: finding the notes written at a
 * playback position ("show notes written here") and the anchor of a selection ("play from
 * here"). Stickies and connectors carry no anchor in the model, so they never match.
 */
object BoardAnchors {
    /** Items written up to this long before a position count as "written at" it (matches the canvas highlight). */
    const val WINDOW_MS = 4_000L

    /** Beyond this distance the closest notes are offered instead of shown. */
    const val NEAR_MS = 30_000L

    /** Every anchored item of [content] with its (rotation-aware) bounds. */
    fun items(content: PageContent): List<AnchoredItem> {
        val out = ArrayList<AnchoredItem>()
        for (s in content.strokes) {
            val a = s.audio ?: continue
            val b = BoardGeometry.pointsBounds(s.points, InkPoints.STRIDE)?.inset(-s.width / 2f, -s.width / 2f) ?: continue
            out += AnchoredItem(a, b, s.createdAt)
        }
        for (s in content.shapes) {
            val a = s.audio ?: continue
            val raw = BoardGeometry.pointsBounds(s.points, 2) ?: continue
            out += AnchoredItem(a, rotated(raw, s.rotation).inset(-s.strokeWidth / 2f, -s.strokeWidth / 2f), s.createdAt)
        }
        for (t in content.texts) {
            val a = t.audio ?: continue
            out += AnchoredItem(a, rotated(Box(t.x, t.y, t.x + t.width, t.y + t.height), t.rotation), t.createdAt)
        }
        for (i in content.images) {
            val a = i.audio ?: continue
            out += AnchoredItem(a, rotated(Box(i.x, i.y, i.x + i.width, i.y + i.height), i.rotation), i.createdAt)
        }
        for (n in content.stickies) {
            val a = n.audio ?: continue
            out += AnchoredItem(a, Box(n.x, n.y, n.x + n.width, n.y + n.height), n.createdAt)
        }
        return out
    }

    fun find(
        content: PageContent,
        recordingId: String,
        positionMs: Long,
        windowMs: Long = WINDOW_MS,
        nearMs: Long = NEAR_MS,
    ): AnchorSearch = find(items(content), recordingId, positionMs, windowMs, nearMs)

    /**
     * Notes of [recordingId] written within [windowMs] before [positionMs]. When there are
     * none, the closest moment with notes: the cluster written just before it (when it lies
     * before the position) or just after it, returned as [AnchorSearch.Found] within [nearMs]
     * and as [AnchorSearch.OnlyFar] beyond.
     */
    fun find(items: List<AnchoredItem>, recordingId: String, positionMs: Long, windowMs: Long, nearMs: Long): AnchorSearch {
        val mine = items.filter { it.anchor.recordingId == recordingId && !it.bounds.width.isNaN() && !it.bounds.height.isNaN() }
        if (mine.isEmpty()) return AnchorSearch.NoNotes
        val window = max(0L, windowMs)
        cluster(mine, positionMs - window, positionMs)?.let { return AnchorSearch.Found(it) }

        // Closest in time; on a tie, the notes written before the position.
        val nearest = mine.minWith(compareBy<AnchoredItem>({ abs(it.anchor.offsetMs - positionMs) }, { it.anchor.offsetMs }))
        val t = nearest.anchor.offsetMs
        val hit = (if (t < positionMs) cluster(mine, t - window, t) else cluster(mine, t, t + window))
            ?: BoardAnchorHit(nearest.bounds, t, t, 1)
        val distance = abs(t - positionMs)
        return if (distance <= nearMs) AnchorSearch.Found(hit) else AnchorSearch.OnlyFar(hit, distance)
    }

    /** Union of the items anchored in [fromMs]..[toMs], or null when there are none. */
    private fun cluster(items: List<AnchoredItem>, fromMs: Long, toMs: Long): BoardAnchorHit? {
        var bounds: Box? = null
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var count = 0
        for (it in items) {
            val t = it.anchor.offsetMs
            if (t < fromMs || t > toMs) continue
            bounds = bounds?.union(it.bounds) ?: it.bounds
            first = min(first, t)
            last = max(last, t)
            count++
        }
        return bounds?.let { BoardAnchorHit(it, first, last, count) }
    }

    /**
     * The anchor to play a selection from: the one of its first-written item (ties: the
     * earliest offset), or null when nothing in it was written during a recording.
     */
    fun firstAnchor(content: PageContent): AudioAnchor? =
        items(content).minWithOrNull(compareBy<AnchoredItem>({ it.createdAt }, { it.anchor.offsetMs }))?.anchor

    /** Bounds of [b] rotated by [degrees] around its centre (the canvas' rotation pivot). */
    fun rotated(b: Box, degrees: Float): Box {
        if (degrees == 0f || degrees.isNaN()) return b
        val a = degrees * PI.toFloat() / 180f
        val c = abs(cos(a))
        val s = abs(sin(a))
        val hw = (b.width * c + b.height * s) / 2f
        val hh = (b.width * s + b.height * c) / 2f
        return Box(b.centerX - hw, b.centerY - hh, b.centerX + hw, b.centerY + hh)
    }
}
