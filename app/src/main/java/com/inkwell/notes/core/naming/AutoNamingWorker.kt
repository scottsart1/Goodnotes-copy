package com.inkwell.notes.core.naming

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.storage.LibraryRepositoryImpl
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background naming. Runs hourly whether or not the app is open (WorkManager), renames every
 * document whose window has passed ([AutoNamingService.runDue]) and purges trash older than 30
 * days. Needs no network: without connectivity or an AI key, naming falls back to on-device
 * inference, and documents waiting for a transient AI failure are retried on the next run.
 *
 * Besides the periodic job, a one-off "precise" check is booked for the next naming deadline
 * when it falls before the next periodic run, so a note created at 10:00 is renamed close to
 * 10:00 the next day rather than up to an hour later.
 */
class AutoNamingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val precise = inputData.getBoolean(KEY_PRECISE, false)
        if (!precise) {
            try {
                (graph.library as? LibraryRepositoryImpl)?.purgeExpiredTrash()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Trash purge failed: ${e.javaClass.simpleName}")
            }
        }
        return try {
            val renamed = graph.naming.runDue()
            if (renamed > 0) Log.i(TAG, "Auto-named $renamed document(s)")
            if (!precise) (graph.naming as? AutoNamingServiceImpl)?.scheduleNextPreciseCheck()
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Auto-naming run failed: ${e.javaClass.simpleName}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "AutoNamingWorker"
        const val PERIODIC_WORK = "inkwell-autoname"
        const val PRECISE_WORK = "inkwell-autoname-next"
        private const val KEY_PRECISE = "precise"
        private const val MAX_RETRIES = 3

        private fun constraints() = Constraints.Builder().setRequiresBatteryNotLow(true).build()

        /** Hourly (15 min flex) job; KEEP so app starts never reset its schedule. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoNamingWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One-off check after [delayMs]; replaces any previously booked one. */
        fun schedulePrecise(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<AutoNamingWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(constraints())
                .setInputData(workDataOf(KEY_PRECISE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(PRECISE_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelPrecise(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PRECISE_WORK)
        }
    }
}
