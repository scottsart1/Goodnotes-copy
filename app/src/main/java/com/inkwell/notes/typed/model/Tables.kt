package com.inkwell.notes.typed.model

/**
 * Table editing on [com.inkwell.notes.core.model.TypedBlock.rows]. The first row is the
 * header. Every operation returns a rectangular grid with at least one row and one column.
 */
object TableOps {
    const val MAX_ROWS = 200
    const val MAX_COLUMNS = 20

    fun newTable(rows: Int = 3, columns: Int = 3): List<List<String>> =
        List(rows.coerceIn(1, MAX_ROWS)) { List(columns.coerceIn(1, MAX_COLUMNS)) { "" } }

    fun columnCount(rows: List<List<String>>): Int = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1

    /** Pads short rows, and turns an empty grid into a single empty cell. */
    fun normalize(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return listOf(listOf(""))
        val cols = columnCount(rows).coerceAtMost(MAX_COLUMNS)
        return rows.take(MAX_ROWS).map { r -> List(cols) { c -> r.getOrNull(c) ?: "" } }
    }

    fun cell(rows: List<List<String>>, row: Int, column: Int): String = rows.getOrNull(row)?.getOrNull(column) ?: ""

    fun setCell(rows: List<List<String>>, row: Int, column: Int, text: String): List<List<String>> {
        val grid = normalize(rows)
        if (row !in grid.indices || column !in grid[0].indices) return grid
        return grid.mapIndexed { r, cells -> if (r == row) cells.mapIndexed { c, v -> if (c == column) text else v } else cells }
    }

    /** Inserts an empty row so it ends up at index [at] (clamped; the header row can be pushed down only at 0). */
    fun insertRow(rows: List<List<String>>, at: Int): List<List<String>> {
        val grid = normalize(rows)
        if (grid.size >= MAX_ROWS) return grid
        val idx = at.coerceIn(0, grid.size)
        val empty = List(grid[0].size) { "" }
        return grid.toMutableList().apply { add(idx, empty) }
    }

    fun removeRow(rows: List<List<String>>, row: Int): List<List<String>> {
        val grid = normalize(rows)
        if (grid.size <= 1 || row !in grid.indices) return grid
        return grid.filterIndexed { r, _ -> r != row }
    }

    fun insertColumn(rows: List<List<String>>, at: Int): List<List<String>> {
        val grid = normalize(rows)
        if (grid[0].size >= MAX_COLUMNS) return grid
        val idx = at.coerceIn(0, grid[0].size)
        return grid.map { it.toMutableList().apply { add(idx, "") } }
    }

    fun removeColumn(rows: List<List<String>>, column: Int): List<List<String>> {
        val grid = normalize(rows)
        if (grid[0].size <= 1 || column !in grid[0].indices) return grid
        return grid.map { cells -> cells.filterIndexed { c, _ -> c != column } }
    }

    fun moveRow(rows: List<List<String>>, from: Int, to: Int): List<List<String>> {
        val grid = normalize(rows)
        if (from !in grid.indices || to !in grid.indices || from == to) return grid
        return grid.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Plain text: cells separated by " | ", rows by newlines. Empty rows are skipped. */
    fun toPlainText(rows: List<List<String>>): String =
        normalize(rows).filter { r -> r.any { it.isNotBlank() } }.joinToString("\n") { r -> r.joinToString(" | ") { it.trim() } }

    /** GitHub-flavoured Markdown table with the first row as header. */
    fun toMarkdown(rows: List<List<String>>): String {
        val grid = normalize(rows)
        fun esc(s: String) = s.replace("|", "\\|").replace("\n", " ")
        val sb = StringBuilder()
        sb.append("| ").append(grid[0].joinToString(" | ") { esc(it) }).append(" |\n")
        sb.append("|").append(grid[0].joinToString("|") { " --- " }).append("|\n")
        for (r in grid.drop(1)) sb.append("| ").append(r.joinToString(" | ") { esc(it) }).append(" |\n")
        return sb.toString().trimEnd()
    }
}
