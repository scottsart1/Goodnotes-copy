package com.inkwell.notes.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.core.model.ToolbarPosition
import com.inkwell.notes.core.model.ToolbarPrefs
import com.inkwell.notes.editor.NotebookController
import com.inkwell.notes.editor.logic.Bounds
import com.inkwell.notes.editor.logic.InkingTools
import com.inkwell.notes.editor.logic.Placement
import com.inkwell.notes.editor.logic.PopupSide
import com.inkwell.notes.editor.logic.ToolbarItem
import com.inkwell.notes.editor.logic.ToolbarLayout
import com.inkwell.notes.editor.logic.ToolbarMode
import com.inkwell.notes.editor.logic.ToolbarOrientation
import com.inkwell.notes.editor.logic.ToolbarSpec
import com.inkwell.notes.editor.ui.components.EditorIcons
import com.inkwell.notes.editor.ui.components.FloatingSurface
import com.inkwell.notes.editor.ui.components.IconAction
import com.inkwell.notes.editor.ui.components.PillDivider
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.roundToInt

/** Positions a popup beside its anchor (the tool button) using [Placement.popover]. */
class AnchoredPopoverProvider(private val side: PopupSide, private val gapPx: Float, private val marginPx: Float) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val p = Placement.popover(
            Bounds(anchorBounds.left.toFloat(), anchorBounds.top.toFloat(), anchorBounds.right.toFloat(), anchorBounds.bottom.toFloat()),
            popupContentSize.width.toFloat(), popupContentSize.height.toFloat(),
            windowSize.width.toFloat(), windowSize.height.toFloat(),
            side, gapPx, marginPx,
        )
        return IntOffset(p.x.roundToInt(), p.y.roundToInt())
    }
}

/** State and callbacks the toolbar needs from its pane. */
class ToolbarCallbacks(
    val onRecord: () -> Unit,
    val onAi: () -> Unit,
    val onPrefs: (ToolbarPrefs) -> Unit,
    val onMyElements: () -> Unit,
    val options: ToolOptionsActions,
)

/**
 * The floating tool pill, placed per [ToolbarPrefs.position] inside a full-size box. In focus
 * mode it is hidden or reduced to a small translucent handle that expands on tap.
 */
@Composable
fun ToolbarHost(
    controller: NotebookController,
    prefs: ToolbarPrefs,
    focusMode: Boolean,
    forceCompact: Boolean,
    presets: List<PenPreset>,
    recordingActive: Boolean,
    aiOpen: Boolean,
    callbacks: ToolbarCallbacks,
    modifier: Modifier = Modifier,
) {
    var handleExpanded by remember { mutableStateOf(false) }
    if (!focusMode && handleExpanded) handleExpanded = false
    BoxWithConstraints(modifier.fillMaxSize()) {
        val vertical = prefs.position == ToolbarPosition.LEFT || prefs.position == ToolbarPosition.RIGHT
        val available = if (vertical) maxHeight.value else maxWidth.value
        val spec = ToolbarLayout.compute(prefs.position, prefs.compact || forceCompact, available, focusMode, prefs.focusModeHidesToolbar, handleExpanded)
        val density = LocalDensity.current
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val margin = with(density) { 8.dp.toPx() }

        when (spec.mode) {
            ToolbarMode.HIDDEN -> Unit
            ToolbarMode.HANDLE -> FocusHandle(
                modifier = Modifier.align(alignmentFor(prefs.position)).padding(10.dp),
                tool = controller.tools.effectiveTool,
                onClick = { handleExpanded = true },
            )
            ToolbarMode.FULL -> {
                var barSize by remember { mutableStateOf(IntSize.Zero) }
                var drag by remember { mutableStateOf<Offset?>(null) }
                val placement: Modifier = if (prefs.position == ToolbarPosition.FLOATING) {
                    val (bx, by) = ToolbarLayout.floatingOffset(prefs.floatingX, prefs.floatingY, containerW, containerH, barSize.width.toFloat(), barSize.height.toFloat(), margin)
                    val d = drag ?: Offset.Zero
                    val x = (bx + d.x).coerceIn(margin, (containerW - barSize.width - margin).coerceAtLeast(margin))
                    val y = (by + d.y).coerceIn(margin, (containerH - barSize.height - margin).coerceAtLeast(margin))
                    Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                } else {
                    Modifier.align(alignmentFor(prefs.position)).padding(
                        when (prefs.position) {
                            ToolbarPosition.TOP -> androidx.compose.foundation.layout.PaddingValues(top = 8.dp)
                            ToolbarPosition.BOTTOM -> androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
                            ToolbarPosition.LEFT -> androidx.compose.foundation.layout.PaddingValues(start = 8.dp)
                            else -> androidx.compose.foundation.layout.PaddingValues(end = 8.dp)
                        },
                    )
                }
                ToolPill(
                    controller = controller,
                    spec = spec,
                    prefs = prefs,
                    presets = presets,
                    recordingActive = recordingActive,
                    aiOpen = aiOpen,
                    callbacks = callbacks,
                    onToolChosen = { if (focusMode) handleExpanded = false },
                    dragHandle = if (prefs.position == ToolbarPosition.FLOATING) {
                        Modifier.pointerInput(containerW, containerH) {
                            detectDragGestures(
                                onDragStart = { drag = Offset.Zero },
                                onDragCancel = { drag = null },
                                onDragEnd = {
                                    val (bx, by) = ToolbarLayout.floatingOffset(prefs.floatingX, prefs.floatingY, containerW, containerH, barSize.width.toFloat(), barSize.height.toFloat(), margin)
                                    val d = drag ?: Offset.Zero
                                    val (fx, fy) = ToolbarLayout.floatingFraction(bx + d.x, by + d.y, containerW, containerH, barSize.width.toFloat(), barSize.height.toFloat())
                                    drag = null
                                    callbacks.onPrefs(prefs.copy(floatingX = fx, floatingY = fy))
                                },
                            ) { change, amount ->
                                change.consume()
                                drag = (drag ?: Offset.Zero) + amount
                            }
                        }
                    } else null,
                    modifier = placement.onSizeChanged { barSize = it },
                )
            }
        }
    }
}

private fun alignmentFor(p: ToolbarPosition): Alignment = when (p) {
    ToolbarPosition.TOP, ToolbarPosition.FLOATING -> Alignment.TopCenter
    ToolbarPosition.BOTTOM -> Alignment.BottomCenter
    ToolbarPosition.LEFT -> Alignment.CenterStart
    ToolbarPosition.RIGHT -> Alignment.CenterEnd
}

private fun popoverSide(p: ToolbarPosition): PopupSide = when (p) {
    ToolbarPosition.TOP, ToolbarPosition.FLOATING -> PopupSide.BELOW
    ToolbarPosition.BOTTOM -> PopupSide.ABOVE
    ToolbarPosition.LEFT -> PopupSide.END
    ToolbarPosition.RIGHT -> PopupSide.START
}

@Composable
private fun FocusHandle(modifier: Modifier, tool: ToolType, onClick: () -> Unit) {
    val c = Inkwell.colors
    val label = stringResource(R.string.editor_show_toolbar)
    Box(
        modifier
            .size(width = 56.dp, height = Dimens.minTouch)
            .combinedClickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .alpha(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(EditorIcons.tool(tool), null, Modifier.size(16.dp), tint = c.inkMuted)
            Box(Modifier.padding(start = 6.dp).width(14.dp).height(2.dp).clip(CircleShape).background(c.inkMuted))
        }
    }
}

@Composable
private fun ToolPill(
    controller: NotebookController,
    spec: ToolbarSpec,
    prefs: ToolbarPrefs,
    presets: List<PenPreset>,
    recordingActive: Boolean,
    aiOpen: Boolean,
    callbacks: ToolbarCallbacks,
    onToolChosen: () -> Unit,
    dragHandle: Modifier?,
    modifier: Modifier,
) {
    val tools = controller.tools
    var openOptions by remember { mutableStateOf<ToolType?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    val vertical = spec.orientation == ToolbarOrientation.VERTICAL
    val side = popoverSide(prefs.position)
    val density = LocalDensity.current
    val provider = remember(side, density) { AnchoredPopoverProvider(side, with(density) { 8.dp.toPx() }, with(density) { 8.dp.toPx() }) }

    fun onTool(t: ToolType) {
        if (tools.tool == t) {
            openOptions = if (openOptions == t) null else t
        } else {
            controller.selectTool(t)
            openOptions = null
            onToolChosen()
        }
    }

    @Composable
    fun OptionsPopup(t: ToolType) {
        if (openOptions == t) {
            Popup(popupPositionProvider = provider, onDismissRequest = { openOptions = null }, properties = PopupProperties(focusable = true)) {
                FloatingSurface(shape = RoundedCornerShape(16.dp)) {
                    ToolOptionsPopover(t, controller.tools, presets, onChange = controller::updateTools, actions = callbacks.options)
                }
            }
        }
    }

    @Composable
    fun Item(item: ToolbarItem) {
        val t = item.tool
        val visual = spec.buttonDp.dp
        val icon = spec.iconDp.dp
        when {
            t != null -> Box {
                val pen = tools.penFor(t)
                IconAction(
                    icon = EditorIcons.tool(t),
                    contentDescription = stringResource(toolLabel(t)),
                    onClick = { onTool(t) },
                    selected = tools.tool == t,
                    temporary = tools.isTemporarilyActive(t),
                    visual = visual,
                    iconSize = icon,
                    badge = if (t in InkingTools && pen != null) {
                        { Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 10.dp, height = 3.dp).clip(CircleShape).background(Color(pen.color).copy(alpha = if (t == ToolType.HIGHLIGHTER) 0.8f else 1f))) }
                    } else null,
                )
                OptionsPopup(t)
            }
            item == ToolbarItem.UNDO -> IconAction(EditorIcons.item(item), stringResource(R.string.editor_undo), { controller.canvas.undo() }, enabled = controller.canUndo, visual = visual, iconSize = icon)
            item == ToolbarItem.REDO -> IconAction(EditorIcons.item(item), stringResource(R.string.editor_redo), { controller.canvas.redo() }, enabled = controller.canRedo, visual = visual, iconSize = icon)
            item == ToolbarItem.RECORD -> IconAction(
                EditorIcons.item(item),
                stringResource(if (recordingActive) R.string.editor_recording_controls else R.string.editor_record),
                callbacks.onRecord,
                tint = if (recordingActive) Inkwell.colors.record else null,
                visual = visual,
                iconSize = icon,
                badge = if (recordingActive) {
                    { Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(7.dp).clip(CircleShape).background(Inkwell.colors.record)) }
                } else null,
            )
            item == ToolbarItem.AI -> IconAction(EditorIcons.item(item), stringResource(R.string.editor_ai), callbacks.onAi, selected = aiOpen, visual = visual, iconSize = icon)
            item == ToolbarItem.OVERFLOW -> Box {
                IconAction(EditorIcons.item(item), stringResource(R.string.editor_more_tools), { overflowOpen = true }, visual = visual, iconSize = icon)
                // Options of a tool that only lives in the overflow menu open from here.
                spec.overflow.mapNotNull { it.tool }.forEach { OptionsPopup(it) }
                ToolbarOverflowMenu(
                    expanded = overflowOpen,
                    onDismiss = { overflowOpen = false },
                    spec = spec,
                    prefs = prefs,
                    controller = controller,
                    recordingActive = recordingActive,
                    callbacks = callbacks,
                    onTool = { tool ->
                        overflowOpen = false
                        if (tools.tool == tool) openOptions = tool else onTool(tool)
                    },
                )
            }
        }
    }

    FloatingSurface(modifier, shape = RoundedCornerShape(if (vertical) 22.dp else 22.dp)) {
        val content: @Composable () -> Unit = {
            if (dragHandle != null) {
                val label = stringResource(R.string.editor_move_toolbar)
                Box(
                    dragHandle.size(width = if (vertical) Dimens.minTouch else 24.dp, height = if (vertical) 24.dp else Dimens.minTouch).semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.DragIndicator, null, Modifier.size(16.dp), tint = Inkwell.colors.inkMuted) }
            }
            spec.groups.forEachIndexed { gi, group ->
                if (gi > 0) PillDivider(vertical = !vertical)
                group.forEach { Item(it) }
            }
        }
        if (vertical) {
            Column(Modifier.padding(vertical = ToolbarLayout.END_PADDING_DP.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        } else {
            Row(Modifier.padding(horizontal = ToolbarLayout.END_PADDING_DP.dp).height(Dimens.toolbarHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) { content() }
        }
    }
}

@Composable
private fun ToolbarOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    spec: ToolbarSpec,
    prefs: ToolbarPrefs,
    controller: NotebookController,
    recordingActive: Boolean,
    callbacks: ToolbarCallbacks,
    onTool: (ToolType) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        spec.overflow.forEach { item ->
            val t = item.tool
            val label = when {
                t != null -> stringResource(toolLabel(t))
                item == ToolbarItem.UNDO -> stringResource(R.string.editor_undo)
                item == ToolbarItem.REDO -> stringResource(R.string.editor_redo)
                item == ToolbarItem.RECORD -> stringResource(if (recordingActive) R.string.editor_recording_controls else R.string.editor_record)
                item == ToolbarItem.AI -> stringResource(R.string.editor_ai)
                else -> ""
            }
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(EditorIcons.item(item), null, Modifier.size(Dimens.icon)) },
                trailingIcon = if (t != null && controller.tools.tool == t) {
                    { Icon(Icons.Outlined.Check, null, Modifier.size(Dimens.icon)) }
                } else null,
                enabled = when (item) {
                    ToolbarItem.UNDO -> controller.canUndo
                    ToolbarItem.REDO -> controller.canRedo
                    else -> true
                },
                onClick = {
                    when {
                        t != null -> onTool(t)
                        item == ToolbarItem.UNDO -> { onDismiss(); controller.canvas.undo() }
                        item == ToolbarItem.REDO -> { onDismiss(); controller.canvas.redo() }
                        item == ToolbarItem.RECORD -> { onDismiss(); callbacks.onRecord() }
                        item == ToolbarItem.AI -> { onDismiss(); callbacks.onAi() }
                    }
                },
            )
        }
        if (spec.overflow.isNotEmpty()) HorizontalDivider(color = Inkwell.colors.outline)
        DropdownMenuItem(text = { Text(stringResource(R.string.editor_my_elements)) }, onClick = { onDismiss(); callbacks.onMyElements() })
        HorizontalDivider(color = Inkwell.colors.outline)
        Text(
            stringResource(R.string.editor_toolbar_position),
            style = MaterialTheme.typography.labelMedium,
            color = Inkwell.colors.inkMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ToolbarPosition.entries.forEach { p ->
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            when (p) {
                                ToolbarPosition.TOP -> R.string.editor_position_top
                                ToolbarPosition.BOTTOM -> R.string.editor_position_bottom
                                ToolbarPosition.LEFT -> R.string.editor_position_left
                                ToolbarPosition.RIGHT -> R.string.editor_position_right
                                ToolbarPosition.FLOATING -> R.string.editor_position_floating
                            },
                        ),
                    )
                },
                trailingIcon = if (prefs.position == p) {
                    { Icon(Icons.Outlined.Check, null, Modifier.size(Dimens.icon)) }
                } else null,
                onClick = { onDismiss(); callbacks.onPrefs(prefs.copy(position = p)) },
            )
        }
        HorizontalDivider(color = Inkwell.colors.outline)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_compact_toolbar)) },
            trailingIcon = if (prefs.compact) {
                { Icon(Icons.Outlined.Check, null, Modifier.size(Dimens.icon)) }
            } else null,
            onClick = { onDismiss(); callbacks.onPrefs(prefs.copy(compact = !prefs.compact)) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_focus_hides_toolbar)) },
            trailingIcon = if (prefs.focusModeHidesToolbar) {
                { Icon(Icons.Outlined.Check, null, Modifier.size(Dimens.icon)) }
            } else null,
            onClick = { onDismiss(); callbacks.onPrefs(prefs.copy(focusModeHidesToolbar = !prefs.focusModeHidesToolbar)) },
        )
    }
}
