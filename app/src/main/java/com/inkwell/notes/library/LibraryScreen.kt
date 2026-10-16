package com.inkwell.notes.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.library.ui.LibraryHome
import com.inkwell.notes.library.ui.TrashContent

/**
 * Home: the library of folders, notebooks, PDFs, whiteboards and typed documents.
 *
 * The navigator re-uses this destination when moving between folders (single top), so one
 * [LibraryViewModel] follows [folderId] for the lifetime of the back-stack entry.
 */
@Composable
fun LibraryScreen(nav: AppNavigator, folderId: String?) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(folderId))
    LaunchedEffect(folderId) { vm.onNavFolder(folderId) }
    LibraryHome(vm, nav)
}

/** Recently deleted items with restore / delete forever / empty trash. */
@Composable
fun TrashScreen(nav: AppNavigator) {
    val vm: TrashViewModel = viewModel(factory = TrashViewModel.Factory)
    TrashContent(vm, nav)
}
