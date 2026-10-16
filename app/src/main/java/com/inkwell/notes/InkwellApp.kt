package com.inkwell.notes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.inkwell.notes.app.AppGraph
import kotlinx.coroutines.launch

class InkwellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.init(this)
        createNotificationChannels()
        graph.appScope.launch {
            runCatching { graph.recording.recoverInterrupted() }
            runCatching { graph.naming.schedule() }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.recording_channel_description)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_MIN),
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_SYNC = "sync"
    }
}
