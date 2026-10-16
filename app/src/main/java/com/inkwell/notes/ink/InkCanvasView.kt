package com.inkwell.notes.ink

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ViewGroup
import android.widget.FrameLayout
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.AudioClock
import com.inkwell.notes.core.model.InputPrefs
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.TextConfig
import com.inkwell.notes.core.model.ThemeMode
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.engine.InkEngine
import com.inkwell.notes.ink.render.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The handwriting canvas: renders a document's pages (paper or PDF backgrounds plus vector
 * content), handles stylus/finger input with palm rejection, the S Pen side-button eraser,
 * zoom/pan, all tools, selection and undo/redo.
 *
 * Implementation lives in [com.inkwell.notes.ink.engine.InkEngine] and the packages under
 * com.inkwell.notes.ink; this class is the public API and the view host.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var listener: InkCanvasListener? = null

    private val engine = InkEngine(this)
    private var themeMode = ThemeMode.SYSTEM
    private var themeJob: Job? = null

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // 10 = TRIM_MEMORY_RUNNING_LOW (deprecated constant, still delivered on older releases).
            if (level >= 10) post { engine.render.trimMemory() }
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() { post { engine.render.trimMemory() } }
    }

    init {
        setWillNotDraw(true)
        // Focusable so physical-keyboard shortcuts (DeX) reach the canvas; no focus highlight.
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        isMotionEventSplittingEnabled = false
        contentDescription = context.getString(R.string.ink_canvas_description)
        addView(engine.pageView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(engine.overlayView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        engine.setDarkTheme(resolveDark(ThemeMode.SYSTEM))
    }

    // ---- View plumbing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.viewportCtl.onSizeChanged(w, h)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = engine.router.onTouchEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (isFocused && engine.keys.onKeyEvent(event)) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        engine.router.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        engine.router.onGenericMotionEvent(event) || super.dispatchHoverEvent(event)

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val stylus = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER
        if (stylus && engine.inputPrefs.showHoverCursor) {
            val t = engine.router.effectiveTool
            if (t == ToolType.PEN || t == ToolType.PENCIL || t == ToolType.HIGHLIGHTER || t == ToolType.ERASER) {
                return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
            }
        }
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        engine.onAttached()
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        observeTheme()
    }

    override fun onDetachedFromWindow() {
        engine.onDetached()
        themeJob?.cancel()
        themeJob = null
        try { context.applicationContext.unregisterComponentCallbacks(memoryCallbacks) } catch (_: Throwable) {}
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        engine.setDarkTheme(resolveDark(themeMode))
    }

    private fun observeTheme() {
        if (isInEditMode) return
        themeJob?.cancel()
        themeJob = engine.scope.launch {
            try {
                val repo = withContext(Dispatchers.IO) { AppGraph.get(context).settings }
                repo.settings.map { it.theme }.distinctUntilChanged().collect { mode ->
                    themeMode = mode
                    engine.setDarkTheme(resolveDark(mode))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("InkCanvasView", "Theme preference unavailable; following the system", t)
            }
        }
    }

    private fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // ---- Binding & configuration
    fun bind(source: InkDocumentSource, pages: List<PageMeta>, layout: PageLayoutMode, initial: CanvasViewState? = null) =
        engine.bind(source, pages, layout, initial)
    /** Pages added/removed/reordered/resized/re-papered. Content of surviving pages is kept. */
    fun updatePages(pages: List<PageMeta>) = engine.updatePages(pages)
    /** Re-reads a page's content from the source (after an external change). */
    fun reloadPage(pageId: String) = engine.reloadPage(pageId)
    fun setToolState(state: ToolState) = engine.setToolState(state)
    fun setInputPrefs(prefs: InputPrefs) = engine.setInputPrefs(prefs)
    fun setLayoutMode(mode: PageLayoutMode) = engine.setLayoutMode(mode)
    /** Items created while a recording runs get an AudioAnchor from this clock. */
    fun setAudioClock(clock: AudioClock?) { engine.audioClock = clock }
    /** Highlights items whose audio anchor lies within [windowMs] before [positionMs]. Null clears. */
    fun setPlaybackHighlight(recordingId: String?, positionMs: Long, windowMs: Long = 4000) =
        engine.setPlayback(recordingId, positionMs, windowMs)

    // ---- History
    fun undo() = engine.undo()
    fun redo() = engine.redo()
    fun canUndo(): Boolean = engine.canUndo()
    fun canRedo(): Boolean = engine.canRedo()

    // ---- Viewport
    fun scrollToPage(index: Int, smooth: Boolean = true) = engine.viewportCtl.scrollToPage(index, smooth)
    /** Centres a point of a page (search results, outline links) and optionally flashes [highlight]. */
    fun revealOnPage(index: Int, pageX: Float, pageY: Float, highlight: RectF? = null) =
        engine.viewportCtl.revealOnPage(index, pageX, pageY, highlight)
    fun zoomBy(factor: Float) = engine.viewportCtl.zoomBy(factor)
    fun setZoom(zoom: Float) = engine.viewportCtl.setZoom(zoom)
    fun fitWidth() = engine.viewportCtl.fitWidth()
    fun fitPage() = engine.viewportCtl.fitPage()
    fun viewState(): CanvasViewState = engine.viewportCtl.viewState()
    fun restoreViewState(state: CanvasViewState) = engine.viewportCtl.restore(state)
    fun currentPageIndex(): Int = engine.viewportCtl.currentPageIndex()
    /** Visible region in page points of [pageIndex] (whiteboard minimap). */
    fun visibleRect(pageIndex: Int = 0): RectF? = engine.viewportCtl.visibleRect(pageIndex)
    /** Union bounds of all content on [pageIndex] in page points. */
    fun contentBounds(pageIndex: Int = 0): RectF? = engine.editor.pageContent(pageIndex)?.let { PageRenderer.contentBounds(it) }
    fun centerOn(pageIndex: Int, pageX: Float, pageY: Float) = engine.viewportCtl.centerOn(pageIndex, pageX, pageY)

    // ---- Inserting content (page coordinates; null page = current page; null point = viewport centre)
    fun insertItems(pageIndex: Int?, fragment: PageContent, at: PointF? = null, select: Boolean = true) =
        engine.editor.insertItems(pageIndex, fragment, at, select)
    fun insertImage(pageIndex: Int?, assetId: String, widthPx: Int, heightPx: Int, at: PointF? = null) =
        engine.editor.insertImage(pageIndex, assetId, widthPx, heightPx, at)
    fun insertText(pageIndex: Int?, text: String, at: PointF? = null, config: TextConfig? = null, origin: String? = null): ItemRef? =
        engine.editor.insertText(pageIndex, text, at, config, origin)
    fun updateText(ref: ItemRef, text: String, config: TextConfig? = null) = engine.editor.updateText(ref, text, config)
    fun addSticky(pageIndex: Int?, at: PointF? = null, color: Int? = null): ItemRef? = engine.editor.addSticky(pageIndex, at, color)
    fun updateSticky(ref: ItemRef, text: String, color: Int? = null) = engine.editor.updateSticky(ref, text, color)

    // ---- Selection
    fun selection(): InkSelection? = engine.editor.buildSelection()
    fun clearSelection() = engine.clearSelection()
    fun selectAll(pageIndex: Int? = null) = engine.editor.selectAll(pageIndex)
    fun selectItems(pageId: String, itemIds: Collection<String>) = engine.editor.selectItems(pageId, itemIds)
    fun deleteSelection() = engine.editor.deleteSelection()
    fun duplicateSelection() = engine.editor.duplicateSelection()
    fun copySelection(): PageContent? = engine.editor.copySelection()
    fun cutSelection(): PageContent? = engine.editor.cutSelection()
    fun recolorSelection(color: Int) = engine.editor.recolorSelection(color)
    fun setSelectionWidth(width: Float) = engine.editor.setSelectionWidth(width)
    fun setSelectionOpacity(opacity: Float) = engine.editor.setSelectionOpacity(opacity)
    fun groupSelection() = engine.editor.groupSelection()
    fun ungroupSelection() = engine.editor.ungroupSelection()
    fun bringSelectionToFront() = engine.editor.bringSelectionToFront()
    fun sendSelectionToBack() = engine.editor.sendSelectionToBack()
    /** Replaces selected handwriting with a text box containing [text] (convert to text). */
    fun replaceSelectionWithText(text: String) = engine.editor.replaceSelectionWithText(text)
    /** Rotates selected handwriting so its baseline is horizontal. */
    fun straightenSelection() = engine.editor.straightenSelection()
    /** Re-wraps selected handwritten words into lines of [widthPt] (null = page text width). */
    fun reflowSelection(widthPt: Float? = null) = engine.editor.reflowSelection(widthPt)
    /** Scales gaps between handwritten words of the selection by [factor]. */
    fun adjustSelectionWordSpacing(factor: Float) = engine.editor.adjustSelectionWordSpacing(factor)
    /** Crops the single selected image by insets (fractions 0..1). */
    fun cropSelectedImage(left: Float, top: Float, right: Float, bottom: Float) = engine.editor.cropSelectedImage(left, top, right, bottom)

    // ---- Rendering helpers
    fun pageContent(pageIndex: Int): PageContent? = engine.editor.pageContent(pageIndex)
    fun renderPageBitmap(pageIndex: Int, widthPx: Int): Bitmap? = engine.editor.renderPageBitmap(pageIndex, widthPx)
    fun renderSelectionBitmap(maxPx: Int = 1024): Bitmap? = engine.editor.renderSelectionBitmap(maxPx)

    // ---- Lifecycle
    /** Commits any in-progress edit and reports dirty pages through onContentChanged. */
    fun flush() = engine.flushTools()
    fun release() = engine.release()
}
