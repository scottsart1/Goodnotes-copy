package com.inkwell.notes.export.format

import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import java.io.OutputStream

/** Opening user-chosen documents (Storage Access Framework) for writing. */
internal object SafStreams {
    /**
     * Opens [uri] truncated ("wt") where the provider supports it; some providers only accept
     * "w", which may not truncate, so it is the fallback. Returns null when nothing opens.
     */
    fun openForWrite(context: Context, uri: Uri): OutputStream? {
        val cr = context.contentResolver
        return try {
            cr.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            cr.openOutputStream(uri, "w")
        } catch (e: IllegalArgumentException) {
            cr.openOutputStream(uri, "w")
        } catch (e: UnsupportedOperationException) {
            cr.openOutputStream(uri, "w")
        }
    }
}
