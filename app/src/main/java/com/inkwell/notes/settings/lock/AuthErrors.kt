package com.inkwell.notes.settings.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

/** Whether this device can verify the user at all. */
enum class AuthAvailability {
    READY,
    /** Neither biometrics nor a screen lock (PIN, pattern, password) is set up. */
    NOT_SET_UP,
    /** Hardware temporarily unavailable (e.g. sensor busy); worth retrying. */
    TEMPORARILY_UNAVAILABLE,
    /** A security update is required before biometrics can be used. */
    UPDATE_REQUIRED,
    /** No biometric hardware and no way to use the screen lock through the prompt. */
    UNSUPPORTED,
}

/** What the UI should do after the prompt ended without success. */
enum class AuthFailureKind {
    /** The user dismissed the prompt; show the Unlock button quietly. */
    CANCELLED,
    /** Retry is possible (timeout, hardware busy, lockout with credential fallback). */
    RETRY,
    /** Nothing is enrolled anymore. */
    NOT_SET_UP,
}

data class AuthFailure(val kind: AuthFailureKind, val message: String?)

/** Maps androidx.biometric codes to user-facing outcomes. Pure, unit-testable. */
object AuthErrors {
    fun availability(canAuthenticateResult: Int): AuthAvailability = when (canAuthenticateResult) {
        BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NOT_SET_UP
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> AuthAvailability.TEMPORARILY_UNAVAILABLE
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> AuthAvailability.UPDATE_REQUIRED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AuthAvailability.UNSUPPORTED
        // UNSUPPORTED / STATUS_UNKNOWN: let the prompt itself decide.
        else -> AuthAvailability.READY
    }

    fun availabilityMessage(a: AuthAvailability): String? = when (a) {
        AuthAvailability.READY -> null
        AuthAvailability.NOT_SET_UP ->
            "This device has no screen lock or fingerprint set up, so Inkwell can't verify it's you. Add a PIN, pattern, password or fingerprint in Android Settings."
        AuthAvailability.TEMPORARILY_UNAVAILABLE -> "The fingerprint sensor isn't available right now. Try again in a moment."
        AuthAvailability.UPDATE_REQUIRED -> "Android needs a security update before biometrics can be used. Check for system updates, then try again."
        AuthAvailability.UNSUPPORTED -> "This device can't verify you with biometrics or a screen lock."
    }

    fun failure(errorCode: Int, systemMessage: CharSequence?): AuthFailure = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED -> AuthFailure(AuthFailureKind.CANCELLED, null)

        BiometricPrompt.ERROR_LOCKOUT ->
            AuthFailure(AuthFailureKind.RETRY, "Too many attempts. Wait 30 seconds, or unlock with your PIN, pattern or password.")
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
            AuthFailure(AuthFailureKind.RETRY, "Fingerprint is locked after too many attempts. Unlock with your PIN, pattern or password.")
        BiometricPrompt.ERROR_TIMEOUT -> AuthFailure(AuthFailureKind.RETRY, "The prompt timed out. Try again.")
        BiometricPrompt.ERROR_HW_UNAVAILABLE ->
            AuthFailure(AuthFailureKind.RETRY, "The fingerprint sensor isn't available right now. Try again in a moment.")
        BiometricPrompt.ERROR_HW_NOT_PRESENT ->
            AuthFailure(AuthFailureKind.RETRY, "No fingerprint hardware was found. Use your PIN, pattern or password.")
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
            AuthFailure(AuthFailureKind.NOT_SET_UP, availabilityMessage(AuthAvailability.NOT_SET_UP))
        BiometricPrompt.ERROR_NO_SPACE, BiometricPrompt.ERROR_UNABLE_TO_PROCESS, BiometricPrompt.ERROR_VENDOR ->
            AuthFailure(AuthFailureKind.RETRY, "Couldn't verify you. Try again.")
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
            AuthFailure(AuthFailureKind.RETRY, availabilityMessage(AuthAvailability.UPDATE_REQUIRED))
        else -> AuthFailure(AuthFailureKind.RETRY, systemMessage?.toString()?.takeIf { it.isNotBlank() } ?: "Couldn't verify you. Try again.")
    }
}
