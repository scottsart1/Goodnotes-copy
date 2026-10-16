package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.DockMetrics
import com.inkwell.notes.audio.logic.DockMode
import com.inkwell.notes.audio.logic.DockPlacement
import com.inkwell.notes.audio.logic.DockStateMachine
import com.inkwell.notes.core.model.DockSide
import org.junit.Assert.assertEquals
import org.junit.Test

class DockStateMachineTest {
    // A 1480 x 924 tablet at density 1 (px == dp).
    private val m = DockMetrics(
        containerWidth = 1480f, containerHeight = 924f,
        capsuleWidth = 64f, capsuleHeight = 232f, sliverTouchHeight = 140f,
        margin = 12f, collapseDistance = 32f, expandDistance = 16f, flingVelocity = 700f,
    )
    private val rightExpanded = DockPlacement(DockSide.RIGHT, DockMode.EXPANDED)
    private val rightCollapsed = DockPlacement(DockSide.RIGHT, DockMode.COLLAPSED)
    private val leftExpanded = DockPlacement(DockSide.LEFT, DockMode.EXPANDED)
    private val leftCollapsed = DockPlacement(DockSide.LEFT, DockMode.COLLAPSED)

    @Test
    fun initialHonoursStartCollapsed() {
        assertEquals(rightCollapsed, DockStateMachine.initial(DockSide.RIGHT, startCollapsed = true))
        assertEquals(leftExpanded, DockStateMachine.initial(DockSide.LEFT, startCollapsed = false))
    }

    @Test
    fun chevronCollapsesAndTapExpands() {
        assertEquals(rightCollapsed, DockStateMachine.onChevron(rightExpanded))
        assertEquals(rightExpanded, DockStateMachine.onSliverTap(rightCollapsed))
        assertEquals(leftExpanded, DockStateMachine.onSliverTap(leftCollapsed))
    }

    @Test
    fun dragTowardEdgeCollapses() {
        assertEquals(rightCollapsed, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = 40f, vx = 0f, vy = 0f))
        assertEquals(leftCollapsed, DockStateMachine.onCapsuleDragEnd(leftExpanded, m, dx = -40f, vx = 0f, vy = 0f))
    }

    @Test
    fun smallDragSpringsBack() {
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = 20f, vx = 0f, vy = 0f))
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = -200f, vx = 0f, vy = 0f))
    }

    @Test
    fun flingTowardEdgeCollapsesEvenWhenShort() {
        assertEquals(rightCollapsed, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = 8f, vx = 1_200f, vy = 100f))
        assertEquals(leftCollapsed, DockStateMachine.onCapsuleDragEnd(leftExpanded, m, dx = -8f, vx = -1_200f, vy = 0f))
    }

    @Test
    fun flingBackCancelsCollapse() {
        // Dragged past the threshold but released moving away from the edge.
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = 50f, vx = -900f, vy = 0f))
    }

    @Test
    fun verticalDragNeverCollapses() {
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = 10f, vx = 900f, vy = 3_000f))
    }

    @Test
    fun draggingAcrossTheMiddleSwitchesSides() {
        // Rest centre is at 1480 - 12 - 32 = 1436; moving 800 left puts it at 636 (< 740).
        assertEquals(leftExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = -800f, vx = 0f, vy = 0f))
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(leftExpanded, m, dx = 800f, vx = 0f, vy = 0f))
        // Not far enough: stays.
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = -600f, vx = 0f, vy = 0f))
    }

    @Test
    fun hardFlingAcrossSwitchesSides() {
        assertEquals(leftExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = -320f, vx = -2_000f, vy = 0f))
        // A gentle fling the same way does not.
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, m, dx = -320f, vx = -900f, vy = 0f))
    }

    @Test
    fun sliverExpandsWhenSwipedOutward() {
        assertEquals(rightExpanded, DockStateMachine.onSliverDragEnd(rightCollapsed, m, dx = -20f, vx = 0f, vy = 0f))
        assertEquals(leftExpanded, DockStateMachine.onSliverDragEnd(leftCollapsed, m, dx = 20f, vx = 0f, vy = 0f))
        assertEquals(rightExpanded, DockStateMachine.onSliverDragEnd(rightCollapsed, m, dx = -4f, vx = -800f, vy = 0f))
    }

    @Test
    fun sliverStaysForTinyOrInwardMoves() {
        assertEquals(rightCollapsed, DockStateMachine.onSliverDragEnd(rightCollapsed, m, dx = -6f, vx = 0f, vy = 0f))
        assertEquals(rightCollapsed, DockStateMachine.onSliverDragEnd(rightCollapsed, m, dx = 10f, vx = 0f, vy = 0f))
        assertEquals(rightCollapsed, DockStateMachine.onSliverDragEnd(rightCollapsed, m, dx = -30f, vx = 900f, vy = 0f))
        // Vertical repositioning of the sliver keeps it collapsed.
        assertEquals(rightCollapsed, DockStateMachine.onSliverDragEnd(rightCollapsed, m, dx = -10f, vx = -100f, vy = 2_000f))
    }

    @Test
    fun sliverDraggedAcrossExpandsOnTheOtherSide() {
        assertEquals(leftExpanded, DockStateMachine.onSliverDragEnd(rightCollapsed, m, dx = -900f, vx = 0f, vy = 0f))
        assertEquals(rightExpanded, DockStateMachine.onSliverDragEnd(leftCollapsed, m, dx = 900f, vx = 0f, vy = 0f))
    }

    @Test
    fun wrongModeIsIgnored() {
        assertEquals(rightCollapsed, DockStateMachine.onCapsuleDragEnd(rightCollapsed, m, dx = -900f, vx = 0f, vy = 0f))
        assertEquals(rightExpanded, DockStateMachine.onSliverDragEnd(rightExpanded, m, dx = -900f, vx = 0f, vy = 0f))
    }

    @Test
    fun restAndHiddenPositions() {
        assertEquals(1480f - 12f - 64f, DockStateMachine.capsuleRestX(DockSide.RIGHT, m))
        assertEquals(12f, DockStateMachine.capsuleRestX(DockSide.LEFT, m))
        assertEquals(1480f + 12f, DockStateMachine.capsuleHiddenX(DockSide.RIGHT, m))
        assertEquals(-64f - 12f, DockStateMachine.capsuleHiddenX(DockSide.LEFT, m))
    }

    @Test
    fun verticalPositionStaysOnScreen() {
        assertEquals(128f, DockStateMachine.clampCenterY(0f, m, 232f))
        assertEquals(924f - 128f, DockStateMachine.clampCenterY(5_000f, m, 232f))
        assertEquals(400f, DockStateMachine.clampCenterY(400f, m, 232f))
        assertEquals(128f, DockStateMachine.clampForBoth(10f, m))
        // Too short to fit: centred.
        val short = m.copy(containerHeight = 200f)
        assertEquals(100f, DockStateMachine.clampCenterY(0f, short, 232f))
    }

    @Test
    fun phoneWidthStillSwitchesAtTheMiddle() {
        val phone = m.copy(containerWidth = 400f)
        assertEquals(leftExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, phone, dx = -200f, vx = 0f, vy = 0f))
        assertEquals(rightExpanded, DockStateMachine.onCapsuleDragEnd(rightExpanded, phone, dx = -100f, vx = 0f, vy = 0f))
    }
}
