package com.inkwell.notes.audio.logic

import com.inkwell.notes.core.model.DockSide
import kotlin.math.abs

enum class DockMode { EXPANDED, COLLAPSED }

/** Where the recording control rests: which edge, and whether it is the capsule or the sliver. */
data class DockPlacement(val side: DockSide, val mode: DockMode) {
    val collapsed: Boolean get() = mode == DockMode.COLLAPSED
}

/** Geometry and gesture thresholds in pixels (pixels per second for velocities). */
data class DockMetrics(
    val containerWidth: Float,
    val containerHeight: Float,
    val capsuleWidth: Float,
    val capsuleHeight: Float,
    /** Height of the collapsed sliver's touch target. */
    val sliverTouchHeight: Float,
    /** Gap between the expanded capsule and the screen edge / top / bottom. */
    val margin: Float,
    /** Drag toward the edge that collapses the capsule. */
    val collapseDistance: Float,
    /** Outward drag on the sliver that expands it. */
    val expandDistance: Float,
    /** Horizontal fling speed that counts as a deliberate flick. */
    val flingVelocity: Float,
)

/**
 * Pure decisions for the edge-docked recording control. The composable reports finished
 * gestures; this returns where the control should come to rest. It never touches the
 * recorder: collapsing and expanding are purely visual.
 *
 * Expanded capsule:
 * - dragged so its centre crosses the middle of the screen: moves to the other edge;
 * - flung toward its edge, or dragged toward it by [DockMetrics.collapseDistance]: collapses;
 * - flung hard toward the far side: moves to the other edge;
 * - anything else springs back.
 *
 * Collapsed sliver:
 * - tap: expands;
 * - dragged across the middle: expands on the other edge;
 * - flung or dragged outward (away from its edge) by [DockMetrics.expandDistance]: expands;
 * - otherwise stays collapsed.
 */
object DockStateMachine {
    /** Fling toward the far side must be this many times [DockMetrics.flingVelocity] to switch sides. */
    private const val SWITCH_FLING_FACTOR = 2.5f
    /** ...and must have travelled this fraction of the width. */
    private const val SWITCH_FLING_TRAVEL = 0.2f

    fun initial(side: DockSide, startCollapsed: Boolean) =
        DockPlacement(side, if (startCollapsed) DockMode.COLLAPSED else DockMode.EXPANDED)

    fun onChevron(p: DockPlacement): DockPlacement = p.copy(mode = DockMode.COLLAPSED)

    fun onSliverTap(p: DockPlacement): DockPlacement = p.copy(mode = DockMode.EXPANDED)

    /** +1 when "toward the edge" is +x (right edge), -1 for the left edge. */
    fun edgeSign(side: DockSide): Float = if (side == DockSide.RIGHT) 1f else -1f

    fun other(side: DockSide): DockSide = if (side == DockSide.RIGHT) DockSide.LEFT else DockSide.RIGHT

    /** Left x of the expanded capsule at rest. */
    fun capsuleRestX(side: DockSide, m: DockMetrics): Float =
        if (side == DockSide.RIGHT) m.containerWidth - m.margin - m.capsuleWidth else m.margin

    /** Left x of the capsule when tucked away off-screen. */
    fun capsuleHiddenX(side: DockSide, m: DockMetrics): Float =
        if (side == DockSide.RIGHT) m.containerWidth + m.margin else -m.capsuleWidth - m.margin

    /**
     * End of a drag on the expanded capsule that moved it by [dx] from rest, released with
     * velocity ([vx], [vy]).
     */
    fun onCapsuleDragEnd(p: DockPlacement, m: DockMetrics, dx: Float, vx: Float, vy: Float): DockPlacement {
        if (p.mode != DockMode.EXPANDED) return p
        val sign = edgeSign(p.side)
        val toward = dx * sign
        val vToward = vx * sign
        val center = capsuleRestX(p.side, m) + m.capsuleWidth / 2f + dx
        val crossed = if (p.side == DockSide.RIGHT) center < m.containerWidth / 2f else center > m.containerWidth / 2f
        if (crossed) return DockPlacement(other(p.side), DockMode.EXPANDED)

        val horizontalFling = abs(vx) >= m.flingVelocity && abs(vx) > abs(vy)
        if (horizontalFling) {
            return when {
                vToward > 0 -> p.copy(mode = DockMode.COLLAPSED)
                -vToward >= m.flingVelocity * SWITCH_FLING_FACTOR && -toward >= m.containerWidth * SWITCH_FLING_TRAVEL ->
                    DockPlacement(other(p.side), DockMode.EXPANDED)
                else -> p
            }
        }
        return if (toward >= m.collapseDistance) p.copy(mode = DockMode.COLLAPSED) else p
    }

    /** End of a drag on the collapsed sliver that moved it by [dx], released with ([vx], [vy]). */
    fun onSliverDragEnd(p: DockPlacement, m: DockMetrics, dx: Float, vx: Float, vy: Float): DockPlacement {
        if (p.mode != DockMode.COLLAPSED) return p
        val sign = edgeSign(p.side)
        val outward = -dx * sign
        val vOut = -vx * sign
        val edgeX = if (p.side == DockSide.RIGHT) m.containerWidth else 0f
        val fingerX = edgeX + dx
        val crossed = if (p.side == DockSide.RIGHT) fingerX < m.containerWidth / 2f else fingerX > m.containerWidth / 2f
        if (crossed) return DockPlacement(other(p.side), DockMode.EXPANDED)

        val horizontalFling = abs(vx) >= m.flingVelocity && abs(vx) > abs(vy)
        if (horizontalFling) return if (vOut > 0) p.copy(mode = DockMode.EXPANDED) else p
        return if (outward >= m.expandDistance) p.copy(mode = DockMode.EXPANDED) else p
    }

    /**
     * Keeps the control's vertical centre on screen for a control of [controlHeight].
     * When the container is too short, the control is centred.
     */
    fun clampCenterY(centerY: Float, m: DockMetrics, controlHeight: Float): Float {
        val min = controlHeight / 2f + m.margin
        val max = m.containerHeight - controlHeight / 2f - m.margin
        if (max < min) return m.containerHeight / 2f
        return centerY.coerceIn(min, max)
    }

    /** Centre that keeps both the capsule and the sliver's touch target on screen. */
    fun clampForBoth(centerY: Float, m: DockMetrics): Float =
        clampCenterY(centerY, m, maxOf(m.capsuleHeight, m.sliverTouchHeight))
}
