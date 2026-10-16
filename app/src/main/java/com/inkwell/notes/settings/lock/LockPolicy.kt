package com.inkwell.notes.settings.lock

import com.inkwell.notes.core.model.PrivacyPrefs
import kotlin.math.max

/**
 * Decides when the app lock engages. Pure Kotlin (times are monotonic milliseconds, e.g.
 * SystemClock.elapsedRealtime) so the rules are unit-testable.
 *
 * - Cold start: locked whenever the lock is on.
 * - "Immediately" (lockAfterSeconds <= 0): locks as soon as the app goes to the background.
 * - Otherwise: locks on return when the app spent at least lockAfterSeconds in the background.
 * - Excursions (the app opened a system picker, share target, scanner or credential screen on
 *   top of itself) get a grace of at least [excursionGraceMs], so returning from "Import PDF"
 *   never throws away the result the user just picked.
 * - Backgrounding caused by our own authentication prompt is ignored.
 */
class LockPolicy(private val excursionGraceMs: Long = DEFAULT_EXCURSION_GRACE_MS) {
    var locked: Boolean = false
        private set

    private var backgroundedAt: Long? = null
    private var excursion: Boolean = false

    /** First time the UI starts in this process. Returns whether it is locked. */
    fun onColdStart(prefs: PrivacyPrefs): Boolean {
        locked = prefs.appLock
        backgroundedAt = null
        return locked
    }

    /**
     * The app went to the background at [nowMs]. [excursion] is true when one of our own tasks
     * shows another app's screen (picker, share sheet...). [authInProgress] is true while our
     * own BiometricPrompt / device-credential screen is up.
     */
    fun onBackground(nowMs: Long, prefs: PrivacyPrefs, excursion: Boolean, authInProgress: Boolean) {
        if (authInProgress) return
        backgroundedAt = nowMs
        this.excursion = excursion
        if (prefs.appLock && prefs.lockAfterSeconds <= 0 && !excursion) locked = true
    }

    /** The app came back to the foreground at [nowMs]. Returns whether it is now locked. */
    fun onForeground(nowMs: Long, prefs: PrivacyPrefs): Boolean {
        val since = backgroundedAt ?: return locked
        backgroundedAt = null
        if (shouldLock(prefs.appLock, prefs.lockAfterSeconds, since, nowMs, excursion, excursionGraceMs)) locked = true
        return locked
    }

    /** Explicit "Lock now". Only when the lock is on. */
    fun lockNow(prefs: PrivacyPrefs): Boolean {
        if (prefs.appLock) {
            locked = true
            backgroundedAt = null
        }
        return locked
    }

    fun onUnlocked() {
        locked = false
        backgroundedAt = null
    }

    /** Lock switched off in settings: nothing stays locked. */
    fun onLockDisabled() {
        locked = false
        backgroundedAt = null
    }

    companion object {
        const val DEFAULT_EXCURSION_GRACE_MS = 5 * 60_000L

        /** Choices offered in settings (seconds). */
        val DELAY_CHOICES = listOf(0, 60, 300, 900)

        fun shouldLock(
            enabled: Boolean,
            lockAfterSeconds: Int,
            backgroundedAtMs: Long,
            nowMs: Long,
            excursion: Boolean,
            excursionGraceMs: Long = DEFAULT_EXCURSION_GRACE_MS,
        ): Boolean {
            if (!enabled) return false
            val elapsed = nowMs - backgroundedAtMs
            // A clock that went backwards cannot be trusted: fail safe.
            if (elapsed < 0) return true
            val threshold = max(lockAfterSeconds, 0) * 1000L
            val effective = if (excursion) max(threshold, excursionGraceMs) else threshold
            return elapsed >= effective
        }
    }
}
