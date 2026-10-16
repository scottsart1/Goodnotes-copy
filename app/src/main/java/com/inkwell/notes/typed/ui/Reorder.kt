package com.inkwell.notes.typed.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for the block list. The dragged item follows the finger through
 * [offsetY]; when its centre passes a neighbour the two swap via [onMove], and the list
 * scrolls when the item nears an edge. Item index == block index (no header items).
 */
@Stable
class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onEnd: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null); private set
    var offsetY by mutableFloatStateOf(0f); private set
    private var autoScroll: Job? = null
    private var scrollSpeed = 0f

    fun start(key: Any) {
        draggingKey = key
        offsetY = 0f
    }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        offsetY += dy
        swapIfNeeded(key)
        updateAutoScroll()
    }

    private fun swapIfNeeded(key: Any) {
        val items = listState.layoutInfo.visibleItemsInfo
        val cur = items.firstOrNull { it.key == key } ?: return
        val center = cur.offset + offsetY + cur.size / 2f
        val target = items.firstOrNull {
            it.key != key && it.key is String && center > it.offset && center < it.offset + it.size
        } ?: return
        onMove(cur.index, target.index)
        // The item is laid out at its new slot next frame; keep it under the finger.
        offsetY += if (target.index > cur.index) -target.size.toFloat() else target.size.toFloat()
    }

    private fun updateAutoScroll() {
        val key = draggingKey ?: return
        val info = listState.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val top = cur.offset + offsetY
        val bottom = top + cur.size
        val viewStart = info.viewportStartOffset.toFloat()
        val viewEnd = info.viewportEndOffset.toFloat()
        scrollSpeed = when {
            top < viewStart + edgePx -> -((viewStart + edgePx - top) / edgePx).coerceIn(0f, 1f) * 18f
            bottom > viewEnd - edgePx -> ((bottom - (viewEnd - edgePx)) / edgePx).coerceIn(0f, 1f) * 18f
            else -> 0f
        }
        if (scrollSpeed != 0f && autoScroll?.isActive != true) {
            autoScroll = scope.launch {
                while (isActive && draggingKey != null && scrollSpeed != 0f) {
                    val consumed = listState.scrollBy(scrollSpeed)
                    if (consumed == 0f) break
                    offsetY += consumed
                    draggingKey?.let { swapIfNeeded(it) }
                    delay(16)
                }
            }
        }
    }

    fun end() {
        draggingKey = null
        offsetY = 0f
        scrollSpeed = 0f
        autoScroll?.cancel()
        onEnd()
    }
}
