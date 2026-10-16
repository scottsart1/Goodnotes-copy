package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.Items

/**
 * Content math of dropping a dragged selection onto another page (see SelectionController).
 * Pure Kotlin, unit tested.
 *
 * Rules: the selected items and connectors whose every attached end moves along go to the target
 * page, stacked above its content in their relative z order. Unselected connectors attached to a
 * moving item stay on the source page with that end frozen where the item was. Selected connectors
 * attached to an item that stays behind are detached from it the same way, so a connector never
 * refers to an item on another page (its end would otherwise jump to a stale fallback position).
 */
object CrossPageMove {
    class Result(
        /** Source page after the move. */
        val source: PageContent,
        /** Target page after the move. */
        val target: PageContent,
        /** The items now on the target page (same ids as before), in their new z order. */
        val moved: List<Any>,
    )

    /**
     * @param before source page content before the drag
     * @param targetContent target page content before the drop
     * @param items the selected items as they were before the drag
     * @param baked the same items with the drag [transform] baked in (same order as [items])
     * @param attached unselected connectors of [before] attached to at least one selected item
     * @param transform the drag transform in source page points
     * @param toTarget source page -> target page affine
     * @param firstZ z of the lowest moved item on the target page
     */
    fun compute(
        before: PageContent,
        targetContent: PageContent,
        items: List<Any>,
        baked: List<Any>,
        attached: List<ConnectorItem>,
        transform: Affine,
        toTarget: Affine,
        firstZ: Int,
    ): Result {
        val moving = items.mapTo(HashSet()) { Items.id(it) }
        // Connectors whose ends all move go along; the others keep a free end where the item was.
        val along = attached.filter { c -> (c.fromItemId == null || c.fromItemId in moving) && (c.toItemId == null || c.toItemId in moving) }
        val stay = attached - along.toSet()
        val staying = ContentOps.allItems(before).map { Items.id(it) }.filterTo(HashSet()) { it !in moving }
        val detached = stay.associate { c -> c.id to SelectionClipboard.detachOutside(c, before, staying) as Any }
        // Selected connectors attached to items that stay behind: freeze that end (at its position
        // before the drag) and then move it like the rest of the selection.
        val original = items.associateBy { Items.id(it) }
        val bakedMoving = baked.map { b ->
            if (b !is ConnectorItem) return@map b
            val o = original[b.id] as? ConnectorItem ?: return@map b
            val free = SelectionClipboard.detachOutside(o, before, moving)
            if (free === o) b else TransformBaker.bake(free, transform)
        }
        val movedRaw = bakedMoving + along.map { TransformBaker.bake(it, transform) }
        var z = firstZ
        val moved = movedRaw.sortedBy { Items.z(it) }.map { item -> Items.withZ(TransformBaker.bake(item, toTarget), z++) }
        val removeIds = HashSet(moving).also { s -> along.forEach { s += it.id } }
        val source = ContentOps.replace(ContentOps.remove(before, removeIds), detached)
        val target = ContentOps.add(targetContent, moved)
        return Result(source, target, moved)
    }
}
