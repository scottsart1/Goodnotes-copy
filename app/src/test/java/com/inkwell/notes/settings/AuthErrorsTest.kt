package com.inkwell.notes.settings

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.inkwell.notes.settings.lock.AuthAvailability
import com.inkwell.notes.settings.lock.AuthErrors
import com.inkwell.notes.settings.lock.AuthFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthErrorsTest {
    @Test
    fun availability() {
        assertEquals(AuthAvailability.READY, AuthErrors.availability(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(AuthAvailability.NOT_SET_UP, AuthErrors.availability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(AuthAvailability.TEMPORARILY_UNAVAILABLE, AuthErrors.availability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(AuthAvailability.UNSUPPORTED, AuthErrors.availability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        // Unknown status: let the prompt decide rather than locking the user out.
        assertEquals(AuthAvailability.READY, AuthErrors.availability(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
        assertNull(AuthErrors.availabilityMessage(AuthAvailability.READY))
        assertNotNull(AuthErrors.availabilityMessage(AuthAvailability.NOT_SET_UP))
    }

    @Test
    fun cancellationIsQuiet() {
        for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED)) {
            val f = AuthErrors.failure(code, "x")
            assertEquals(AuthFailureKind.CANCELLED, f.kind)
            assertNull(f.message)
        }
    }

    @Test
    fun lockoutAndHardwareProblemsAllowRetry() {
        for (code in listOf(
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_TIMEOUT,
        )) {
            val f = AuthErrors.failure(code, null)
            assertEquals(AuthFailureKind.RETRY, f.kind)
            assertNotNull(f.message)
        }
    }

    @Test
    fun nothingEnrolledIsReportedAsNotSetUp() {
        assertEquals(AuthFailureKind.NOT_SET_UP, AuthErrors.failure(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, null).kind)
        assertEquals(AuthFailureKind.NOT_SET_UP, AuthErrors.failure(BiometricPrompt.ERROR_NO_BIOMETRICS, null).kind)
    }

    @Test
    fun unknownErrorsUseTheSystemMessage() {
        val f = AuthErrors.failure(999, "Sensor dirty")
        assertEquals(AuthFailureKind.RETRY, f.kind)
        assertEquals("Sensor dirty", f.message)
        assertNotNull(AuthErrors.failure(999, " ").message)
    }
}
