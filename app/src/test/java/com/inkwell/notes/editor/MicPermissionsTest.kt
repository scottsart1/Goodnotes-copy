package com.inkwell.notes.editor

import com.inkwell.notes.editor.logic.MicPermissions
import com.inkwell.notes.editor.logic.MicRequestOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class MicPermissionsTest {
    private val mic = MicPermissions.RECORD_AUDIO
    private val notif = MicPermissions.POST_NOTIFICATIONS

    @Test fun `a cancelled prompt is neither a denial nor a permanent one`() {
        // Before any denial the system reports "no rationale needed": that must not read as "permanently denied".
        assertEquals(MicRequestOutcome.Cancelled, MicPermissions.outcome(emptyMap(), micGrantedNow = false, canShowRationale = false))
        // ...unless the permission is in fact held (e.g. granted from Settings meanwhile).
        assertEquals(MicRequestOutcome.Granted, MicPermissions.outcome(emptyMap(), micGrantedNow = true, canShowRationale = false))
    }

    @Test fun `the microphone grant counts even when notifications are declined`() {
        assertEquals(MicRequestOutcome.Granted, MicPermissions.outcome(mapOf(mic to true, notif to false), micGrantedNow = true, canShowRationale = false))
        assertEquals(MicRequestOutcome.Granted, MicPermissions.outcome(mapOf(mic to true), micGrantedNow = false, canShowRationale = true))
    }

    @Test fun `a result without the microphone key falls back to the live check`() {
        assertEquals(MicRequestOutcome.Granted, MicPermissions.outcome(mapOf(notif to true), micGrantedNow = true, canShowRationale = false))
        assertEquals(MicRequestOutcome.Denied(permanently = true), MicPermissions.outcome(mapOf(notif to true), micGrantedNow = false, canShowRationale = false))
    }

    @Test fun `first denial can be retried and a second one points at settings`() {
        assertEquals(MicRequestOutcome.Denied(permanently = false), MicPermissions.outcome(mapOf(mic to false), micGrantedNow = false, canShowRationale = true))
        assertEquals(MicRequestOutcome.Denied(permanently = true), MicPermissions.outcome(mapOf(mic to false), micGrantedNow = false, canShowRationale = false))
    }

    @Test fun `notifications are only requested on Android 13 and later and only when missing`() {
        assertEquals(listOf(mic), MicPermissions.toRequest(sdkInt = 32, hasNotifications = false))
        assertEquals(listOf(mic, notif), MicPermissions.toRequest(sdkInt = 33, hasNotifications = false))
        assertEquals(listOf(mic), MicPermissions.toRequest(sdkInt = 35, hasNotifications = true))
    }
}
