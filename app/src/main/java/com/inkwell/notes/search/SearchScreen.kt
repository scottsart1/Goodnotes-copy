package com.inkwell.notes.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inkwell.notes.app.AppNavigator

/** Library-wide search across titles, typed text, handwriting, OCR, PDFs and transcripts. SCAFFOLD STUB. */
@Composable
fun SearchScreen(initialQuery: String, nav: AppNavigator) {
    Box(Modifier.fillMaxSize()) { Text("Search") }
}
