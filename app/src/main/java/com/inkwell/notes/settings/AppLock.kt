package com.inkwell.notes.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.settings.lock.AppLockManager
import com.inkwell.notes.settings.lock.LockScreen
import com.inkwell.notes.settings.onboarding.OnboardingOverlay
import com.inkwell.notes.settings.ui.SettingsWriter

private const val CONTENT_STATE_KEY = "inkwell-app-content"

/**
 * Shows [content] or, when the biometric app lock is on and engaged, a lock screen.
 *
 * The lock engages at cold start and when the app returns after the chosen time in the
 * background. While locked, [content] is not composed at all; its saveable state (navigation
 * back stack, scroll positions) is kept and restored on unlock. Background work, such as a
 * running recording, keeps going. On first run an introduction is shown over the content.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    // Installed before the first frame so a locked cold start never shows content.
    remember(graph) {
        AppLockManager.install(graph)
        true
    }
    val settings by graph.settings.settings.collectAsState()
    val locked by AppLockManager.locked.collectAsState()
    val holder = rememberSaveableStateHolder()
    val lockOn = settings.privacy.appLock

    LaunchedEffect(lockOn) { if (!lockOn) AppLockManager.onLockDisabled() }

    Box(Modifier.fillMaxSize()) {
        if (locked && lockOn) {
            LockScreen(onUnlocked = { AppLockManager.unlock() })
        } else {
            holder.SaveableStateProvider(CONTENT_STATE_KEY) { content() }
            if (!settings.onboardingDone) {
                OnboardingOverlay(
                    delayHours = settings.naming.delayHours,
                    onFinish = { SettingsWriter.get(graph).submit { it.copy(onboardingDone = true) } },
                )
            }
        }
    }
}
