package com.inkwell.notes.library

/** Where dragged items can be dropped. */
sealed interface DropTarget {
    /** The library's top level. */
    data object Root : DropTarget

    data class IntoFolder(val folderId: String) : DropTarget

    data object Trash : DropTarget
}

/** What is being dragged or acted on: documents and folders by id. */
data class ItemSet(val documentIds: Set<String> = emptySet(), val folderIds: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = documentIds.isEmpty() && folderIds.isEmpty()
    val size: Int get() = documentIds.size + folderIds.size
}

/** The effective operation for a drop: what actually moves. */
data class DropPlan(
    val allowed: Boolean,
    val documentIds: List<String>,
    val folderIds: List<String>,
    val reason: Reason = Reason.OK,
) {
    enum class Reason { OK, NOTHING_TO_MOVE, INTO_ITSELF, UNKNOWN_TARGET }

    val count: Int get() = documentIds.size + folderIds.size

    companion object {
        fun refused(reason: Reason) = DropPlan(false, emptyList(), emptyList(), reason)
    }
}

/**
 * Cycle-safe rules for moving documents and folders (drag and drop and "Move to folder").
 *
 * - Selections are normalised first: a folder inside another selected folder, and a document
 *   inside a selected folder, travel with their ancestor instead of being moved separately
 *   (moving them separately would pull them out of the folder being moved).
 * - A folder can never be dropped into itself or into one of its own sub-folders.
 * - Items already at the target are skipped; a drop where nothing would move is refused, so the
 *   target does not highlight.
 * - Unknown (deleted or trashed) target folders are refused.
 */
object DropRules {

    fun normalize(items: ItemSet, index: FolderIndex, folderOf: (String) -> String?): ItemSet {
        val folders = items.folderIds.filter { it in index }
        val topFolders = folders.filter { f ->
            index.breadcrumb(f).dropLast(1).none { ancestor -> ancestor.id in items.folderIds }
        }
        val covered = topFolders.flatMapTo(HashSet()) { index.subtree(it) }
        val docs = items.documentIds.filter { d -> folderOf(d)?.let { it !in covered } ?: true }
        return ItemSet(docs.toSet(), topFolders.toSet())
    }

    fun plan(items: ItemSet, target: DropTarget, index: FolderIndex, folderOf: (String) -> String?): DropPlan {
        val n = normalize(items, index, folderOf)
        if (n.isEmpty) return DropPlan.refused(DropPlan.Reason.NOTHING_TO_MOVE)
        return when (target) {
            DropTarget.Trash -> DropPlan(true, n.documentIds.sorted(), n.folderIds.sorted())
            DropTarget.Root -> {
                val docs = n.documentIds.filter { folderOf(it) != null && folderOf(it) in index }.sorted()
                val folders = n.folderIds.filter { index.parentOf(it) != null }.sorted()
                if (docs.isEmpty() && folders.isEmpty()) DropPlan.refused(DropPlan.Reason.NOTHING_TO_MOVE)
                else DropPlan(true, docs, folders)
            }
            is DropTarget.IntoFolder -> {
                val t = target.folderId
                if (t !in index) return DropPlan.refused(DropPlan.Reason.UNKNOWN_TARGET)
                if (n.folderIds.any { f -> index.isWithin(t, f) }) return DropPlan.refused(DropPlan.Reason.INTO_ITSELF)
                val docs = n.documentIds.filter { folderOf(it) != t }.sorted()
                val folders = n.folderIds.filter { index.parentOf(it)?.id != t }.sorted()
                if (docs.isEmpty() && folders.isEmpty()) DropPlan.refused(DropPlan.Reason.NOTHING_TO_MOVE)
                else DropPlan(true, docs, folders)
            }
        }
    }

    /** Targets a picker should disable for [items] (the folders themselves and their sub-folders). */
    fun forbiddenTargets(items: ItemSet, index: FolderIndex): Set<String> =
        items.folderIds.filter { it in index }.flatMapTo(HashSet()) { index.subtree(it) }
}
