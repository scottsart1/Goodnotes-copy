package com.inkwell.notes.ink.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.inkwell.notes.ink.engine.InkEngine

/** Desk, paper and cached committed content. Redrawn on viewport changes and tile arrivals. */
@SuppressLint("ViewConstructor")
internal class PageLayerView(context: Context, private val engine: InkEngine) : View(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        engine.painter.drawPages(canvas)
    }
}

/**
 * Live feedback above the pages: just-committed ink until tiles catch up, the wet stroke when not
 * front-buffered, selection, cursors and tool previews. Invalidating it never re-records the
 * page layer, which keeps the per-event cost of inking small.
 */
@SuppressLint("ViewConstructor")
internal class OverlayView(context: Context, private val engine: InkEngine) : View(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        engine.painter.drawOverlay(canvas)
    }
}
