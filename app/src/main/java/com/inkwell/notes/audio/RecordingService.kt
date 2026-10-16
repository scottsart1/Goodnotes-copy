package com.inkwell.notes.audio

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Microphone foreground service that owns the recorder while a recording is active.
 * SCAFFOLD STUB — replaced by the audio implementation.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
