package com.inkwell.notes.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/** Horizontal padding of the detail pane (32dp two-pane, 16dp single-pane). */
internal val LocalDetailPadding = staticCompositionLocalOf { 16.dp }

private val MaxSectionWidth = 760.dp
private val MaxHostedWidth = 840.dp

/**
 * Gives each visit to a section its own ViewModel scope, cleared when the section closes, so
 * hosted panes (AI providers, usage, storage) always start from current data, e.g. after keys
 * were deleted under Privacy.
 */
@Composable
internal fun SectionHost(content: @Composable () -> Unit) {
    val parent = LocalViewModelStoreOwner.current
    val owner = remember(parent) { SectionViewModelOwner(parent) }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

private class SectionViewModelOwner(private val parent: ViewModelStoreOwner?) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
}

/** Scrolling column for the sections implemented here. */
@Composable
internal fun SectionColumn(content: @Composable ColumnScope.() -> Unit) {
    val pad = LocalDetailPadding.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.widthIn(max = MaxSectionWidth + pad * 2).fillMaxWidth().padding(horizontal = pad).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
        Spacer(Modifier.height(48.dp))
    }
}

/** Hosts a pane owned by another feature; it scrolls itself inside the bounded area. */
@Composable
internal fun HostedSection(content: @Composable (Modifier) -> Unit) {
    val pad: Dp = LocalDetailPadding.current
    Box(Modifier.fillMaxSize()) {
        content(Modifier.widthIn(max = MaxHostedWidth + pad * 2).fillMaxSize().padding(start = pad, end = pad, top = 16.dp))
    }
}
