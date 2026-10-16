package com.inkwell.notes.core.naming

import java.time.ZoneId

/**
 * Pure title computations used by the library repository: new untitled documents, imported
 * names, manual renames, auto-applied topics and duplicates. Every result is unique against
 * the given existing titles (case-insensitive, " (2)" style suffixes via [DateNaming.unique]).
 */
object TitleRules {
    const val MAX_TITLE_CHARS = 160
    const val COPY_SUFFIX = " copy"

    private val CONTROL = Regex("[\\p{Cntrl}\\u2028\\u2029]")
    private val WHITESPACE = Regex("\\s+")
    private val IMPORT_EXTENSIONS = setOf(
        "pdf", "png", "jpg", "jpeg", "webp", "heic", "heif", "gif", "bmp", "tif", "tiff",
        "txt", "md", "markdown", "rtf", "doc", "docx", "inkwell", "zip",
    )

    /** "YYYYMMDD_Untitled", numbered when that title is taken. */
    fun untitled(createdAt: Long, existing: Collection<String>, zone: ZoneId = ZoneId.systemDefault()): String =
        DateNaming.unique(DateNaming.initialTitle(createdAt, zone), existing)

    /** Collapses whitespace/control characters and limits the length; null when nothing is left. */
    fun sanitize(raw: String): String? {
        val s = raw.replace(CONTROL, " ").replace(WHITESPACE, " ").trim()
        if (s.isEmpty()) return null
        return if (s.length <= MAX_TITLE_CHARS) s else s.take(MAX_TITLE_CHARS).trimEnd()
    }

    /** Title kept for an imported file: its display name without folders or a known extension. */
    fun fromImportedName(originalName: String): String? {
        val base = originalName.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', "")
        val stem = if (ext.isNotEmpty() && ext.lowercase() in IMPORT_EXTENSIONS && base.length > ext.length + 1) {
            base.dropLast(ext.length + 1)
        } else {
            base
        }
        return sanitize(stem)
    }

    /** A manual rename, or null when [raw] is blank (rejected). */
    fun manual(raw: String, otherTitles: Collection<String>): String? =
        sanitize(raw)?.let { DateNaming.unique(it, otherTitles) }

    /**
     * The auto-name for [topic]: creation-date prefix + cleaned topic, unique among the other
     * documents. Null when the topic is empty or too generic to use.
     */
    fun auto(createdAt: Long, topic: String, otherTitles: Collection<String>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val clean = DateNaming.cleanTopic(topic) ?: return null
        return DateNaming.unique(DateNaming.titleFor(createdAt, clean, zone), otherTitles)
    }

    /** "<title> copy", numbered when taken. */
    fun copyOf(title: String, existing: Collection<String>): String {
        val base = sanitize(title) ?: DateNaming.UNTITLED
        val trimmed = if (base.length + COPY_SUFFIX.length > MAX_TITLE_CHARS) base.take(MAX_TITLE_CHARS - COPY_SUFFIX.length).trimEnd() else base
        return DateNaming.unique(trimmed + COPY_SUFFIX, existing)
    }
}
