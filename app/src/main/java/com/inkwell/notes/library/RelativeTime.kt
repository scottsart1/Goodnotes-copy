package com.inkwell.notes.library

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Compact relative times for library meta lines: "just now", "5 min ago", "2h ago",
 * "yesterday", "3 days ago", "Oct 12", "Oct 12, 2025". Lower-case first letter so it reads
 * inside a sentence ("Edited 2h ago"); use [capitalized] when it stands alone.
 */
object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Up to this many hours ago is shown in hours even across midnight ("3h ago" at 1am). */
    private const val HOURS_ACROSS_MIDNIGHT = 6

    fun phrase(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val diff = nowMs - thenMs
        // Small clock skew (or a timestamp from a moment ago on another device) reads as now.
        if (diff < MINUTE) {
            return if (diff > -HOUR) "just now" else absolute(thenMs, nowMs, zone, locale)
        }
        if (diff < HOUR) return "${diff / MINUTE} min ago"
        val then = Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then, now)
        val hours = diff / HOUR
        if (days == 0L || (hours < HOURS_ACROSS_MIDNIGHT && days == 1L)) return "${hours}h ago"
        if (days == 1L) return "yesterday"
        if (days in 2..6) return "$days days ago"
        return absolute(thenMs, nowMs, zone, locale)
    }

    /** "Oct 12" this year, "Oct 12, 2025" otherwise. */
    fun absolute(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(thenMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val pattern = if (then.year == now.year) "MMM d" else "MMM d, yyyy"
        return DateTimeFormatter.ofPattern(pattern, locale).format(then)
    }

    fun capitalized(text: String): String = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
