package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.PageDiff

/** What an undoable command needs from the engine. */
interface CommandTarget {
    /** Current content of a loaded page, or null when the page is gone. */
    fun contentOf(pageId: String): PageContent?

    /** Installs new content for a page as part of undo/redo ([changed] limits re-rendering). */
    fun setContent(pageId: String, content: PageContent, changed: Bounds?)
}

/**
 * An undoable edit. Implementations must be deterministic and touch only [pageIds]. Extension
 * point: tools (lasso transforms, shape recognition, gestures) can push their own commands.
 */
interface InkCommand {
    val label: String
    val pageIds: Set<String>
    /** Returns false when the command can no longer be applied. */
    fun undo(target: CommandTarget): Boolean
    fun redo(target: CommandTarget): Boolean
}

/** The standard command: per-page content diffs, applied forward/backward. */
class ContentDiffCommand(override val label: String, val diffs: List<PageDiff>) : InkCommand {
    override val pageIds: Set<String> = diffs.mapTo(LinkedHashSet()) { it.pageId }

    override fun undo(target: CommandTarget): Boolean = applyAll(target, forward = false)
    override fun redo(target: CommandTarget): Boolean = applyAll(target, forward = true)

    private fun applyAll(target: CommandTarget, forward: Boolean): Boolean {
        var applied = false
        for (d in diffs) {
            val current = target.contentOf(d.pageId) ?: continue
            val next = if (forward) ContentOps.applyForward(current, d) else ContentOps.applyBackward(current, d)
            target.setContent(d.pageId, next, d.changedBounds(current, next))
            applied = true
        }
        return applied
    }

    companion object {
        /** Builds a command from before/after snapshots, or null when nothing changed. */
        fun of(label: String, changes: Map<String, Pair<PageContent, PageContent>>): ContentDiffCommand? {
            val diffs = changes.mapNotNull { (pageId, ba) -> ContentOps.diff(pageId, ba.first, ba.second).takeUnless { it.isEmpty } }
            return if (diffs.isEmpty()) null else ContentDiffCommand(label, diffs)
        }
    }
}
