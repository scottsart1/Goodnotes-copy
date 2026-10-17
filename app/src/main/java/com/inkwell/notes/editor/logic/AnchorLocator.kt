package com.inkwell.notes.editor.logic

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.PageContent
import kotlin.math.abs

/** A note item written at a given moment of a recording. */
data class AnchorHit(val pageIndex: Int, val bounds: Bounds, val offsetMs: Long)

/** Finds the notes written near a playback position ("show notes written here"). */
object AnchorLocator {

    private fun anchored(c: PageContent): List<Pair<AudioAnchor, Bounds?>> =
        c.strokes.mapNotNull { s -> s.audio?.let { it to FragmentOps.strokeBounds(s.points, s.width / 2f) } } +
            c.shapes.mapNotNull { s -> s.audio?.let { it to FragmentOps.pairBounds(s.points, s.strokeWidth / 2f) } } +
            c.texts.mapNotNull { t -> t.audio?.let { it to Bounds(t.x, t.y, t.x + t.width, t.y + t.height) } } +
            c.images.mapNotNull { i -> i.audio?.let { it to Bounds(i.x, i.y, i.x + i.width, i.y + i.height) } } +
            c.stickies.mapNotNull { n -> n.audio?.let { it to Bounds(n.x, n.y, n.x + n.width, n.y + n.height) } }

    /**
     * The first page (lowest index) holding items of [recordingId] anchored within [windowMs]
     * before [positionMs] (the items being written at that moment), with the union of their
     * bounds. When nothing falls in that window, the item closest in time on any page.
     */
    fun find(pages: List<Pair<Int, PageContent>>, recordingId: String, positionMs: Long, windowMs: Long = 4_000): AnchorHit? {
        var closest: AnchorHit? = null
        var closestDelta = Long.MAX_VALUE
        for ((index, content) in pages.sortedBy { it.first }) {
            var windowBounds: Bounds? = null
            var windowOffset = Long.MAX_VALUE
            for ((anchor, b) in anchored(content)) {
                if (anchor.recordingId != recordingId || b == null) continue
                val delta = positionMs - anchor.offsetMs
                if (delta in 0..windowMs) {
                    windowBounds = windowBounds?.union(b) ?: b
                    windowOffset = minOf(windowOffset, anchor.offsetMs)
                }
                val d = abs(delta)
                if (d < closestDelta) {
                    closestDelta = d
                    closest = AnchorHit(index, b, anchor.offsetMs)
                }
            }
            if (windowBounds != null) return AnchorHit(index, windowBounds, windowOffset)
        }
        return closest
    }
}
