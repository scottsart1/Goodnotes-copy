package com.inkwell.notes.ocr.text

/** Folder chains from flat (id, name, parentId) records, safe against cycles. */
object FolderPaths {
    data class Node(val id: String, val name: String, val parentId: String?)

    /** Names from the root down to [folderId]; empty when unknown. */
    fun chain(nodes: Map<String, Node>, folderId: String?): List<Node> {
        val out = ArrayList<Node>()
        val seen = HashSet<String>()
        var id = folderId
        while (id != null && seen.add(id)) {
            val n = nodes[id] ?: break
            out += n
            id = n.parentId
        }
        return out.asReversed()
    }

    /** "Economics / Term 1" style path. */
    fun path(nodes: Map<String, Node>, folderId: String?): String = chain(nodes, folderId).joinToString(" / ") { it.name }
}
