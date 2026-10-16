package com.inkwell.notes.whiteboard.logic

import kotlin.math.max
import kotlin.math.min

/** Maps board (page-point) coordinates into the minimap's pixel space and back. */
data class MapTransform(val world: Box, val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toMapX(x: Float): Float = offsetX + (x - world.left) * scale
    fun toMapY(y: Float): Float = offsetY + (y - world.top) * scale
    fun toMap(b: Box): Box = Box(toMapX(b.left), toMapY(b.top), toMapX(b.right), toMapY(b.bottom))
    fun toWorldX(mx: Float): Float = world.left + (mx - offsetX) / scale
    fun toWorldY(my: Float): Float = world.top + (my - offsetY) / scale
}

/** Viewport math for the whiteboard minimap and the zoom controls. */
object MinimapMath {
    const val DEFAULT_EXTENT = 1200f

    /**
     * The region the minimap shows: all content plus the current viewport, padded. With no
     * content and no viewport yet, a default square around the origin.
     */
    fun world(content: Box?, viewport: Box?, padding: Float = 0.08f): Box {
        val base = when {
            content != null && viewport != null -> content.union(viewport)
            content != null -> content
            viewport != null -> viewport
            else -> Box(0f, 0f, DEFAULT_EXTENT, DEFAULT_EXTENT)
        }
        val w = max(base.width, 1f)
        val h = max(base.height, 1f)
        val pad = max(w, h) * padding
        return Box(base.centerX - w / 2f - pad, base.centerY - h / 2f - pad, base.centerX + w / 2f + pad, base.centerY + h / 2f + pad)
    }

    /** Fits [world] into a [mapWidth] x [mapHeight] area (minus [inset] on every side), centred. */
    fun transform(world: Box, mapWidth: Float, mapHeight: Float, inset: Float = 0f): MapTransform {
        val aw = max(mapWidth - 2f * inset, 1f)
        val ah = max(mapHeight - 2f * inset, 1f)
        val ww = max(world.width, 1e-3f)
        val wh = max(world.height, 1e-3f)
        val scale = min(aw / ww, ah / wh)
        return MapTransform(world, scale, inset + (aw - ww * scale) / 2f, inset + (ah - wh * scale) / 2f)
    }

    /** The viewport rectangle drawn on the minimap. */
    fun viewportOnMap(t: MapTransform, visible: Box): Box = t.toMap(visible)

    /** Board point under a minimap position: the point to centre the viewport on. */
    fun panTarget(t: MapTransform, mapX: Float, mapY: Float): Pair<Float, Float> = t.toWorldX(mapX) to t.toWorldY(mapY)

    /**
     * New viewport centre while dragging on the minimap. The transform is frozen at drag start
     * (the world grows with the viewport, so a live transform would feed back into the drag).
     */
    fun dragCenter(t: MapTransform, startCenterX: Float, startCenterY: Float, dxMap: Float, dyMap: Float): Pair<Float, Float> =
        (startCenterX + dxMap / t.scale) to (startCenterY + dyMap / t.scale)

    /** Zoom that makes [content] (plus a margin) fit the view, given the current [visible] region at [currentZoom]. */
    fun fitZoom(currentZoom: Float, visible: Box, content: Box, marginFraction: Float = 0.08f, minZoom: Float = 0.05f, maxZoom: Float = 2f): Float {
        if (visible.isEmpty || currentZoom <= 0f) return currentZoom
        val cw = max(content.width, 1f) * (1f + 2f * marginFraction)
        val ch = max(content.height, 1f) * (1f + 2f * marginFraction)
        val factor = min(visible.width / cw, visible.height / ch)
        return (currentZoom * factor).coerceIn(minZoom, maxZoom)
    }

    /** Zoom shown as a whole percentage. */
    fun percent(zoom: Float): Int = (zoom * 100f + 0.5f).toInt().coerceAtLeast(1)
}
