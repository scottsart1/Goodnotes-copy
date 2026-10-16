package com.inkwell.notes.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inkwell.notes.app.AppNavigator
import com.inkwell.notes.app.SettingsSection

/** Two-pane settings. SCAFFOLD STUB. */
@Composable
fun SettingsScreen(section: SettingsSection, nav: AppNavigator) {
    Box(Modifier.fillMaxSize()) { Text("Settings") }
}
