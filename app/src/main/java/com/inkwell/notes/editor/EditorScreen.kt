package com.inkwell.notes.editor

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.editor.split.SplitLayout
import com.inkwell.notes.editor.ui.KeepScreenOn
import com.inkwell.notes.editor.ui.NotebookPane
import com.inkwell.notes.editor.ui.PaneMode

/**
 * Notebook and PDF editor: slim top bar, floating tool pill, the handwriting canvas, page
 * management, outline, in-document search, AI and recordings panels, focus mode.
 */
@Composable
fun EditorScreen(docId: String, initialPage: Int?, nav: AppNavigator) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    KeepScreenOn(settings.view.keepScreenOn)
    NotebookPane(
        docId = docId,
        initialPage = initialPage,
        nav = nav,
        mode = PaneMode.FULL,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    )
}

/** Two documents side by side with a draggable divider. */
@Composable
fun SplitScreen(leftDocId: String, rightDocId: String, nav: AppNavigator) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    SplitLayout(leftDocId, rightDocId, nav, settings.view.keepScreenOn)
}
