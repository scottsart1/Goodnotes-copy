package com.inkwell.notes.audio

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.recorder.RecordingEngine
import com.inkwell.notes.audio.recorder.RecordingNotifications
import com.inkwell.notes.audio.recorder.RecordingRuntime
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Microphone foreground service that keeps a recording alive across page changes, panels
 * and the app going to the background. It enters the foreground immediately on every start
 * request, then hands the start to the process-wide [RecordingEngine]; the engine owns the
 * recorder. The notification mirrors [RecordingRuntime.state] and offers Pause/Resume and
 * Stop. The service leaves the foreground and stops as soon as the engine is idle.
 */
class RecordingService : LifecycleService() {
    private val graph: AppGraph by lazy { AppGraph.get(applicationContext) }
    private val engine: RecordingEngine by lazy { RecordingRuntime.engine(graph) }
    private val notifications by lazy { RecordingNotifications(this) }
    private val main = Handler(Looper.getMainLooper())
    private var inForeground = false
    private var lastStartId = 0

    private val hooks = RecordingEngine.ServiceHooks { main.post { stopIfIdle() } }

    override fun onCreate() {
        super.onCreate()
        engine.attach(hooks)
        lifecycleScope.launch {
            RecordingRuntime.state
                .map { s ->
                    (s as? RecordingState.Active)?.let {
                        RecordingNotifications.Content(RecordingRuntime.session?.docTitle.orEmpty(), it.elapsedMs / 1000, it.paused)
                    }
                }
                .distinctUntilChanged()
                .collect { content -> if (content != null && inForeground) notifications.update(content) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                // Started with startForegroundService: enter the foreground before anything else.
                val failure = enterForeground()
                val docId = intent.getStringExtra(EXTRA_DOC_ID)
                when {
                    failure != null -> {
                        engine.refuseStart(failure)
                        stopIfIdle()
                    }
                    docId.isNullOrEmpty() -> stopIfIdle()
                    else -> engine.submitStart(docId, intent.getStringExtra(EXTRA_TITLE))
                }
            }
            ACTION_PAUSE -> engine.pause()
            ACTION_RESUME -> engine.resume()
            ACTION_STOP -> engine.stop()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    /** startForeground with the microphone type; returns a user-facing reason on failure. */
    private fun enterForeground(): String? {
        val active = RecordingRuntime.state.value as? RecordingState.Active
        val content = active?.let { RecordingNotifications.Content(RecordingRuntime.session?.docTitle.orEmpty(), it.elapsedMs / 1000, it.paused) }
        return try {
            ServiceCompat.startForeground(
                this,
                RecordingNotifications.NOTIFICATION_ID,
                notifications.build(content),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            inForeground = true
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone foreground service refused", e)
            getString(R.string.audio_error_permission)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException and friends: the app is not in the foreground.
            Log.w(TAG, "Foreground service not allowed", e)
            getString(R.string.audio_error_background)
        }
    }

    private fun stopIfIdle() {
        if (!engine.isIdle) return
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        // Only stops when no newer start request has arrived in the meantime.
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        engine.detach(hooks)
        main.removeCallbacksAndMessages(null)
        // Destroyed while recording (system kill of the service): finalise what was captured.
        if (engine.hasSession) engine.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.inkwell.notes.audio.START"
        const val ACTION_PAUSE = "com.inkwell.notes.audio.PAUSE"
        const val ACTION_RESUME = "com.inkwell.notes.audio.RESUME"
        const val ACTION_STOP = "com.inkwell.notes.audio.STOP"
        private const val EXTRA_DOC_ID = "docId"
        private const val EXTRA_TITLE = "title"

        fun startIntent(context: Context, docId: String, title: String?): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DOC_ID, docId)
                .apply { if (title != null) putExtra(EXTRA_TITLE, title) }
    }
}
