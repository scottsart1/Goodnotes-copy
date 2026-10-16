package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.ink.geometry.Bounds
import kotlinx.coroutines.Job
import java.util.IdentityHashMap

enum class LoadState { NOT_LOADED, LOADING, LOADED, FAILED }

/**
 * Main-thread model of one page: its metadata, the current immutable content snapshot, load
 * state and a lazily built spatial index. [version] changes with every content edit;
 * [visualVersion] also changes when anything else that affects pixels (PDF background
 * resolution, an image finishing loading, paper) changes.
 */
class PageHolder(meta: PageMeta) {
    var meta: PageMeta = meta
        internal set
    val id: String get() = meta.id

    var state: LoadState = LoadState.NOT_LOADED
        internal set
    var content: PageContent = PageContent.EMPTY
        private set
    var version: Long = 0L
        private set
    var visualVersion: Long = 0L
        private set

    /** True once this page was edited while bound (its content must never be dropped). */
    var editedThisSession = false
        internal set
    internal var loadJob: Job? = null
    internal var failures = 0
    internal var lastFailureAt = 0L

    /** Items committed while the page was still loading; merged in once it loads. */
    internal val pendingAdds = ArrayList<Any>()

    private val boundsCache = IdentityHashMap<Any, Bounds>()
    private var index: ItemIndex? = null

    val isLoaded: Boolean get() = state == LoadState.LOADED

    /** The spatial index of the current content (rebuilt lazily after edits). */
    fun index(): ItemIndex {
        val i = index
        if (i != null && i.content === content) return i
        return ItemIndex.build(content, boundsCache).also { index = it }
    }

    internal fun setContent(c: PageContent) {
        content = c
        version++
        visualVersion++
    }

    /** Something besides content changed the page's pixels. */
    internal fun bumpVisual() { visualVersion++ }

    internal fun unload() {
        content = PageContent.EMPTY
        state = LoadState.NOT_LOADED
        index = null
        boundsCache.clear()
        version++
        visualVersion++
    }
}
