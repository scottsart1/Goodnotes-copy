package com.inkwell.notes.library

import com.inkwell.notes.core.model.DocumentMeta

/** A selectable library item. */
sealed interface ItemKey {
    data class Doc(val id: String) : ItemKey
    data class Fold(val id: String) : ItemKey
}

/** Multi-selection: documents and folders by id. Immutable; every change returns a copy. */
data class Selection(val keys: Set<ItemKey> = emptySet()) {
    val active: Boolean get() = keys.isNotEmpty()
    val size: Int get() = keys.size
    val documentIds: Set<String> get() = keys.filterIsInstance<ItemKey.Doc>().mapTo(LinkedHashSet()) { it.id }
    val folderIds: Set<String> get() = keys.filterIsInstance<ItemKey.Fold>().mapTo(LinkedHashSet()) { it.id }
    val hasFolders: Boolean get() = keys.any { it is ItemKey.Fold }
    val hasDocuments: Boolean get() = keys.any { it is ItemKey.Doc }

    operator fun contains(key: ItemKey): Boolean = key in keys

    fun toggle(key: ItemKey): Selection = if (key in keys) Selection(keys - key) else Selection(keys + key)

    fun plus(key: ItemKey): Selection = Selection(keys + key)

    fun asItems(): ItemSet = ItemSet(documentIds, folderIds)

    /** Drops keys that no longer exist (deleted elsewhere, moved out of view). */
    fun retainOnly(existing: Set<ItemKey>): Selection = if (existing.containsAll(keys)) this else Selection(keys.filterTo(LinkedHashSet()) { it in existing })

    companion object {
        val None = Selection()

        /**
         * Bulk toggles set every selected document to the same state: if all of them already
         * have it, the action clears it; otherwise it sets it on all.
         */
        fun bulkTarget(docs: Collection<DocumentMeta>, has: (DocumentMeta) -> Boolean): Boolean =
            docs.isEmpty() || !docs.all(has)
    }
}
