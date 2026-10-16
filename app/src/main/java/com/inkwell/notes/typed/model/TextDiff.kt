package com.inkwell.notes.typed.model

/** A single contiguous replacement: [deletedLength] chars at [start] replaced by [inserted]. */
data class TextEdit(val start: Int, val deletedLength: Int, val inserted: String) {
    val isNoOp: Boolean get() = deletedLength == 0 && inserted.isEmpty()
}

/**
 * Finds the edit that turns one text into another. Text fields report whole new values, so
 * the editor diffs them to keep inline formatting offsets exact. When the caret position
 * after the edit is known, it disambiguates repeated characters ("aa" -> "aaa").
 */
object TextDiff {

    fun compute(old: String, new: String, caretAfter: Int? = null): TextEdit {
        if (old == new) return TextEdit(0, 0, "")
        if (caretAfter != null && caretAfter in 0..new.length) {
            val suffixLen = new.length - caretAfter
            if (suffixLen <= old.length && new.regionMatches(caretAfter, old, old.length - suffixLen, suffixLen)) {
                val oldHeadEnd = old.length - suffixLen
                val prefix = commonPrefix(old, 0, oldHeadEnd, new, 0, caretAfter)
                return TextEdit(prefix, oldHeadEnd - prefix, new.substring(prefix, caretAfter))
            }
        }
        val prefix = commonPrefix(old, 0, old.length, new, 0, new.length)
        val maxSuffix = minOf(old.length, new.length) - prefix
        var suffix = 0
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return TextEdit(prefix, old.length - suffix - prefix, new.substring(prefix, new.length - suffix))
    }

    private fun commonPrefix(a: String, aStart: Int, aEnd: Int, b: String, bStart: Int, bEnd: Int): Int {
        val n = minOf(aEnd - aStart, bEnd - bStart)
        var i = 0
        while (i < n && a[aStart + i] == b[bStart + i]) i++
        return i
    }
}
