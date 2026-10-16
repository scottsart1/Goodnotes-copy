package com.inkwell.notes.export.format

import android.content.Context
import com.inkwell.notes.R
import com.inkwell.notes.export.backup.PackageException
import java.io.FileNotFoundException
import java.io.IOException

/** An export/import failure whose [message] is ready to show to the user. */
class ExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

object ExportErrors {
    /** Converts any failure into an [ExportException] with a user-facing message. */
    fun wrap(context: Context, t: Throwable): ExportException = when (t) {
        is ExportException -> t
        is PackageException -> ExportException(context.getString(packageMessage(t.reason)), t)
        is OutOfMemoryError -> ExportException(context.getString(R.string.export_err_memory), t)
        is SecurityException, is FileNotFoundException -> ExportException(context.getString(R.string.export_err_write), t)
        is IOException -> ExportException(context.getString(if (isNoSpace(t)) R.string.export_err_no_space else R.string.export_err_write), t)
        else -> ExportException(context.getString(R.string.export_err_generic), t)
    }

    fun of(context: Context, res: Int, vararg args: Any): ExportException = ExportException(context.getString(res, *args))

    private fun packageMessage(reason: PackageException.Reason): Int = when (reason) {
        PackageException.Reason.NOT_A_PACKAGE -> R.string.export_err_pkg_invalid
        PackageException.Reason.CORRUPT -> R.string.export_err_pkg_corrupt
        PackageException.Reason.NEWER_VERSION -> R.string.export_err_pkg_newer
        PackageException.Reason.TOO_LARGE, PackageException.Reason.NO_SPACE -> R.string.export_err_no_space
        PackageException.Reason.UNREADABLE -> R.string.export_err_pkg_unreadable
    }

    /** ENOSPC surfaces as an IOException whose message mentions the condition. */
    fun isNoSpace(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val m = cur.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            cur = cur.cause
            depth++
        }
        return false
    }
}
