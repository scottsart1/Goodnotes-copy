package com.inkwell.notes.core.naming

import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.AutoNameStatus.DISABLED
import com.inkwell.notes.core.model.AutoNameStatus.DONE
import com.inkwell.notes.core.model.AutoNameStatus.IMPORTED
import com.inkwell.notes.core.model.AutoNameStatus.INSUFFICIENT
import com.inkwell.notes.core.model.AutoNameStatus.MANUAL
import com.inkwell.notes.core.model.AutoNameStatus.PENDING
import com.inkwell.notes.core.model.AutoNameStatus.QUEUED
import com.inkwell.notes.core.model.NamingPrefs
import com.inkwell.notes.core.naming.AutoNamingRules.HOUR_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNamingRulesTest {
    private val created = 1_760_000_000_000L
    private val prefs = NamingPrefs()

    private fun eligible(
        status: AutoNameStatus,
        hoursAfterCreation: Double,
        attempts: Int = 0,
        lastCheckHoursAgo: Double? = null,
        prefs: NamingPrefs = this.prefs,
    ): Boolean {
        val now = created + (hoursAfterCreation * HOUR_MS).toLong()
        val lastCheck = lastCheckHoursAgo?.let { now - (it * HOUR_MS).toLong() }
        return AutoNamingRules.isEligible(status, created, attempts, lastCheck, now, prefs)
    }

    @Test fun nothingIsEligibleBeforeTheWindowCloses() {
        for (status in AutoNameStatus.entries) {
            assertFalse("$status at 23h59", eligible(status, 23.99))
        }
    }

    @Test fun pendingAndQueuedAreEligibleOnceTheWindowCloses() {
        assertTrue(eligible(PENDING, 24.0))
        assertTrue(eligible(PENDING, 24 * 30.0))
        assertTrue(eligible(QUEUED, 25.0))
        // QUEUED retries regardless of attempts or recent checks.
        assertTrue(eligible(QUEUED, 25.0, attempts = 5, lastCheckHoursAgo = 0.5))
    }

    @Test fun userNamedImportedDisabledAndDoneAreNeverRenamedAutomatically() {
        for (status in listOf(MANUAL, IMPORTED, DISABLED, DONE)) {
            assertFalse("$status", eligible(status, 24.0 * 365))
            assertFalse("$status", eligible(status, 48.0, attempts = 0, lastCheckHoursAgo = null))
        }
    }

    @Test fun insufficientIsRecheckedAtMostEveryTwelveHours() {
        assertTrue(eligible(INSUFFICIENT, 40.0, attempts = 1, lastCheckHoursAgo = 12.0))
        assertTrue(eligible(INSUFFICIENT, 40.0, attempts = 1, lastCheckHoursAgo = 13.0))
        assertFalse(eligible(INSUFFICIENT, 40.0, attempts = 1, lastCheckHoursAgo = 11.9))
        assertFalse(eligible(INSUFFICIENT, 40.0, attempts = 1, lastCheckHoursAgo = 0.0))
        // Unknown last check (state lost) is safe to re-check.
        assertTrue(eligible(INSUFFICIENT, 40.0, attempts = 2, lastCheckHoursAgo = null))
        // A last check in the future (clock moved back) does not block forever.
        assertTrue(eligible(INSUFFICIENT, 40.0, attempts = 2, lastCheckHoursAgo = -5.0))
    }

    @Test fun insufficientStopsAfterSixAttempts() {
        assertTrue(eligible(INSUFFICIENT, 200.0, attempts = 5, lastCheckHoursAgo = 24.0))
        assertFalse(eligible(INSUFFICIENT, 200.0, attempts = 6, lastCheckHoursAgo = 24.0))
        assertFalse(eligible(INSUFFICIENT, 200.0, attempts = 9, lastCheckHoursAgo = 100.0))
    }

    @Test fun disabledSettingStopsEverything() {
        val off = NamingPrefs(autoNameEnabled = false)
        for (status in AutoNameStatus.entries) {
            assertFalse("$status", eligible(status, 24.0 * 10, prefs = off))
        }
        assertNull(AutoNamingRules.nextCheckAt(PENDING, created, 0, null, off))
    }

    @Test fun delayFollowsSettings() {
        val short = NamingPrefs(delayHours = 2)
        assertFalse(eligible(PENDING, 1.9, prefs = short))
        assertTrue(eligible(PENDING, 2.0, prefs = short))
        val immediate = NamingPrefs(delayHours = 0)
        assertTrue(eligible(PENDING, 0.0, prefs = immediate))
        // A negative delay from corrupt settings behaves like 0, never like "before creation".
        assertEquals(created, AutoNamingRules.windowEndsAt(created, NamingPrefs(delayHours = -5)))
    }

    @Test fun nextCheckTimes() {
        val windowEnd = created + 24 * HOUR_MS
        assertEquals(windowEnd, AutoNamingRules.nextCheckAt(PENDING, created, 0, null, prefs))
        val lastCheck = windowEnd + 3 * HOUR_MS
        assertEquals(lastCheck + HOUR_MS, AutoNamingRules.nextCheckAt(QUEUED, created, 0, lastCheck, prefs))
        assertEquals(lastCheck + 12 * HOUR_MS, AutoNamingRules.nextCheckAt(INSUFFICIENT, created, 2, lastCheck, prefs))
        assertNull(AutoNamingRules.nextCheckAt(INSUFFICIENT, created, 6, lastCheck, prefs))
        for (status in listOf(MANUAL, IMPORTED, DISABLED, DONE)) {
            assertNull(AutoNamingRules.nextCheckAt(status, created, 0, null, prefs))
        }
    }

    @Test fun stateAfterAnUnsuccessfulAnalysis() {
        assertEquals(
            AutoNamingRules.Update(INSUFFICIENT, attempts = 3, aiFailures = 1),
            AutoNamingRules.afterUnsuccessful(TopicOutcome.Insufficient, attempts = 2, aiFailures = 1),
        )
        assertEquals(
            AutoNamingRules.Update(QUEUED, attempts = 2, aiFailures = 2),
            AutoNamingRules.afterUnsuccessful(TopicOutcome.RetryLater, attempts = 2, aiFailures = 1),
        )
        assertNull(AutoNamingRules.afterUnsuccessful(TopicOutcome.Found("Physics", TopicOutcome.Source.LOCAL), 0, 0))
    }
}
