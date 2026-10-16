package com.inkwell.notes.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

/**
 * Editable vector content of one page. All coordinates are in page points
 * (1/72 in, origin top-left of the unrotated page). Items of every type share one
 * z-order through their [z] field; renderers merge the lists by z (highlighter
 * strokes are drawn beneath regular ink of the same z band).
 *
 * Persisted with kotlinx ProtoBuf by [com.inkwell.notes.core.storage.PageContentStore].
 * Only ever append fields with new @ProtoNumber values; never renumber.
 */
@Serializable
data class PageContent(
    @ProtoNumber(1) val version: Int = 1,
    @ProtoNumber(2) val strokes: List<Stroke> = emptyList(),
    @ProtoNumber(3) val shapes: List<ShapeItem> = emptyList(),
    @ProtoNumber(4) val texts: List<TextItem> = emptyList(),
    @ProtoNumber(5) val images: List<ImageItem> = emptyList(),
    @ProtoNumber(6) val stickies: List<StickyItem> = emptyList(),
    @ProtoNumber(7) val connectors: List<ConnectorItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = strokes.isEmpty() && shapes.isEmpty() && texts.isEmpty() &&
            images.isEmpty() && stickies.isEmpty() && connectors.isEmpty()

    fun maxZ(): Int = maxOf(
        strokes.maxOfOrNull { it.z } ?: 0,
        shapes.maxOfOrNull { it.z } ?: 0,
        texts.maxOfOrNull { it.z } ?: 0,
        images.maxOfOrNull { it.z } ?: 0,
        stickies.maxOfOrNull { it.z } ?: 0,
        connectors.maxOfOrNull { it.z } ?: 0,
    )

    companion object {
        val EMPTY = PageContent()
    }
}

/** Where on a recording's timeline an item was created. */
@Serializable
data class AudioAnchor(
    @ProtoNumber(1) val recordingId: String,
    @ProtoNumber(2) val offsetMs: Long,
)

@Serializable
enum class PenType { BALLPOINT, FOUNTAIN, BRUSH, PENCIL, HIGHLIGHTER }

/**
 * A handwritten stroke. [points] is packed [InkPoints.STRIDE] floats per sample:
 * x, y (page points), pressure (0..1), tilt (radians, 0 = perpendicular), t (ms since [createdAt]).
 * [width] is the nominal width in points before pressure modulation.
 */
@Serializable
data class Stroke(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val pen: PenType,
    @ProtoNumber(3) val color: Int,
    @ProtoNumber(4) val width: Float,
    @ProtoNumber(5) @ProtoPacked val points: FloatArray,
    @ProtoNumber(6) val createdAt: Long,
    @ProtoNumber(7) val z: Int = 0,
    @ProtoNumber(8) val audio: AudioAnchor? = null,
    @ProtoNumber(9) val groupId: String? = null,
    /** 0..1: how strongly pressure changes width for this stroke. */
    @ProtoNumber(10) val pressureSensitivity: Float = 0.6f,
) {
    val pointCount: Int get() = points.size / InkPoints.STRIDE

    override fun equals(other: Any?): Boolean =
        other is Stroke && other.id == id && other.points.contentEquals(points) && other.color == color &&
            other.width == width && other.pen == pen && other.z == z && other.groupId == groupId
    override fun hashCode(): Int = id.hashCode()
}

object InkPoints {
    const val STRIDE = 5
    const val X = 0
    const val Y = 1
    const val PRESSURE = 2
    const val TILT = 3
    const val TIME = 4
}

@Serializable
enum class ShapeKind { LINE, ARROW, DOUBLE_ARROW, RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE, TRIANGLE, POLYGON, STAR }

/**
 * A geometric shape. [points] packs x,y pairs in page points:
 * LINE/ARROW/DOUBLE_ARROW: start, end. RECTANGLE/ROUNDED_RECTANGLE/ELLIPSE: two opposite
 * corners of the unrotated box. TRIANGLE/POLYGON/STAR: vertices in order.
 * [rotation] (degrees) is applied around the box centre for box kinds.
 */
@Serializable
data class ShapeItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val kind: ShapeKind,
    @ProtoNumber(3) @ProtoPacked val points: FloatArray,
    @ProtoNumber(4) val strokeColor: Int,
    @ProtoNumber(5) val fillColor: Int = 0,
    @ProtoNumber(6) val strokeWidth: Float = 2f,
    @ProtoNumber(7) val rotation: Float = 0f,
    @ProtoNumber(8) val z: Int = 0,
    @ProtoNumber(9) val createdAt: Long = 0,
    @ProtoNumber(10) val audio: AudioAnchor? = null,
    @ProtoNumber(11) val groupId: String? = null,
    @ProtoNumber(12) val cornerRadius: Float = 8f,
) {
    override fun equals(other: Any?): Boolean =
        other is ShapeItem && other.id == id && other.points.contentEquals(points) && other.kind == kind &&
            other.strokeColor == strokeColor && other.fillColor == fillColor && other.strokeWidth == strokeWidth &&
            other.rotation == rotation && other.z == z && other.groupId == groupId
    override fun hashCode(): Int = id.hashCode()
}

/** A typed text box placed on the canvas. [text] is plain text with '\n' line breaks. */
@Serializable
data class TextItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
    @ProtoNumber(4) val width: Float,
    @ProtoNumber(5) val height: Float,
    @ProtoNumber(6) val text: String,
    @ProtoNumber(7) val fontSizePt: Float = 14f,
    @ProtoNumber(8) val color: Int = 0xFF1A1916.toInt(),
    @ProtoNumber(9) val bold: Boolean = false,
    @ProtoNumber(10) val italic: Boolean = false,
    @ProtoNumber(11) val rotation: Float = 0f,
    @ProtoNumber(12) val z: Int = 0,
    @ProtoNumber(13) val createdAt: Long = 0,
    @ProtoNumber(14) val background: Int = 0,
    @ProtoNumber(15) val audio: AudioAnchor? = null,
    @ProtoNumber(16) val groupId: String? = null,
    /** Set when this box holds AI output or converted handwriting, for provenance. */
    @ProtoNumber(17) val origin: String? = null,
)

/** An image stored in the document's assets folder ([assetId] = file name). */
@Serializable
data class ImageItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val assetId: String,
    @ProtoNumber(3) val x: Float,
    @ProtoNumber(4) val y: Float,
    @ProtoNumber(5) val width: Float,
    @ProtoNumber(6) val height: Float,
    @ProtoNumber(7) val rotation: Float = 0f,
    @ProtoNumber(8) val opacity: Float = 1f,
    /** Crop insets as fractions of the source image (0..1). */
    @ProtoNumber(9) val cropLeft: Float = 0f,
    @ProtoNumber(10) val cropTop: Float = 0f,
    @ProtoNumber(11) val cropRight: Float = 0f,
    @ProtoNumber(12) val cropBottom: Float = 0f,
    @ProtoNumber(13) val z: Int = 0,
    @ProtoNumber(14) val createdAt: Long = 0,
    @ProtoNumber(15) val groupId: String? = null,
    @ProtoNumber(16) val audio: AudioAnchor? = null,
)

/** Whiteboard sticky note. */
@Serializable
data class StickyItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
    @ProtoNumber(4) val width: Float = 180f,
    @ProtoNumber(5) val height: Float = 180f,
    @ProtoNumber(6) val text: String = "",
    @ProtoNumber(7) val color: Int = 0xFFFFE89A.toInt(),
    @ProtoNumber(8) val z: Int = 0,
    @ProtoNumber(9) val createdAt: Long = 0,
    @ProtoNumber(10) val groupId: String? = null,
)

/**
 * A connector arrow. When [fromItemId]/[toItemId] are set the endpoint follows that
 * item's bounds (nearest edge midpoint); the stored coordinates are the fallback.
 */
@Serializable
data class ConnectorItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val fromItemId: String? = null,
    @ProtoNumber(3) val toItemId: String? = null,
    @ProtoNumber(4) val fromX: Float = 0f,
    @ProtoNumber(5) val fromY: Float = 0f,
    @ProtoNumber(6) val toX: Float = 0f,
    @ProtoNumber(7) val toY: Float = 0f,
    @ProtoNumber(8) val color: Int = 0xFF1A1916.toInt(),
    @ProtoNumber(9) val width: Float = 2f,
    @ProtoNumber(10) val arrowEnd: Boolean = true,
    @ProtoNumber(11) val arrowStart: Boolean = false,
    @ProtoNumber(12) val z: Int = 0,
    @ProtoNumber(13) val createdAt: Long = 0,
    @ProtoNumber(14) val label: String = "",
)
