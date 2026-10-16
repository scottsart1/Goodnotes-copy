package com.inkwell.notes.settings.lock

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.PrivacyPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide app lock state. Watches [ProcessLifecycleOwner] (ON_STOP / ON_START) and asks
 * [LockPolicy] whether the lock engages. Locking only hides the UI: background work such as
 * a running recording continues.
 */
internal object AppLockManager {
    private const val TAG = "AppLock"

    private val policy = LockPolicy()
    private val _locked = MutableStateFlow(false)
    private val _promptRequests = MutableStateFlow(0)
    private var installed = false
    private var graph: AppGraph? = null
    private var pendingAutoPrompt = false

    /** True while the lock screen must cover the app. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Bumped whenever the lock screen should check [consumeAutoPrompt] again. */
    val promptRequests: StateFlow<Int> = _promptRequests.asStateFlow()

    /** Set while our own BiometricPrompt is showing (its credential screen backgrounds the app). */
    @Volatile
    var authInProgress: Boolean = false

    /** Idempotent; call on the main thread before reading [locked] for the first frame. */
    @MainThread
    fun install(graph: AppGraph) {
        if (installed) return
        installed = true
        this.graph = graph
        val lockedAtStart = policy.onColdStart(prefs())
        publish()
        if (lockedAtStart) requestAutoPrompt()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> onBackground()
                    Lifecycle.Event.ON_START -> onForeground()
                    else -> Unit
                }
            },
        )
    }

    /** True once after the app locked by itself (cold start, return from background). */
    @MainThread
    fun consumeAutoPrompt(): Boolean {
        val p = pendingAutoPrompt
        pendingAutoPrompt = false
        return p
    }

    @MainThread
    fun unlock() {
        policy.onUnlocked()
        pendingAutoPrompt = false
        publish()
    }

    /** Locks right away (the "Lock now" action) without popping the prompt. No-op when the lock is off. */
    @MainThread
    fun lockNow() {
        if (!policy.lockNow(prefs())) return
        pendingAutoPrompt = false
        publish()
    }

    @MainThread
    fun onLockDisabled() {
        if (!policy.locked && !_locked.value) return
        policy.onLockDisabled()
        pendingAutoPrompt = false
        publish()
    }

    private fun onBackground() {
        val ctx = graph?.context ?: return
        policy.onBackground(SystemClock.elapsedRealtime(), prefs(), isExcursion(ctx), authInProgress)
        publish()
    }

    private fun onForeground() {
        val locked = policy.onForeground(SystemClock.elapsedRealtime(), prefs())
        publish()
        if (locked && !authInProgress) requestAutoPrompt()
    }

    private fun requestAutoPrompt() {
        pendingAutoPrompt = true
        _promptRequests.value = _promptRequests.value + 1
    }

    private fun publish() {
        _locked.value = policy.locked
    }

    private fun prefs(): PrivacyPrefs = graph?.settings?.current?.privacy ?: PrivacyPrefs()

    /**
     * True when one of our tasks currently shows another app's screen on top: a document
     * picker, share target, scanner or system credential screen we launched. The user did
     * not leave Inkwell, so the lock gives them a grace period.
     */
    private fun isExcursion(context: Context): Boolean = try {
        val am = context.getSystemService(ActivityManager::class.java)
        am?.appTasks.orEmpty().any { task ->
            val info = task.taskInfo
            val top = info?.topActivity
            val base = info?.baseActivity
            top != null && base?.packageName == context.packageName && top.packageName != context.packageName
        }
    } catch (e: Exception) {
        Log.w(TAG, "Task lookup failed: ${e.javaClass.simpleName}")
        false
    }
}
