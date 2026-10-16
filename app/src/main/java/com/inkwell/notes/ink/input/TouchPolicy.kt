package com.inkwell.notes.ink.input

import com.inkwell.notes.core.model.FingerAction
import com.inkwell.notes.core.model.InputPrefs

/** What finger pointers may do at a given moment. */
enum class FingerPermission {
    /** Fingers are ignored entirely (stylus near and zoom-while-writing off). */
    NONE,
    /** Only deliberate two-finger navigation (stylus near, zoom-while-writing on). */
    MULTI_ONLY,
    /** Normal finger handling. */
    ALL,
}

/** How a lone finger behaves when it is allowed. */
enum class FingerRole { NAVIGATE, INK, SELECT }

/**
 * Palm rejection and finger routing rules, kept free of Android types so they can be tested.
 */
class TouchPolicy {
    var prefs: InputPrefs = InputPrefs()
    /** Contact size (px) above which a touch is treated as a palm. */
    var palmMajorPx: Float = 160f

    /** Large contact patches are palms when rejection is on. Zero means "unknown" (not a palm). */
    fun isPalm(touchMajorPx: Float): Boolean = prefs.rejectLargeContacts && touchMajorPx > palmMajorPx

    fun fingerPermission(stylusNear: Boolean): FingerPermission = when {
        !stylusNear -> FingerPermission.ALL
        prefs.zoomWhileWriting -> FingerPermission.MULTI_ONLY
        else -> FingerPermission.NONE
    }

    fun fingerRole(): FingerRole = when {
        prefs.stylusOnlyInk -> FingerRole.NAVIGATE
        prefs.fingerAction == FingerAction.DRAW -> FingerRole.INK
        prefs.fingerAction == FingerAction.SELECT -> FingerRole.SELECT
        else -> FingerRole.NAVIGATE
    }

    /**
     * A two-finger gesture while the stylus is near must look deliberate: both fingers land
     * close together in time (palms touch down piecemeal).
     */
    fun deliberateMultiTouch(secondFingerDelayMs: Long, stylusNear: Boolean): Boolean =
        !stylusNear || secondFingerDelayMs <= DELIBERATE_WINDOW_MS

    companion object {
        const val DELIBERATE_WINDOW_MS = 250L
        /** Physical palm threshold (contact major axis). */
        const val PALM_MM = 17f
        /** A finger ink stroke is held back this long so a palm or a second finger can cancel it. */
        const val FINGER_INK_DELAY_MS = 90L
        /** Fingers stay blocked this long after the stylus leaves hover range. */
        const val STYLUS_LINGER_MS = 350L
    }
}

/**
 * Detects two- and three-finger taps: all fingers down and up within [tapTimeoutMs] without
 * moving more than [slopPx]. Pure Kotlin.
 */
class MultiFingerTapTracker(var tapTimeoutMs: Long = 320L, var slopPx: Float = 24f) {
    private var startMs = -1L
    private var maxPointers = 0
    private var valid = false

    val active: Boolean get() = startMs >= 0

    fun begin(timeMs: Long) {
        startMs = timeMs; maxPointers = 1; valid = true
    }

    fun pointerCount(count: Int) {
        if (count > maxPointers) maxPointers = count
        if (count > 3) valid = false
    }

    fun moved(distancePx: Float) {
        if (distancePx > slopPx) valid = false
    }

    fun invalidate() { valid = false }

    /** Call when the last finger lifts; returns 2 or 3 for a tap, otherwise 0. */
    fun end(timeMs: Long): Int {
        val ok = valid && startMs >= 0 && timeMs - startMs <= tapTimeoutMs && maxPointers in 2..3
        val n = maxPointers
        startMs = -1; maxPointers = 0; valid = false
        return if (ok) n else 0
    }
}
