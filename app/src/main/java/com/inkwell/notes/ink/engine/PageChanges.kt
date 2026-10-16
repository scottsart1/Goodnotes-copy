package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.PageMeta

/**
 * Classifies page-list updates from the host so the canvas only re-lays out (and only commits a
 * gesture in progress) when the pages themselves changed. Autosave bumps every page's
 * `modifiedAt` a few hundred milliseconds after each stroke, and thumbnails arrive later; those
 * updates must never interrupt writing. Pure Kotlin.
 */
object PageChanges {
    /** True when [new] draws differently from [old]: size, paper, rotation or PDF page. */
    fun affectsRendering(old: PageMeta, new: PageMeta): Boolean =
        old.widthPt != new.widthPt || old.heightPt != new.heightPt || old.paper != new.paper ||
            old.rotation != new.rotation || old.pdfPageIndex != new.pdfPageIndex

    /**
     * True when the update only touches bookkeeping (modifiedAt, thumbnails, bookmarks): the same
     * pages in the same order, each looking the same.
     */
    fun isBookkeepingOnly(old: List<PageMeta>, new: List<PageMeta>): Boolean =
        old.size == new.size && old.indices.all { old[it].id == new[it].id && !affectsRendering(old[it], new[it]) }
}
