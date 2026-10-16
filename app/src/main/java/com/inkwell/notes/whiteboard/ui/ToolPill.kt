package com.inkwell.notes.whiteboard.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.inkwell.notes.R
import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.kit.KitIcons
import com.inkwell.notes.whiteboard.kit.PillDivider
import com.inkwell.notes.whiteboard.kit.PillSurface
import kotlin.math.max

private data class PillTool(val type: ToolType, val icon: ImageVector, @param:StringRes val label: Int)

private val boardTools = listOf(
    PillTool(ToolType.PEN, KitIcons.Pen, R.string.wb_tool_pen),
    PillTool(ToolType.PENCIL, Icons.Outlined.Edit, R.string.wb_tool_pencil),
    PillTool(ToolType.HIGHLIGHTER, KitIcons.Highlighter, R.string.wb_tool_highlighter),
    PillTool(ToolType.ERASER, KitIcons.Eraser, R.string.wb_tool_eraser),
    PillTool(ToolType.LASSO, KitIcons.Lasso, R.string.wb_tool_lasso),
    PillTool(ToolType.SHAPE, Icons.Outlined.Category, R.string.wb_tool_shapes),
    PillTool(ToolType.CONNECTOR, KitIcons.Connector, R.string.wb_tool_connector),
    PillTool(ToolType.STICKY, Icons.AutoMirrored.Outlined.StickyNote2, R.string.wb_tool_sticky),
    PillTool(ToolType.TEXT, Icons.Outlined.TextFields, R.string.wb_tool_text),
    PillTool(ToolType.IMAGE, Icons.Outlined.Image, R.string.wb_tool_image),
)

private val toolsWithOptions = setOf(
    ToolType.PEN, ToolType.PENCIL, ToolType.HIGHLIGHTER, ToolType.ERASER, ToolType.LASSO,
    ToolType.SHAPE, ToolType.CONNECTOR, ToolType.STICKY, ToolType.TEXT,
)

/** Label resource of a tool, for toasts and descriptions. */
@StringRes
fun toolLabel(t: ToolType): Int = boardTools.firstOrNull { it.type == t }?.label ?: when (t) {
    ToolType.HAND -> R.string.wb_tool_hand
    else -> R.string.wb_tools
}

/**
 * The whiteboard's compact floating tool pill. Tapping the selected tool again opens its
 * options popover. Scrolls horizontally when the window is narrower than the pill.
 */
@Composable
fun BoardToolPill(
    tools: ToolState,
    temporaryTool: ToolType?,
    presets: List<PenPreset>,
    canUndo: Boolean,
    canRedo: Boolean,
    aiOpen: Boolean,
    exportActive: Boolean,
    onToolState: (ToolState) -> Unit,
    onImage: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPdfSnippet: () -> Unit,
    onAi: () -> Unit,
    onExportArea: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var optionsFor by remember { mutableStateOf<ToolType?>(null) }
    val active = temporaryTool ?: tools.tool
    PillSurface(modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            boardTools.forEach { t ->
                Box {
                    val label = stringResource(t.label)
                    KitIconButton(
                        icon = t.icon,
                        contentDescription = if (t.type == active && t.type in toolsWithOptions) stringResource(R.string.wb_tool_options, label) else label,
                        selected = t.type == active,
                        onClick = {
                            when {
                                t.type == ToolType.IMAGE -> { optionsFor = null; onImage() }
                                tools.tool == t.type && t.type in toolsWithOptions -> optionsFor = if (optionsFor == t.type) null else t.type
                                else -> { optionsFor = null; onToolState(tools.copy(tool = t.type)) }
                            }
                        },
                    )
                    toolColor(tools, t.type)?.let { color ->
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 5.dp)
                                .size(width = 12.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color),
                        )
                    }
                    if (optionsFor == t.type) {
                        val density = LocalDensity.current
                        val provider = remember(density) {
                            BelowAnchorProvider(with(density) { 8.dp.roundToPx() }, with(density) { 8.dp.roundToPx() })
                        }
                        Popup(
                            popupPositionProvider = provider,
                            onDismissRequest = { optionsFor = null },
                            properties = PopupProperties(focusable = true),
                        ) {
                            PillSurface {
                                ToolOptions(t.type, tools, presets) { newState ->
                                    onToolState(newState)
                                    if (newState.tool != t.type) optionsFor = null
                                }
                            }
                        }
                    }
                }
            }
            PillDivider()
            KitIconButton(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.wb_undo), onUndo, enabled = canUndo)
            KitIconButton(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.wb_redo), onRedo, enabled = canRedo)
            PillDivider()
            KitIconButton(Icons.Outlined.PictureAsPdf, stringResource(R.string.wb_pdf_snippet), onPdfSnippet)
            KitIconButton(Icons.Outlined.AutoAwesome, stringResource(R.string.wb_ai), onAi, selected = aiOpen)
            KitIconButton(KitIcons.ExportArea, stringResource(R.string.wb_export_area), onExportArea, selected = exportActive)
        }
    }
}

private fun toolColor(state: ToolState, t: ToolType): Color? = when (t) {
    ToolType.PEN -> Color(state.pen.color)
    ToolType.PENCIL -> Color(state.pencil.color)
    ToolType.HIGHLIGHTER -> Color(state.highlighter.color)
    ToolType.STICKY -> Color(state.stickyColor)
    else -> null
}

/** Places a popover centred below its anchor, or above it when there is no room below. */
class BelowAnchorProvider(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = max(margin, windowSize.width - popupContentSize.width - margin)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, maxX)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) below
        else max(margin, anchorBounds.top - gap - popupContentSize.height)
        return IntOffset(x, y)
    }
}
