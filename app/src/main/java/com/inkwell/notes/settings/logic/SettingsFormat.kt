package com.inkwell.notes.settings.logic

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Plain-text formatting used across the settings screens. */
object SettingsFormat {
    /** Decimal units, like Android's own storage screens: "0 B", "820 KB", "1.4 GB". */
    fun bytes(value: Long): String {
        if (value < 0) return "0 B"
        if (value < 1000) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = value / 1000.0
        var i = 0
        while (v >= 1000.0 && i < units.lastIndex) {
            v /= 1000.0
            i++
        }
        return if (v >= 100) "${v.roundToLong()} ${units[i]}"
        else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** Approximate megabytes per hour of audio at [bitrate] bits per second. */
    fun megabytesPerHour(bitrate: Int): Int = (bitrate.coerceAtLeast(0).toLong() / 8 * 3600 / 1_000_000.0).roundToInt()

    fun audioQualityLabel(bitrate: Int): String = "${bitrate / 1000} kbps · about ${megabytesPerHour(bitrate)} MB per hour"

    fun lockDelayLabel(seconds: Int): String = when {
        seconds <= 0 -> "Immediately"
        seconds < 60 -> "After $seconds seconds"
        seconds == 60 -> "After 1 minute"
        seconds % 60 == 0 -> "After ${seconds / 60} minutes"
        else -> "After ${seconds / 60} min ${seconds % 60} s"
    }

    fun minutesLabel(seconds: Int): String {
        val m = seconds / 60
        return if (m == 1) "1 min" else "$m min"
    }

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"
}
