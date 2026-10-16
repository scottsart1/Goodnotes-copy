package com.inkwell.notes.library

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.inkwell.notes.core.model.DocumentMeta

/** Text produced off the UI (view models, jobs) and resolved against resources when shown. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText
    data class Raw(val text: String) : UiText

    fun resolve(res: Resources): String = when (this) {
        is Res -> if (args.isEmpty()) res.getString(id)
        else res.getString(id, *args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray())
        is Plural -> res.getQuantityString(id, count, count)
        is Raw -> text
    }
}

/** A snackbar message, optionally with one action (Undo, Open). */
class LibraryMessage(
    val text: UiText,
    val actionLabel: UiText? = null,
    val long: Boolean = false,
    val action: (() -> Unit)? = null,
)

/** One-off things the library screen must do in response to a view-model action. */
sealed interface LibraryEvent {
    data class Message(val message: LibraryMessage) : LibraryEvent
    data class Open(val doc: DocumentMeta) : LibraryEvent

    /** Leave a folder that was deleted while it was open. */
    data class ShowFolder(val folderId: String?) : LibraryEvent
}
