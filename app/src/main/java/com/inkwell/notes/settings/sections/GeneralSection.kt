package com.inkwell.notes.settings.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.Composable
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.ScrollDirection
import com.inkwell.notes.core.model.ThemeMode
import com.inkwell.notes.settings.ui.ActionRow
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.SegmentedRow
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SwitchRow

@Composable
internal fun GeneralSection(s: AppSettings, actions: SettingsActions) {
    SectionColumn {
        SettingsGroup("Appearance") {
            SegmentedRow(
                title = "Theme",
                subtitle = "System follows your tablet's light and dark setting.",
                choices = listOf(
                    Choice(ThemeMode.SYSTEM, "System", icon = Icons.Outlined.SettingsBrightness),
                    Choice(ThemeMode.LIGHT, "Light", icon = Icons.Outlined.LightMode),
                    Choice(ThemeMode.DARK, "Dark", icon = Icons.Outlined.DarkMode),
                ),
                selected = s.theme,
                onSelect = { t -> actions.update { it.copy(theme = t) } },
            )
        }

        SettingsGroup("Reading and writing") {
            SwitchRow(
                "Keep screen on",
                s.view.keepScreenOn,
                { v -> actions.update { it.copy(view = it.view.copy(keepScreenOn = v)) } },
                subtitle = "While a document is open, the screen doesn't dim or turn off.",
            )
            RowDivider()
            SwitchRow(
                "Reopen documents where you left off",
                s.view.reopenAtLastPosition,
                { v -> actions.update { it.copy(view = it.view.copy(reopenAtLastPosition = v)) } },
                subtitle = "Returns to the last page, zoom and scroll position. Off always starts on the first page.",
            )
            RowDivider()
            SegmentedRow(
                title = "Page scrolling",
                subtitle = "Vertical scrolls pages as one continuous strip; horizontal turns them like a book.",
                choices = listOf(
                    Choice(ScrollDirection.VERTICAL, "Vertical", icon = Icons.Outlined.SwapVert),
                    Choice(ScrollDirection.HORIZONTAL, "Horizontal", icon = Icons.Outlined.SwapHoriz),
                ),
                selected = s.view.scrollDirection,
                onSelect = { d -> actions.update { it.copy(view = it.view.copy(scrollDirection = d)) } },
            )
            RowDivider()
            SwitchRow(
                "Page edges",
                s.view.showPageShadows,
                { v -> actions.update { it.copy(view = it.view.copy(showPageShadows = v)) } },
                subtitle = "A soft edge separates pages from the desk behind them.",
            )
        }

        SettingsGroup("Help") {
            ActionRow(
                title = "Show the introduction again",
                subtitle = "Three short tips: the S Pen side button, automatic file names and your own AI key.",
                actionLabel = "Show",
                onAction = { actions.update { it.copy(onboardingDone = false) } },
            )
        }
    }
}
