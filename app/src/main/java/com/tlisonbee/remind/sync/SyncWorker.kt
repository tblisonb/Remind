package com.tlisonbee.remind.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tlisonbee.remind.app
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (applicationContext.app.syncManager.sync()) {
        SyncResult.SUCCESS, SyncResult.NOT_CONFIGURED -> Result.success()
        SyncResult.NETWORK_ERROR -> Result.retry()
        // Bad token or server error: retrying soon won't help; the error shows in Sync settings.
        SyncResult.FAILED -> Result.failure()
    }
}

/** Background sync: every 15 minutes (Android's minimum), plus shortly after each local change. */
object SyncScheduler {
    private const val PERIODIC = "sync-periodic"
    private const val SOON = "sync-soon"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Debounced: a burst of edits collapses into one sync a few seconds after the last one. */
    fun requestSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(SOON)
        }
    }
}
