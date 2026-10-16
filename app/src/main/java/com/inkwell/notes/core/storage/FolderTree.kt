package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.Folder

/**
 * Read-only view of the folder hierarchy. Tolerates corrupt data (cycles, missing parents):
 * every walk is bounded by a visited set, and a folder whose parent is missing behaves as a
 * root folder.
 */
class FolderTree(folders: Collection<Folder>) {
    private val byId: Map<String, Folder> = folders.associateBy { it.id }
    private val children: Map<String?, List<Folder>> = folders.groupBy { it.parentId?.takeIf { p -> p in byId } }

    operator fun get(id: String): Folder? = byId[id]

    fun childrenOf(id: String?): List<Folder> = children[id].orEmpty()

    /** [id] and all of its descendants. */
    fun subtree(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue += id
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!out.add(next)) continue
            childrenOf(next).forEach { queue += it.id }
        }
        return out
    }

    /** True when making [newParentId] the parent of [folderId] would create a cycle. */
    fun wouldCreateCycle(folderId: String, newParentId: String?): Boolean =
        newParentId != null && newParentId in subtree(folderId)

    /** Ancestors from the direct parent up to the root. */
    fun ancestors(id: String): List<Folder> {
        val out = ArrayList<Folder>()
        val seen = HashSet<String>()
        seen += id
        var parent = byId[id]?.parentId
        while (parent != null && seen.add(parent)) {
            val f = byId[parent] ?: break
            out += f
            parent = f.parentId
        }
        return out
    }

    /** "Economics / Term 1" for display; "" for the library root or an unknown folder. */
    fun path(id: String?, separator: String = " / "): String {
        if (id == null) return ""
        val self = byId[id] ?: return ""
        return (ancestors(id).asReversed() + self).joinToString(separator) { it.name }
    }
}
