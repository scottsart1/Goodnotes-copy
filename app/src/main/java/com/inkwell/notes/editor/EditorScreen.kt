package com.inkwell.notes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inkwell.notes.app.AppNavigator

/** Notebook and PDF editor. SCAFFOLD STUB. */
@Composable
fun EditorScreen(docId: String, initialPage: Int?, nav: AppNavigator) {
    Box(Modifier.fillMaxSize()) { Text("Editor $docId") }
}

/** Two documents side by side with a draggable divider. SCAFFOLD STUB. */
@Composable
fun SplitScreen(leftDocId: String, rightDocId: String, nav: AppNavigator) {
    Box(Modifier.fillMaxSize()) { Text("Split") }
}
