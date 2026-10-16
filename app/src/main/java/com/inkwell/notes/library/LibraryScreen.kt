package com.inkwell.notes.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inkwell.notes.app.AppNavigator

/** Home: the library of folders, notebooks, PDFs, whiteboards and typed documents. SCAFFOLD STUB. */
@Composable
fun LibraryScreen(nav: AppNavigator, folderId: String?) {
    Box(Modifier.fillMaxSize()) { Text("Library") }
}

/** Recently deleted items with restore / delete forever / empty trash. SCAFFOLD STUB. */
@Composable
fun TrashScreen(nav: AppNavigator) {
    Box(Modifier.fillMaxSize()) { Text("Trash") }
}
