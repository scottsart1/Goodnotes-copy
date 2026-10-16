package com.inkwell.notes.audio.ui.dock

import androidx.compose.runtime.mutableStateListOf

/**
 * The docks currently on screen. Split view (or a navigation cross-fade) can compose two
 * editors at once; only one of them shows the recorder: the editor of the recording's
 * document if present, otherwise the first one attached. Main thread only (snapshot state,
 * so composables re-evaluate when docks come and go).
 */
internal object DockRegistry {
    private class Entry(val token: Any, val docId: String)

    private val docks = mutableStateListOf<Entry>()

    fun attach(token: Any, docId: String) {
        docks.removeAll { it.token === token }
        docks.add(Entry(token, docId))
    }

    fun detach(token: Any) {
        docks.removeAll { it.token === token }
    }

    /** Whether the dock [token] (in [docId]'s editor) shows the recording of [recordingDocId]. */
    fun shows(token: Any, docId: String, recordingDocId: String): Boolean {
        if (docId == recordingDocId) return docks.firstOrNull { it.docId == recordingDocId }?.token.let { it == null || it === token }
        if (docks.any { it.docId == recordingDocId }) return false
        return isPrimary(token)
    }

    /** The first dock on screen (used for messages not tied to a document). */
    fun isPrimary(token: Any): Boolean = docks.firstOrNull()?.token.let { it == null || it === token }
}
