package com.inkwell.notes.editor

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateListOf
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.editor.logic.SessionTabs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Items cut or copied in the editor, kept for this process (survives switching documents). */
data class ClipEntry(val content: PageContent, val sourceDocId: String)

/** Process-level editor state shared by every open editor pane. */
object EditorSession {
    /** Documents opened in this session (tab strip). */
    val tabs = SessionTabs()

    private val _clipboard = MutableStateFlow<ClipEntry?>(null)
    val clipboard: StateFlow<ClipEntry?> = _clipboard.asStateFlow()

    fun setClipboard(content: PageContent?, sourceDocId: String) {
        _clipboard.value = content?.takeIf { !it.isEmpty }?.let { ClipEntry(it, sourceDocId) }
    }

    /** Document whose editor opened the current split view, so closing a pane can go back to it. */
    @Volatile var splitOrigin: String? = null
}

/** Snackbar messages of one pane. Safe to call from any thread. */
class EditorMessages(private val scope: CoroutineScope) {
    val host = SnackbarHostState()

    fun show(message: String, actionLabel: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        scope.launch(Dispatchers.Main.immediate) {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = if (actionLabel != null || long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/** Labels of long-running operations shown as a small progress pill; the canvas stays usable. */
class BusyTracker {
    val labels = mutableStateListOf<String>()

    suspend fun <T> run(label: String, block: suspend () -> T): T {
        labels += label
        try {
            return block()
        } finally {
            labels.remove(label)
        }
    }
}

/** Rethrows coroutine cancellation; everything else is a reportable failure. */
internal inline fun <T> catchingNonCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}
