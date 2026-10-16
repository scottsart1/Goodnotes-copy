package com.inkwell.notes.settings.sections

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.notes.BuildConfig
import com.inkwell.notes.app.SettingsSection
import com.inkwell.notes.core.model.StorageInfo
import com.inkwell.notes.settings.logic.LicenseEntry
import com.inkwell.notes.settings.logic.Licenses
import com.inkwell.notes.settings.logic.SettingsFormat
import com.inkwell.notes.settings.ui.ActionRow
import com.inkwell.notes.settings.ui.InfoRow
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface StorageLoad {
    data object Loading : StorageLoad
    data class Loaded(val info: StorageInfo) : StorageLoad
    data object Failed : StorageLoad
}

@Composable
internal fun AboutSection(actions: SettingsActions, openSection: (SettingsSection) -> Unit) {
    val c = Inkwell.colors
    val storage by produceState<StorageLoad>(StorageLoad.Loading) {
        value = withContext(Dispatchers.IO) {
            try {
                StorageLoad.Loaded(actions.graph.library.storageInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Settings", "Storage info failed: ${e.javaClass.simpleName}")
                StorageLoad.Failed
            }
        }
    }

    SectionColumn {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Inkwell", style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMuted,
            )
            Text(
                "Handwritten notes for the Galaxy Tab and S Pen. Everything works offline; AI is optional and uses your own key.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SettingsGroup("Storage") {
            when (val s = storage) {
                StorageLoad.Loading -> InfoRow("Documents", "Calculating…")
                StorageLoad.Failed -> InfoRow("Storage details", "Couldn't be read right now")
                is StorageLoad.Loaded -> {
                    val info = s.info
                    InfoRow("Documents", info.documentCount.toString())
                    RowDivider()
                    InfoRow("Space used by Inkwell", SettingsFormat.bytes(info.totalBytes))
                    RowDivider()
                    InfoRow("Ink and pages", SettingsFormat.bytes(info.inkBytes))
                    RowDivider()
                    InfoRow("PDFs and images", SettingsFormat.bytes(info.pdfBytes))
                    RowDivider()
                    InfoRow("Audio recordings", SettingsFormat.bytes(info.audioBytes))
                    RowDivider()
                    InfoRow("Free on this device", SettingsFormat.bytes(info.freeBytes))
                }
            }
            RowDivider()
            ActionRow(
                title = "Storage, sync and backups",
                subtitle = "Choose where copies go and back up your library.",
                actionLabel = "Open",
                onAction = { openSection(SettingsSection.STORAGE) },
            )
        }

        SettingsGroup("Open-source licences", description = "Inkwell is built with these components. Tap one to read its licence.") {
            Licenses.entries.forEachIndexed { i, entry ->
                if (i > 0) RowDivider()
                LicenseRow(entry, actions)
            }
        }

        Text(
            "Inkwell has no analytics, ads or telemetry. Fonts: Figtree under the SIL Open Font License 1.1.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun LicenseRow(entry: LicenseEntry, actions: SettingsActions) {
    val c = Inkwell.colors
    val uri = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${entry.license}") {
                try {
                    uri.openUri(entry.url)
                } catch (e: Exception) {
                    actions.message("No browser is available to open ${entry.url}")
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.ink)
            Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            Text(entry.license, style = MaterialTheme.typography.labelMedium, color = c.accent)
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
    }
}
