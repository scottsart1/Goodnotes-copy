package com.inkwell.notes.export.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inkwell.notes.app.AppGraph
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Where automatic backups go, kept in a private preferences file of the export feature. */
internal class BackupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        set(value) { prefs.edit().putString(KEY_TREE, value?.toString()).apply() }

    var intervalDays: Int
        get() = prefs.getInt(KEY_DAYS, 1)
        set(value) { prefs.edit().putInt(KEY_DAYS, value).apply() }

    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_OK, 0)
        set(value) { prefs.edit().putLong(KEY_LAST_OK, value).apply() }

    var lastFailed: Boolean
        get() = prefs.getBoolean(KEY_LAST_FAILED, false)
        set(value) { prefs.edit().putBoolean(KEY_LAST_FAILED, value).apply() }

    companion object {
        private const val FILE = "inkwell_export_backup"
        private const val KEY_TREE = "auto_tree_uri"
        private const val KEY_DAYS = "auto_interval_days"
        private const val KEY_LAST_OK = "auto_last_success"
        private const val KEY_LAST_FAILED = "auto_last_failed"
    }
}

/** Schedules and runs automatic backups into a user-chosen folder. */
internal object AutoBackup {
    const val WORK_NAME = "inkwell-auto-backup"
    const val KEEP = 5

    fun schedule(context: Context, treeUri: Uri?, intervalDays: Int) {
        val prefs = BackupPrefs(context)
        val wm = WorkManager.getInstance(context)
        if (treeUri == null) {
            prefs.treeUri = null
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        // Keep access to the folder across restarts (no-op when the grant is already persisted).
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Folder grant could not be persisted")
        }
        val days = intervalDays.coerceIn(1, 90)
        prefs.treeUri = treeUri
        prefs.intervalDays = days
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(days.toLong(), TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(WORK_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Deletes all but the [KEEP] newest backups in [tree]. Other files are never touched. */
    fun prune(context: Context, tree: Uri) {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return
        val files = dir.listFiles().filter { it.isFile }
        for (f in BackupNaming.toDelete(files, { it.name.orEmpty() }, { it.lastModified() }, KEEP)) {
            runCatching { f.delete() }
        }
    }

    private const val TAG = "AutoBackup"
}

/** Periodic backup job (charging, battery not low). Backups are never touched by sync or deletions. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = BackupPrefs(applicationContext)
        val tree = prefs.treeUri ?: return Result.success()
        val dir = DocumentFile.fromTreeUri(applicationContext, tree)
        if (dir == null || !dir.exists() || !dir.canWrite()) {
            prefs.lastFailed = true
            return Result.failure()
        }
        return try {
            val graph = AppGraph.get(applicationContext)
            graph.backup.backupTo(tree, includeAudio = true)
            AutoBackup.prune(applicationContext, tree)
            prefs.lastSuccessAt = System.currentTimeMillis()
            prefs.lastFailed = false
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w("AutoBackup", "Automatic backup failed (${t.javaClass.simpleName})")
            prefs.lastFailed = true
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
