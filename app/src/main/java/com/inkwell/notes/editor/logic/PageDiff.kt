package com.inkwell.notes.editor.logic

import com.inkwell.notes.core.model.PageMeta

/** Compares page lists the way the editor needs to react to them. */
object PageDiff {

    /** [page] without the fields autosave and the thumbnail service change. */
    private fun structural(page: PageMeta): PageMeta = page.copy(modifiedAt = 0L, thumbnailPath = null)

    /**
     * True when [new] differs from [old] only in bookkeeping (modifiedAt, thumbnailPath): the
     * same pages in the same order, each with the same size, paper, rotation, bookmark and PDF
     * page. Autosave bumps modifiedAt a few hundred milliseconds after every stroke, so the
     * canvas must not re-layout for such updates.
     */
    fun isBookkeepingOnly(old: List<PageMeta>, new: List<PageMeta>): Boolean {
        if (old.size != new.size) return false
        for (i in old.indices) {
            if (old[i].id != new[i].id) return false
            if (structural(old[i]) != structural(new[i])) return false
        }
        return true
    }
}
