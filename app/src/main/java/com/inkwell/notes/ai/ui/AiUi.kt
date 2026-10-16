package com.inkwell.notes.ai.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the AI panel is looking at. Built by the editor from the current selection/page. */
data class AiPanelContext(
    val docId: String,
    /** Recognised or typed text of the selection, if any. */
    val selectionText: String? = null,
    /** Rendered image of the selection (for vision models), if any. */
    val selectionImage: Bitmap? = null,
    val pageId: String? = null,
    val pageIndex: Int? = null,
    /** Pre-filled prompt such as "Explain this passage". */
    val initialPrompt: String? = null,
    /** Send [initialPrompt] immediately (selection menu actions). */
    val autoSend: Boolean = false,
    /** Extra documents the user explicitly added to the conversation. */
    val extraDocIds: List<String> = emptyList(),
)

/**
 * Contextual AI chat panel shown beside the notebook (420dp on tablets, full width on phones).
 * Never edits notes itself: output is only inserted through [onInsert] when the user taps
 * "Insert into page". Source chips call [onOpenSource] with a 0-based page index.
 */
@Composable
fun AiPanel(
    context: AiPanelContext,
    onInsert: (text: String) -> Unit,
    onOpenSource: (pageIndex: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AiPanelImpl(context, onInsert, onOpenSource, onClose, modifier)
}

/**
 * Providers, keys, validation, models, routing, limits and privacy. Scrolls itself when given a
 * bounded height; lays out flat inside a host that already scrolls.
 */
@Composable
fun AiSettingsContent(modifier: Modifier = Modifier) {
    AiSettingsImpl(modifier)
}

/** Token usage and estimated cost by provider/model, limits and warnings, recent requests. */
@Composable
fun AiUsageContent(modifier: Modifier = Modifier) {
    AiUsageImpl(modifier)
}
