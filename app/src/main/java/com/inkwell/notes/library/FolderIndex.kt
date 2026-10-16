package com.inkwell.notes.library

import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder

/** One visible row of the folder tree (rail, move picker). */
data class FolderRow(
    val folder: Folder,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

/**
 * Read-only index of the folder hierarchy used by the library UI. Tolerates corrupt data:
 * a folder whose parent is missing (or is itself) is a root, and folders caught in a parent
 * cycle are still reachable (the first of them in sort order becomes a root), so nothing ever
 * disappears from the tree and no walk loops forever.
 *
 * Children are ordered by [comparator] (name by default).
 */
class FolderIndex(
    folders: Collection<Folder>,
    comparator: Comparator<Folder> = LibrarySorting.folderComparator(com.inkwell.notes.core.model.LibrarySort.NAME_ASC),
) {
    private val byId: Map<String, Folder> = folders.associateBy { it.id }
    private val children: Map<String?, List<Folder>>
    val roots: List<Folder>

    init {
        val grouped = HashMap<String?, MutableList<Folder>>()
        for (f in byId.values) {
            val parent = f.parentId?.takeIf { it != f.id && it in byId }
            grouped.getOrPut(parent) { ArrayList() } += f
        }
        // Folders in a cycle have parents, so none of them is reachable from a root. Detach the
        // first folder of each unreachable group so the whole group shows up again.
        val reachable = HashSet<String>()
        fun mark(id: String?) {
            val stack = ArrayDeque<String?>()
            stack += id
            while (stack.isNotEmpty()) {
                val next = stack.removeLast()
                for (c in grouped[next].orEmpty()) if (reachable.add(c.id)) stack += c.id
            }
        }
        mark(null)
        val orphans = byId.values.filter { it.id !in reachable }.sortedWith(comparator)
        for (o in orphans) {
            if (o.id in reachable) continue
            val parent = o.parentId?.takeIf { it != o.id && it in byId }
            grouped[parent]?.remove(o)
            grouped.getOrPut(null) { ArrayList() } += o
            reachable += o.id
            mark(o.id)
        }
        children = grouped.mapValues { (_, list) -> list.sortedWith(comparator) }
        roots = children[null].orEmpty()
    }

    val size: Int get() = byId.size

    operator fun get(id: String?): Folder? = id?.let { byId[it] }

    operator fun contains(id: String?): Boolean = id != null && id in byId

    fun childrenOf(id: String?): List<Folder> = children[id].orEmpty()

    /** The effective parent (null for roots, including folders whose parent is missing). */
    fun parentOf(id: String): Folder? {
        val f = byId[id] ?: return null
        val p = f.parentId?.let { byId[it] } ?: return null
        return if (childrenOf(p.id).any { it.id == id }) p else null
    }

    /** [id] and every folder below it. */
    fun subtree(id: String): Set<String> {
        if (id !in byId) return emptySet()
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        stack += id
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (!out.add(next)) continue
            childrenOf(next).forEach { stack += it.id }
        }
        return out
    }

    /** Root first, ending with the folder itself; empty for unknown ids. */
    fun breadcrumb(id: String?): List<Folder> {
        val self = this[id] ?: return emptyList()
        val chain = ArrayList<Folder>()
        val seen = HashSet<String>()
        var current: Folder? = self
        while (current != null && seen.add(current.id)) {
            chain += current
            current = parentOf(current.id)
        }
        return chain.asReversed()
    }

    /** True when [ancestorId] is [id] or one of its ancestors. */
    fun isWithin(id: String, ancestorId: String): Boolean = id in subtree(ancestorId)

    /** Visible rows for a tree where only [expanded] folders show their children. */
    fun visibleRows(expanded: Set<String>): List<FolderRow> = rows { expanded.contains(it) }

    /** Every folder, fully expanded (pickers). */
    fun allRows(): List<FolderRow> = rows { true }

    private inline fun rows(isExpanded: (String) -> Boolean): List<FolderRow> {
        val out = ArrayList<FolderRow>(byId.size)
        val seen = HashSet<String>()
        val stack = ArrayDeque<Pair<Folder, Int>>()
        for (r in roots.asReversed()) stack += r to 0
        while (stack.isNotEmpty()) {
            val (f, depth) = stack.removeLast()
            if (!seen.add(f.id)) continue
            val kids = childrenOf(f.id)
            val open = kids.isNotEmpty() && isExpanded(f.id)
            out += FolderRow(f, depth, kids.isNotEmpty(), open)
            if (open) for (k in kids.asReversed()) stack += k to depth + 1
        }
        return out
    }

    /** Folder ids that must be expanded for [id] to be visible (its ancestors). */
    fun ancestorsOf(id: String?): Set<String> = breadcrumb(id).dropLast(1).mapTo(LinkedHashSet()) { it.id }

    companion object {
        /**
         * Number of items directly inside each folder: live documents filed there plus its
         * sub-folders. Documents in unknown folders are ignored.
         */
        fun itemCounts(index: FolderIndex, docs: Collection<DocumentMeta>): Map<String, Int> {
            val counts = HashMap<String, Int>()
            for (d in docs) {
                val fid = d.folderId ?: continue
                if (d.trashedAt != null || fid !in index) continue
                counts[fid] = (counts[fid] ?: 0) + 1
            }
            for (f in index.allRows()) {
                val kids = index.childrenOf(f.folder.id).size
                if (kids > 0) counts[f.folder.id] = (counts[f.folder.id] ?: 0) + kids
            }
            return counts
        }
    }
}
