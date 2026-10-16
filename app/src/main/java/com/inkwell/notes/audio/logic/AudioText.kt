package com.inkwell.notes.audio.logic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** Display strings for durations, titles and estimates. Locale-independent digits. */
object AudioText {
    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    const val RECOVERED_SUFFIX = " (recovered)"

    /** "0:07", "18:40", "1:02:03" (minutes are not zero-padded below an hour). */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Default name of the [n]th recording of a document: "Recording 3 · 14:05". */
    fun defaultTitle(n: Int, startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Recording ${n.coerceAtLeast(1)} · ${HH_MM.format(Instant.ofEpochMilli(startedAt).atZone(zone))}"

    /** Marks a crash-recovered recording; idempotent. */
    fun recoveredTitle(title: String): String =
        if (title.endsWith(RECOVERED_SUFFIX)) title else title.trimEnd() + RECOVERED_SUFFIX

    fun isRecoveredTitle(title: String): Boolean = title.endsWith(RECOVERED_SUFFIX)

    /** Whole minutes of audio for estimates, rounded up ("1 min" for anything under a minute). */
    fun minutesCeil(ms: Long): Int = ceil(ms.coerceAtLeast(0) / 60_000.0).toInt().coerceAtLeast(1)

    /** "$0.11", "< $0.01", or null when the price is unknown. */
    fun usd(v: Double?): String? = when {
        v == null || v.isNaN() -> null
        v <= 0.0 -> "$0.00"
        v < 0.01 -> "< $0.01"
        v < 100 -> "$" + String.format(Locale.US, "%.2f", v)
        else -> "$" + String.format(Locale.US, "%,.0f", v)
    }

    /** "about 1.1k tokens", "about 350 tokens". */
    fun tokens(n: Int): String = when {
        n >= 1_000_000 -> "about " + String.format(Locale.US, "%.1fM", n / 1_000_000.0) + " tokens"
        n >= 1000 -> "about " + String.format(Locale.US, "%.1fk", n / 1000.0) + " tokens"
        else -> "about ${n.coerceAtLeast(0)} tokens"
    }
}
