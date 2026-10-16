package com.inkwell.notes.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ai.ui.AiSettingsContent
import com.inkwell.notes.ai.ui.AiUsageContent
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.app.SettingsSection
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.export.ui.StorageSettingsContent
import com.inkwell.notes.settings.sections.AboutSection
import com.inkwell.notes.settings.sections.GeneralSection
import com.inkwell.notes.settings.sections.HostedSection
import com.inkwell.notes.settings.sections.InputSection
import com.inkwell.notes.settings.sections.LocalDetailPadding
import com.inkwell.notes.settings.sections.NamingSection
import com.inkwell.notes.settings.sections.OcrSection
import com.inkwell.notes.settings.sections.PaperSection
import com.inkwell.notes.settings.sections.PrivacySection
import com.inkwell.notes.settings.sections.RecordingSection
import com.inkwell.notes.settings.sections.SectionHost
import com.inkwell.notes.settings.sections.ToolbarSection
import com.inkwell.notes.settings.ui.Radius12
import com.inkwell.notes.settings.ui.Radius8
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsIconButton
import com.inkwell.notes.settings.ui.rememberSettingsActions
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** Below this width settings use single-pane navigation (list, then detail). */
private val TwoPaneMinWidth = 700.dp
private val ListPaneWidth = 300.dp

/**
 * Settings: a 300dp section list beside the selected section on wide windows, list-then-detail
 * navigation on narrow ones. Every control writes through [SettingsActions] to
 * graph.settings; AI, usage and storage host the panes owned by those features.
 */
@Composable
fun SettingsScreen(section: SettingsSection, nav: AppNavigator) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val settings by graph.settings.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberSettingsActions(graph, snackbar)
    var selected by rememberSaveable(section) { mutableStateOf(section) }
    // On narrow screens an explicit section (deep link) opens straight into its detail.
    var narrowDetail by rememberSaveable(section) { mutableStateOf(section != SettingsSection.GENERAL) }
    val c = Inkwell.colors

    BoxWithConstraints(Modifier.fillMaxSize().background(c.ground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= TwoPaneMinWidth
        val detailOnly = !wide && narrowDetail
        BackHandler(enabled = detailOnly) { narrowDetail = false }

        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = if (detailOnly) selected.title else "Settings",
                backLabel = if (detailOnly) "Back to settings" else "Back",
                onBack = { if (detailOnly) narrowDetail = false else nav.back() },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
            when {
                wide -> Row(Modifier.fillMaxSize()) {
                    SectionList(
                        selected = selected,
                        onSelect = { selected = it; narrowDetail = true },
                        withSummaries = false,
                        modifier = Modifier.width(ListPaneWidth).fillMaxHeight(),
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
                    SectionDetail(
                        section = selected,
                        settings = settings,
                        actions = actions,
                        openSection = { selected = it },
                        showHeader = true,
                        wide = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                detailOnly -> SectionDetail(
                    section = selected,
                    settings = settings,
                    actions = actions,
                    openSection = { selected = it },
                    showHeader = false,
                    wide = false,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> SectionList(
                    selected = selected,
                    onSelect = { selected = it; narrowDetail = true },
                    withSummaries = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 560.dp),
        ) { data ->
            Snackbar(
                data,
                shape = Radius12,
                containerColor = c.ink,
                contentColor = c.ground,
                actionColor = c.accentContainer,
                dismissActionContentColor = c.ground,
            )
        }
    }
}

@Composable
private fun TopBar(title: String, backLabel: String, onBack: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconButton(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, onBack, tint = c.ink)
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

@Composable
private fun SectionList(
    selected: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
    withSummaries: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .selectableGroup(),
    ) {
        SettingsCatalog.groups.forEachIndexed { gi, group ->
            Text(
                group.title,
                style = MaterialTheme.typography.labelMedium,
                color = c.inkMuted,
                modifier = Modifier.padding(start = 12.dp, top = if (gi == 0) 8.dp else 20.dp, bottom = 6.dp),
            )
            group.sections.forEach { s ->
                SectionListItem(s, selected = !withSummaries && s == selected, withSummary = withSummaries) { onSelect(s) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionListItem(section: SettingsSection, selected: Boolean, withSummary: Boolean, onClick: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (withSummary) 64.dp else 48.dp)
            .clip(Radius8)
            .background(if (selected) c.accentContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SettingsCatalog.icon(section), null, Modifier.size(Dimens.icon), tint = if (selected) c.ink else c.inkMuted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                section.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (withSummary) {
                Text(SettingsCatalog.summary(section), style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 2)
            }
        }
        if (withSummary) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
    }
}

@Suppress("REDUNDANT_ELSE_IN_WHEN")
@Composable
private fun SectionDetail(
    section: SettingsSection,
    settings: AppSettings,
    actions: SettingsActions,
    openSection: (SettingsSection) -> Unit,
    showHeader: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val pad = if (wide) 32.dp else 16.dp
    CompositionLocalProvider(LocalDetailPadding provides pad) {
        Column(modifier) {
            if (showHeader) {
                Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 24.dp, bottom = 4.dp)) {
                    Text(section.title, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(SettingsCatalog.summary(section), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                }
            }
            key(section) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SectionHost {
                        when (section) {
                            SettingsSection.GENERAL -> GeneralSection(settings, actions)
                            SettingsSection.INPUT -> InputSection(settings, actions)
                            SettingsSection.TOOLBAR -> ToolbarSection(settings, actions)
                            SettingsSection.NAMING -> NamingSection(settings, actions)
                            SettingsSection.PAPER -> PaperSection(settings, actions)
                            SettingsSection.RECORDING -> RecordingSection(settings, actions, openSection)
                            SettingsSection.OCR -> OcrSection(settings, actions, openSection)
                            SettingsSection.AI -> HostedSection { AiSettingsContent(it) }
                            SettingsSection.USAGE -> HostedSection { AiUsageContent(it) }
                            SettingsSection.STORAGE -> HostedSection { StorageSettingsContent(it) }
                            SettingsSection.PRIVACY -> PrivacySection(settings, actions)
                            SettingsSection.ABOUT -> AboutSection(actions, openSection)
                            else -> GeneralSection(settings, actions)
                        }
                    }
                }
            }
        }
    }
}
