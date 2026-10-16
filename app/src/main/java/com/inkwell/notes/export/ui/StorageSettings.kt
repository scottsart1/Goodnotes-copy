package com.inkwell.notes.export.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * "Where outputs are saved": per output type (notes, transcripts, audio) choose this device
 * only, a folder (SAF), and/or the yourpitbox.com notes vault (URL + password, test connection),
 * sync now, backup/restore. SCAFFOLD STUB.
 */
@Composable
fun StorageSettingsContent(modifier: Modifier = Modifier) {
    Box(modifier) { Text("Storage & sync") }
}
