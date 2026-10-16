package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.library.DropTarget
import com.inkwell.notes.library.ItemSet
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** What a drag carries, plus how the floating label looks. */
data class DragPayload(val items: ItemSet, val label: String, val icon: ImageVector)

/**
 * In-app drag and drop for library cards. Drop targets (folder cards, rail folders, Library,
 * Trash) register their bounds in root coordinates; the dragged card reports the pointer in
 * root coordinates; the hovered target is whichever registered rect contains it and accepts
 * the payload ([canDrop]).
 */
@Stable
class DragDropState {
    var payload by mutableStateOf<DragPayload?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var hovered by mutableStateOf<DropTarget?>(null)
        private set

    /** Whether [DropTarget] accepts the current payload; set by the screen. */
    var canDrop: (ItemSet, DropTarget) -> Boolean = { _, _ -> false }

    private val targets = LinkedHashMap<Any, Pair<DropTarget, Rect>>()

    val isDragging: Boolean get() = payload != null

    fun register(slot: Any, target: DropTarget, bounds: Rect) {
        targets[slot] = target to bounds
    }

    fun unregister(slot: Any) {
        targets.remove(slot)
    }

    fun start(p: DragPayload, at: Offset) {
        payload = p
        move(at)
    }

    fun move(to: Offset) {
        pointer = to
        val p = payload ?: return
        val hit = targets.values
            .filter { (_, r) -> r.contains(to) }
            .minByOrNull { (_, r) -> r.width * r.height }
            ?.first
        hovered = hit?.takeIf { canDrop(p.items, it) }
    }

    /** Ends the drag; returns the payload and target when dropped on an accepting target. */
    fun end(): Pair<DragPayload, DropTarget>? {
        val p = payload
        val t = hovered
        cancel()
        return if (p != null && t != null) p to t else null
    }

    fun cancel() {
        payload = null
        hovered = null
    }
}

/** Registers this element as a drop target while it is composed. */
@Composable
internal fun Modifier.dropTarget(state: DragDropState, target: DropTarget?): Modifier {
    val slot = remember { Any() }
    DisposableEffect(state, slot) { onDispose { state.unregister(slot) } }
    if (target == null) {
        state.unregister(slot)
        return this
    }
    return this.onGloballyPositioned { state.register(slot, target, it.boundsInRoot()) }
}

/**
 * Tap, long press, right click (mouse or stylus secondary button) and drag-after-long-press for
 * one library item. Shows press feedback through [interactionSource]. Dragging starts once the
 * pointer moves past touch slop after the long press; the drop is resolved through [drag].
 */
@Composable
internal fun Modifier.itemGestures(
    key: Any,
    interactionSource: MutableInteractionSource,
    drag: DragDropState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSecondaryClick: (Offset) -> Unit,
    payload: () -> DragPayload?,
    onDrop: (DragPayload, DropTarget) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val secondary by rememberUpdatedState(onSecondaryClick)
    val currentPayload by rememberUpdatedState(payload)
    val drop by rememberUpdatedState(onDrop)

    fun toRoot(local: Offset): Offset = coords[0]?.takeIf { it.isAttached }?.localToRoot(local) ?: local

    return this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(key) {
            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Mouse right button or the stylus button held while tapping.
                if (currentEvent.buttons.isSecondaryPressed) {
                    down.consume()
                    secondary(down.position)
                    waitForUpOrCancellation()?.consume()
                    return@awaitEachGesture
                }
                var press: PressInteraction.Press? = null
                var ended = false
                var up: PointerInputChange? = null
                // Short delay before showing the press so scrolling does not flash cards.
                withTimeoutOrNull(PRESS_DELAY_MS) {
                    up = waitForUpOrCancellation()
                    ended = true
                }
                if (!ended) {
                    press = PressInteraction.Press(down.position).also { interactionSource.tryEmit(it) }
                    withTimeoutOrNull((longPressTimeout - PRESS_DELAY_MS).coerceAtLeast(1L)) {
                        up = waitForUpOrCancellation()
                        ended = true
                    }
                }
                if (ended) {
                    val u = up
                    if (u != null) {
                        val p = press ?: PressInteraction.Press(down.position).also { interactionSource.tryEmit(it) }
                        interactionSource.tryEmit(PressInteraction.Release(p))
                        u.consume()
                        tap()
                    } else {
                        press?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                    }
                    return@awaitEachGesture
                }
                // Long press reached.
                press?.let { interactionSource.tryEmit(PressInteraction.Release(it)) }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                longPress()
                val p = currentPayload()
                var dragging = false
                var travelled = Offset.Zero
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            break
                        }
                        travelled += change.positionChange()
                        if (!dragging && p != null && travelled.getDistance() > slop) {
                            dragging = true
                            drag.start(p, toRoot(change.position))
                        }
                        if (dragging) drag.move(toRoot(change.position))
                        change.consume()
                    }
                    if (dragging) drag.end()?.let { (payload, target) -> drop(payload, target) }
                } catch (c: CancellationException) {
                    if (dragging) drag.cancel()
                    throw c
                }
            }
        }
}

private const val PRESS_DELAY_MS = 80L

/** The floating label that follows the pointer while dragging. Place in a full-screen Box. */
@Composable
internal fun DragGhost(state: DragDropState, rootOffset: Offset) {
    val p = state.payload ?: return
    val c = Inkwell.colors
    Box(
        Modifier
            // Read the pointer in the layout phase only, so following the pen does not recompose.
            .offset {
                val pos = state.pointer - rootOffset
                IntOffset((pos.x + 14f).roundToInt(), (pos.y + 14f).roundToInt())
            }
            .widthIn(max = 260.dp)
            .clip(R12)
            .background(c.surface)
            .border(1.dp, if (state.hovered != null) c.accent else c.outline, R12)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(p.icon, null, Modifier.size(18.dp), tint = if (state.hovered != null) c.accent else c.inkMuted)
            Spacer(Modifier.width(8.dp))
            Text(p.label, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
