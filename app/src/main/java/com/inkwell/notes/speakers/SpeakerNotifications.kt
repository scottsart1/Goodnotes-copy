package com.inkwell.notes.speakers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.inkwell.notes.MainActivity
import com.inkwell.notes.R
import com.inkwell.notes.speakers.logic.NamingNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The quiet "3 voices in 20261016_Standup - tap to name them" notification after a
 * background identification. Posted only while the app is in the background and allowed to
 * notify; never names a person, and with the app lock on not even the document. Tapping it
 * opens the document.
 */
internal class SpeakerNotifications(private val context: Context) {

    suspend fun inForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun post(docId: String, recordingId: String, text: NamingNotice.Text) {
        ensureChannel()
        val title = when (text) {
            is NamingNotice.Text.Counted -> context.resources.getQuantityString(R.plurals.speaker_engine_notif_counted, text.count, text.count, text.docTitle)
            NamingNotice.Text.Generic -> context.getString(R.string.speaker_engine_notif_generic)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentIntent(openDocument(docId, recordingId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // The title can hold a document name: keep it off the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_record)
                    .setContentTitle(context.getString(R.string.speaker_engine_notif_generic))
                    .build(),
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, idFor(recordingId), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: nothing to show.
        }
    }

    fun cancel(recordingId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(TAG, idFor(recordingId)) }
    }

    private fun openDocument(docId: String, recordingId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_DOCUMENT)
            .putExtra(MainActivity.EXTRA_DOC_ID, docId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, idFor(recordingId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.speaker_engine_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.speaker_engine_channel_description)
                setShowBadge(true)
            },
        )
    }

    private fun idFor(recordingId: String): Int = recordingId.hashCode() and 0x7FFF_FFFF

    companion object {
        const val CHANNEL = "speakers"
        private const val TAG = "speakers"
    }
}
