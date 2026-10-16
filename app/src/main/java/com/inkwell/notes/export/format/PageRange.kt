package com.inkwell.notes.export.format

/**
 * Parses page ranges typed by the user ("1-3, 5, 8-") into 0-based page indices.
 * Page numbers are 1-based as shown in the editor; an open range ("8-") runs to the last
 * page and "-3" starts at the first. Duplicates are removed; order is ascending.
 */
object PageRange {
    sealed interface Result {
        data class Ok(val indices: List<Int>) : Result
        /** [reason] is a stable code the UI maps to a message; [token] is the offending part. */
        data class Error(val reason: Reason, val token: String) : Result
    }

    enum class Reason { EMPTY, SYNTAX, OUT_OF_RANGE, REVERSED }

    fun parse(input: String, pageCount: Int): Result {
        val text = input.trim()
        if (text.isEmpty() || pageCount <= 0) return Result.Error(Reason.EMPTY, input)
        val out = sortedSetOf<Int>()
        for (raw in text.split(',', ';')) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            val parts = token.split('-', '–', '—').map { it.trim() }
            when (parts.size) {
                1 -> {
                    val n = parts[0].toIntOrNull() ?: return Result.Error(Reason.SYNTAX, token)
                    if (n < 1 || n > pageCount) return Result.Error(Reason.OUT_OF_RANGE, token)
                    out += n - 1
                }
                2 -> {
                    val start = if (parts[0].isEmpty()) 1 else parts[0].toIntOrNull() ?: return Result.Error(Reason.SYNTAX, token)
                    val end = if (parts[1].isEmpty()) pageCount else parts[1].toIntOrNull() ?: return Result.Error(Reason.SYNTAX, token)
                    if (parts[0].isEmpty() && parts[1].isEmpty()) return Result.Error(Reason.SYNTAX, token)
                    if (start < 1 || end > pageCount || start > pageCount || end < 1) return Result.Error(Reason.OUT_OF_RANGE, token)
                    if (start > end) return Result.Error(Reason.REVERSED, token)
                    for (n in start..end) out += n - 1
                }
                else -> return Result.Error(Reason.SYNTAX, token)
            }
        }
        if (out.isEmpty()) return Result.Error(Reason.EMPTY, input)
        return Result.Ok(out.toList())
    }

    /** Compact text for a set of 0-based indices: [0,1,2,4] -> "1-3, 5". */
    fun format(indices: Collection<Int>): String {
        val sorted = indices.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val parts = ArrayList<String>()
        var start = sorted[0]
        var prev = start
        for (i in 1..sorted.size) {
            val cur = sorted.getOrNull(i)
            if (cur != null && cur == prev + 1) {
                prev = cur
                continue
            }
            parts += if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}"
            if (cur != null) {
                start = cur
                prev = cur
            }
        }
        return parts.joinToString(", ")
    }

    /**
     * The pages an export covers: [requested] filtered to valid indices (sorted, unique), or
     * every page when [requested] is null. Empty when nothing requested exists.
     */
    fun resolve(requested: List<Int>?, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        if (requested == null) return (0 until pageCount).toList()
        return requested.filter { it in 0 until pageCount }.distinct().sorted()
    }
}
