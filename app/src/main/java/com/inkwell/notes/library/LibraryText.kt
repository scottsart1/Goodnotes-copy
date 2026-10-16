package com.inkwell.notes.library

import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.naming.DateNaming
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil

/**
 * Data-derived text for cards and rows ("Notebook · 12 pages · Edited 2h ago", "4.2 MB",
 * "26 days left"). Kept in plain Kotlin so it is unit-tested; static UI labels live in
 * res/values/strings_library.xml.
 */
object LibraryText {
    const val SEPARATOR = " · "
    const val TRASH_RETENTION_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun kindLabel(kind: DocKind): String = when (kind) {
        DocKind.NOTEBOOK -> "Notebook"
        DocKind.PDF -> "PDF"
        DocKind.WHITEBOARD -> "Whiteboard"
        DocKind.TYPED -> "Typed document"
    }

    /** "12 pages" for paged kinds; null for whiteboards and typed documents. */
    fun pagesLabel(kind: DocKind, pageCount: Int): String? = when (kind) {
        DocKind.NOTEBOOK, DocKind.PDF -> if (pageCount == 1) "1 page" else "$pageCount pages"
        DocKind.WHITEBOARD, DocKind.TYPED -> null
    }

    fun metaLine(doc: DocumentMeta, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val parts = ArrayList<String>(3)
        parts += kindLabel(doc.kind)
        pagesLabel(doc.kind, doc.pageCount)?.let { parts += it }
        parts += "Edited " + RelativeTime.phrase(doc.modifiedAt, nowMs, zone, locale)
        return parts.joinToString(SEPARATOR)
    }

    /** The "Auto-names after 24h" chip: only while still untitled and waiting for its naming window. */
    fun showsAutoNameChip(doc: DocumentMeta, autoNamingEnabled: Boolean): Boolean =
        autoNamingEnabled && doc.autoNameStatus == AutoNameStatus.PENDING && DateNaming.isUntitled(doc.title)

    fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

    /** "Folder · 5 items" for trash rows. */
    fun folderLine(itemCount: Int): String = "Folder" + SEPARATOR + itemCount(itemCount)

    fun documentCount(count: Int): String = if (count == 1) "1 document" else "$count documents"

    /** Binary units with one decimal from MB up: "0 B", "740 KB", "12.4 MB", "1.2 GB". */
    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < mb -> "${(bytes / kb).toLong().coerceAtLeast(1)} KB"
            bytes < gb -> String.format(locale, "%.1f MB", bytes / mb)
            else -> String.format(locale, "%.1f GB", bytes / gb)
        }
    }

    /** "N documents · X MB on this tablet". */
    fun storageSummary(documentCount: Int, totalBytes: Long, locale: Locale = Locale.getDefault()): String =
        documentCount(documentCount) + SEPARATOR + formatBytes(totalBytes, locale) + " on this tablet"

    /** Whole days until a trashed item is purged (rounded up; 0 = today). */
    fun trashDaysLeft(trashedAtMs: Long, nowMs: Long, retentionDays: Int = TRASH_RETENTION_DAYS): Int {
        val purgeAt = trashedAtMs + retentionDays * DAY_MS
        val left = purgeAt - nowMs
        if (left <= 0) return 0
        return ceil(left.toDouble() / DAY_MS).toInt().coerceAtMost(retentionDays)
    }

    fun trashDaysLeftLabel(trashedAtMs: Long, nowMs: Long): String = when (val d = trashDaysLeft(trashedAtMs, nowMs)) {
        0 -> "Deletes today"
        1 -> "1 day left"
        else -> "$d days left"
    }

    fun trashLine(trashedAtMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        "Deleted " + RelativeTime.phrase(trashedAtMs, nowMs, zone, locale) + SEPARATOR + trashDaysLeftLabel(trashedAtMs, nowMs)
}
