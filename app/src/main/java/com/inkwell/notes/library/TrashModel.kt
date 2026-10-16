package com.inkwell.notes.library

import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder

/** One row of the Trash screen. */
sealed interface TrashEntry {
    val key: String
    val trashedAt: Long
    val title: String

    data class Doc(val doc: DocumentMeta) : TrashEntry {
        override val key: String get() = "d:" + doc.id
        override val trashedAt: Long get() = doc.trashedAt ?: 0L
        override val title: String get() = doc.title
    }

    /**
     * A folder trashed as a whole, with what was trashed together with it: [folderIds]
     * (itself first, then sub-folders) and [documentIds].
     */
    data class FolderGroup(
        val folder: Folder,
        val folderIds: List<String>,
        val documentIds: List<String>,
    ) : TrashEntry {
        override val key: String get() = "f:" + folder.id
        override val trashedAt: Long get() = folder.trashedAt ?: 0L
        override val title: String get() = folder.name
        val itemCount: Int get() = folderIds.size - 1 + documentIds.size
    }
}

/**
 * Groups trashed items the way they were deleted. The repository stamps a folder, its
 * sub-folders and its documents with the same trashedAt when a folder is trashed, and restoring
 * the folder brings back exactly those. So:
 *
 * - A trashed folder is covered by its parent when the parent is trashed with the same stamp;
 *   otherwise it is its own entry (it was deleted separately).
 * - A trashed document is covered by its folder when the folder is trashed with the same stamp;
 *   otherwise it is its own entry.
 *
 * Entries are newest first. Walks are bounded, so corrupt parent cycles cannot loop.
 */
object TrashModel {

    fun build(trashedDocs: Collection<DocumentMeta>, allFolders: Collection<Folder>): List<TrashEntry> {
        val byId = allFolders.associateBy { it.id }

        fun coveringParent(f: Folder): Folder? {
            val p = f.parentId?.takeIf { it != f.id }?.let { byId[it] } ?: return null
            return if (p.trashedAt != null && p.trashedAt == f.trashedAt) p else null
        }

        val entryOf = HashMap<String, String>()
        fun entryFor(f: Folder): String {
            entryOf[f.id]?.let { return it }
            val seen = LinkedHashSet<String>()
            var cur = f
            while (seen.add(cur.id)) {
                val parent = coveringParent(cur) ?: break
                if (parent.id in seen) break
                cur = parent
            }
            val root = cur.id
            seen.forEach { entryOf[it] = root }
            return root
        }

        val trashedFolders = allFolders.filter { it.trashedAt != null }
        val folderMembers = LinkedHashMap<String, MutableList<String>>()
        for (f in trashedFolders.sortedBy { depthHint(it, byId) }) {
            val root = entryFor(f)
            val list = folderMembers.getOrPut(root) { ArrayList() }
            if (f.id == root) list.add(0, f.id) else list += f.id
        }
        val docMembers = HashMap<String, MutableList<String>>()
        val entries = ArrayList<TrashEntry>()
        for (d in trashedDocs) {
            if (d.trashedAt == null) continue
            val folder = d.folderId?.let { byId[it] }
            if (folder != null && folder.trashedAt != null && folder.trashedAt == d.trashedAt) {
                docMembers.getOrPut(entryFor(folder)) { ArrayList() } += d.id
            } else {
                entries += TrashEntry.Doc(d)
            }
        }
        for ((rootId, members) in folderMembers) {
            val folder = byId[rootId] ?: continue
            entries += TrashEntry.FolderGroup(folder, members, docMembers[rootId].orEmpty().sorted())
        }
        return entries.sortedWith(compareByDescending<TrashEntry> { it.trashedAt }.thenBy { it.title.lowercase() }.thenBy { it.key })
    }

    /** Parents before children where possible so a group lists its root folder first. */
    private fun depthHint(f: Folder, byId: Map<String, Folder>): Int {
        var depth = 0
        val seen = HashSet<String>()
        var cur: Folder? = f
        while (cur != null && seen.add(cur.id)) {
            cur = cur.parentId?.let { byId[it] }
            if (cur != null) depth++
        }
        return depth
    }

    /** Total rows the "Empty trash" confirmation talks about. */
    fun itemCount(entries: List<TrashEntry>): Int = entries.sumOf { e ->
        when (e) {
            is TrashEntry.Doc -> 1
            is TrashEntry.FolderGroup -> 1 + e.itemCount
        }
    }
}
