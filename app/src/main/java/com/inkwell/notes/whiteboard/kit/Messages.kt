package com.inkwell.notes.whiteboard.kit

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow

/** A user-visible message, resolved from string resources at display time. */
data class UiMessage(
    @param:StringRes val text: Int,
    val args: List<Any> = emptyList(),
    @param:StringRes val action: Int? = null,
    val onAction: (() -> Unit)? = null,
    val long: Boolean = false,
)

/** Shows every message of [messages] in [host] (one at a time). */
@Composable
fun CollectMessages(messages: Flow<UiMessage>, host: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(messages, host) {
        messages.collect { m ->
            val text = context.getString(m.text, *m.args.toTypedArray())
            val result = host.showSnackbar(
                message = text,
                actionLabel = m.action?.let { context.getString(it) },
                duration = if (m.long || m.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }
}
