package com.inkwell.notes.settings

import androidx.compose.runtime.Composable

/** Shows [content] or, when the biometric app lock is on and engaged, a lock screen. SCAFFOLD STUB. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    content()
}
