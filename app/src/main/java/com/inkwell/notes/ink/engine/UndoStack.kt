package com.inkwell.notes.ink.engine

/**
 * Bounded undo/redo history. Pushing a new command clears the redo side; the oldest command is
 * dropped once [capacity] is reached. Pure Kotlin, unit tested.
 */
class UndoStack<C : Any>(val capacity: Int = 200) {
    private val undo = ArrayDeque<C>()
    private val redo = ArrayDeque<C>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoSize: Int get() = undo.size
    val redoSize: Int get() = redo.size

    fun push(command: C) {
        undo.addLast(command)
        while (undo.size > capacity) undo.removeFirst()
        redo.clear()
    }

    fun peekUndo(): C? = undo.lastOrNull()
    fun peekRedo(): C? = redo.lastOrNull()

    /** Moves the newest command to the redo side and returns it. */
    fun popUndo(): C? {
        val c = undo.removeLastOrNull() ?: return null
        redo.addLast(c)
        return c
    }

    /** Moves the newest undone command back to the undo side and returns it. */
    fun popRedo(): C? {
        val c = redo.removeLastOrNull() ?: return null
        undo.addLast(c)
        return c
    }

    /** Drops a command that could not be applied (e.g. its page was deleted). */
    fun discard(command: C) {
        undo.remove(command)
        redo.remove(command)
    }

    /** Removes every command matching [predicate] (e.g. commands of a removed page). */
    fun removeIf(predicate: (C) -> Boolean) {
        undo.removeAll(predicate)
        redo.removeAll(predicate)
    }

    fun clear() {
        undo.clear(); redo.clear()
    }
}
