package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.tools.InkTool
import com.inkwell.notes.ink.tools.ToolEvent
import com.inkwell.notes.ink.tools.ToolHost

/**
 * Routes a gesture that starts on the selection box (any tool but the eraser, stylus or finger)
 * to the [SelectionController]: drag to move, handles to resize or rotate, taps to edit or
 * report the tapped item. Not registered as a tool type; the input router picks it.
 */
internal class SelectionTool(private val ctl: SelectionController) : InkTool {
    override val type: ToolType get() = ToolType.LASSO
    override val isActive: Boolean get() = ctl.isManipulating

    override fun onDown(host: ToolHost, e: ToolEvent) = ctl.onDown(e)
    override fun onMove(host: ToolHost, e: ToolEvent) = ctl.onMove(e)
    override fun onUp(host: ToolHost, e: ToolEvent) = ctl.onUp(e)
    override fun onCancel(host: ToolHost) = ctl.cancel()
    override fun flush(host: ToolHost) = ctl.commit()
    override fun onViewportChanged(host: ToolHost) = ctl.onViewportChanged()
}
