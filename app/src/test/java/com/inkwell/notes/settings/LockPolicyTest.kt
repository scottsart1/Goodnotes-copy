package com.inkwell.notes.settings

import com.inkwell.notes.core.model.PrivacyPrefs
import com.inkwell.notes.settings.lock.LockPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    private val on60 = PrivacyPrefs(appLock = true, lockAfterSeconds = 60)
    private val onImmediate = PrivacyPrefs(appLock = true, lockAfterSeconds = 0)
    private val off = PrivacyPrefs(appLock = false, lockAfterSeconds = 0)

    @Test
    fun coldStartLocksOnlyWhenEnabled() {
        assertTrue(LockPolicy().onColdStart(on60))
        assertFalse(LockPolicy().onColdStart(off))
    }

    @Test
    fun returningBeforeTheDelayStaysUnlocked() {
        val p = LockPolicy()
        p.onColdStart(on60)
        p.onUnlocked()
        p.onBackground(nowMs = 1_000, prefs = on60, excursion = false, authInProgress = false)
        assertFalse(p.onForeground(nowMs = 1_000 + 59_999, prefs = on60))
    }

    @Test
    fun returningAtOrAfterTheDelayLocks() {
        val p = LockPolicy()
        p.onBackground(nowMs = 1_000, prefs = on60, excursion = false, authInProgress = false)
        assertTrue(p.onForeground(nowMs = 61_000, prefs = on60))
    }

    @Test
    fun immediateLocksAsSoonAsTheAppIsBackgrounded() {
        val p = LockPolicy()
        p.onBackground(nowMs = 5_000, prefs = onImmediate, excursion = false, authInProgress = false)
        assertTrue(p.locked)
        assertTrue(p.onForeground(nowMs = 5_001, prefs = onImmediate))
    }

    @Test
    fun disabledLockNeverEngages() {
        val p = LockPolicy()
        p.onBackground(nowMs = 0, prefs = off, excursion = false, authInProgress = false)
        assertFalse(p.locked)
        assertFalse(p.onForeground(nowMs = 10 * 60_000, prefs = off))
    }

    @Test
    fun ownAuthenticationScreenDoesNotCountAsBackground() {
        val p = LockPolicy()
        p.onBackground(nowMs = 0, prefs = onImmediate, excursion = false, authInProgress = true)
        assertFalse(p.locked)
        assertFalse(p.onForeground(nowMs = 10 * 60_000, prefs = onImmediate))
    }

    @Test
    fun excursionsGetAGracePeriod() {
        val p = LockPolicy(excursionGraceMs = 300_000)
        p.onBackground(nowMs = 0, prefs = onImmediate, excursion = true, authInProgress = false)
        assertFalse("immediate lock must not fire while a picker we opened is on top", p.locked)
        assertFalse(p.onForeground(nowMs = 120_000, prefs = onImmediate))

        p.onBackground(nowMs = 200_000, prefs = onImmediate, excursion = true, authInProgress = false)
        assertTrue(p.onForeground(nowMs = 200_000 + 300_000, prefs = onImmediate))
    }

    @Test
    fun excursionGraceNeverShortensALongerDelay() {
        val fifteen = PrivacyPrefs(appLock = true, lockAfterSeconds = 900)
        assertFalse(LockPolicy.shouldLock(true, 900, 0, 600_000, excursion = true, excursionGraceMs = 300_000))
        assertTrue(LockPolicy.shouldLock(true, fifteen.lockAfterSeconds, 0, 900_000, excursion = true, excursionGraceMs = 300_000))
    }

    @Test
    fun clockGoingBackwardsFailsSafe() {
        assertTrue(LockPolicy.shouldLock(true, 900, backgroundedAtMs = 10_000, nowMs = 5_000, excursion = false))
    }

    @Test
    fun foregroundWithoutBackgroundKeepsCurrentState() {
        val p = LockPolicy()
        p.onColdStart(on60)
        assertTrue(p.onForeground(nowMs = 1, prefs = on60))
        p.onUnlocked()
        assertFalse(p.onForeground(nowMs = 2, prefs = on60))
    }

    @Test
    fun unlockClearsPendingBackgroundTime() {
        val p = LockPolicy()
        p.onBackground(nowMs = 0, prefs = on60, excursion = false, authInProgress = false)
        p.onUnlocked()
        assertFalse(p.onForeground(nowMs = 10 * 60_000, prefs = on60))
    }

    @Test
    fun lockNowOnlyWhenEnabled() {
        val p = LockPolicy()
        assertFalse(p.lockNow(off))
        assertTrue(p.lockNow(on60))
        p.onLockDisabled()
        assertFalse(p.locked)
    }

    @Test
    fun negativeDelayIsTreatedAsImmediate() {
        assertTrue(LockPolicy.shouldLock(true, -5, 0, 0, excursion = false))
    }

    @Test
    fun delayChoicesMatchTheSettingsScreen() {
        assertTrue(LockPolicy.DELAY_CHOICES == listOf(0, 60, 300, 900))
    }
}
