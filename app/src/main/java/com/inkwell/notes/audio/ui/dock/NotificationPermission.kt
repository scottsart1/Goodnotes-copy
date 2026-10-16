package com.inkwell.notes.audio.ui.dock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * On Android 13+, asks once per app session for notification permission when a recording
 * starts, so the ongoing "Recording" notification with Pause and Stop is visible while the
 * app is in the background. Recording works either way.
 */
@Composable
internal fun RequestRecordingNotificationPermission(recordingActive: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(recordingActive) {
        if (!recordingActive || DockMemory.askedForNotifications) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        DockMemory.askedForNotifications = true
        if (!granted) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
