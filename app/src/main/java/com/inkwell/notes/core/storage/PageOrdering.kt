package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent

/** Pure helpers for page order, rotation and content copies. */
internal object PageOrdering {
    /** Clamps an insertion index; null appends. */
    fun insertionIndex(atIndex: Int?, size: Int): Int = atIndex?.coerceIn(0, size) ?: size

    fun <T> insertAll(list: List<T>, items: List<T>, atIndex: Int?): List<T> {
        val at = insertionIndex(atIndex, list.size)
        return ArrayList<T>(list.size + items.size).apply {
            addAll(list.subList(0, at))
            addAll(items)
            addAll(list.subList(at, list.size))
        }
    }

    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        if (from == target) return list
        return list.toMutableList().apply { add(target, removeAt(from)) }
    }

    /**
     * New order of [currentIds] following [requested]: requested ids that exist, in that order
     * (duplicates and unknown ids ignored), then every page the request did not mention, in
     * its current order, so a stale or partial request never loses pages.
     */
    fun reorder(currentIds: List<String>, requested: List<String>): List<String> {
        val existing = currentIds.toHashSet()
        val out = LinkedHashSet<String>()
        requested.filterTo(out) { it in existing }
        currentIds.filterTo(out) { it !in out }
        return out.toList()
    }

    /** Any angle to 0, 90, 180 or 270 (clockwise), rounding to the nearest quarter turn. */
    fun normalizeRotation(degrees: Int): Int {
        val quarter = Math.floorMod(Math.round(degrees / 90.0).toInt(), 4)
        return quarter * 90
    }

    /** Asset files referenced by a page (images). */
    fun referencedAssets(content: PageContent): Set<String> = content.images.mapTo(LinkedHashSet()) { it.assetId }

    /** Drops links to audio recordings (used when content moves to a document without them). */
    fun withoutAudio(content: PageContent): PageContent = content.copy(
        strokes = content.strokes.map { if (it.audio == null) it else it.copy(audio = null) },
        shapes = content.shapes.map { if (it.audio == null) it else it.copy(audio = null) },
        texts = content.texts.map { if (it.audio == null) it else it.copy(audio = null) },
        images = content.images.map { if (it.audio == null) it else it.copy(audio = null) },
        stickies = content.stickies.map { if (it.audio == null) it else it.copy(audio = null) },
    )

    /** Puts [image] beneath everything else on the page (a flattened PDF background). */
    fun withBackground(content: PageContent, image: ImageItem): PageContent {
        val minZ = listOfNotNull(
            content.strokes.minOfOrNull { it.z }, content.shapes.minOfOrNull { it.z },
            content.texts.minOfOrNull { it.z }, content.images.minOfOrNull { it.z },
            content.stickies.minOfOrNull { it.z }, content.connectors.minOfOrNull { it.z },
        ).minOrNull() ?: 0
        return content.copy(images = listOf(image.copy(z = minZ - 1)) + content.images)
    }
}
