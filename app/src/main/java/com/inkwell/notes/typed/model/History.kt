package com.inkwell.notes.typed.model

/**
 * Snapshot undo/redo. Callers [record] the state *before* each change. Consecutive changes
 * with the same coalescing key within [coalesceMs] (continuous typing in one block) form a
 * single undo step.
 */
class History<T>(private val limit: Int = 200, private val coalesceMs: Long = 1200) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var lastKey: String? = null
    private var lastAt: Long = 0

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun record(before: T, coalesceKey: String? = null, now: Long = System.currentTimeMillis()) {
        val coalesce = coalesceKey != null && coalesceKey == lastKey && now - lastAt <= coalesceMs && undo.isNotEmpty()
        if (!coalesce) {
            undo.addLast(before)
            while (undo.size > limit) undo.removeFirst()
        }
        redo.clear()
        lastKey = coalesceKey
        lastAt = now
    }

    /** Ends the current coalescing run so the next change becomes its own step. */
    fun breakCoalescing() {
        lastKey = null
    }

    fun undo(current: T): T? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastKey = null
        return prev
    }

    fun redo(current: T): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastKey = null
        return next
    }

    fun clear() {
        undo.clear(); redo.clear(); lastKey = null
    }
}
