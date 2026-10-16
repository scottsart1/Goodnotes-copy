package com.inkwell.notes.settings.lock

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle

/** BiometricPrompt plumbing shared by the lock screen and the "App lock" switch. */
internal object Biometrics {
    private const val TAG = "AppLock"

    /**
     * Strong biometrics or the device screen lock. Android 10 does not support
     * STRONG | DEVICE_CREDENTIAL, so it falls back to WEAK | DEVICE_CREDENTIAL there.
     */
    fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun availability(context: Context): AuthAvailability = try {
        AuthErrors.availability(BiometricManager.from(context).canAuthenticate(authenticators()))
    } catch (e: Exception) {
        Log.w(TAG, "canAuthenticate failed: ${e.javaClass.simpleName}")
        AuthAvailability.READY
    }

    fun findActivity(context: Context): FragmentActivity? {
        var c: Context? = context
        while (c != null) {
            if (c is FragmentActivity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * Shows the system prompt. Exactly one of [onSuccess] / [onFailure] is called, on the main
     * thread. While the prompt is up the app lock ignores the background/foreground switch the
     * device-credential screen causes.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        onSuccess: () -> Unit,
        onFailure: (AuthFailure) -> Unit,
    ) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onFailure(AuthFailure(AuthFailureKind.CANCELLED, null))
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                AppLockManager.authInProgress = false
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                AppLockManager.authInProgress = false
                onFailure(AuthErrors.failure(errorCode, errString))
            }

            // A single unrecognised finger: the system prompt stays up and says so itself.
            override fun onAuthenticationFailed() = Unit
        }
        try {
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(authenticators())
                .setConfirmationRequired(false)
                .build()
            AppLockManager.authInProgress = true
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
        } catch (e: Exception) {
            AppLockManager.authInProgress = false
            Log.w(TAG, "BiometricPrompt failed to start: ${e.javaClass.simpleName}")
            onFailure(AuthFailure(AuthFailureKind.RETRY, "Couldn't show the unlock prompt. Try again."))
        }
    }

    /** Opens the screen where the user can add a screen lock or fingerprint. Returns false if none exists. */
    fun openSecuritySettings(context: Context): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, authenticators()))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // Try the next, more general screen.
            }
        }
        return false
    }
}
