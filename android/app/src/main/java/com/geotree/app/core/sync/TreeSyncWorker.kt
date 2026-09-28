package com.geotree.app.core.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.geotree.app.appContainer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TreeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = applicationContext.appContainer.syncEngine.sync()
        Log.i(TAG, "Sync attempt ${runAttemptCount + 1}: $outcome")
        return when (outcome) {
            is SyncOutcome.Completed -> Result.success()
            SyncOutcome.NotSignedIn, SyncOutcome.AuthExpired -> Result.failure()
            is SyncOutcome.BackendUnavailable, is SyncOutcome.Interrupted ->
                // Bounded: after MAX_ATTEMPTS the periodic job, app start or "Sync now" picks it up.
                if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "GeoTreeSync"
        const val MAX_ATTEMPTS = 6
    }
}

class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueue one sync. [replace] restarts immediately (manual "Sync now"). */
    fun requestSync(replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<TreeSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TreeSyncWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(
            ONE_TIME_WORK,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Periodic pull/push so other devices' trees arrive without user action. */
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<TreeSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(TreeSyncWorker.TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME_WORK)
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }

    /** True while a one-time sync is queued waiting for network or backoff. */
    fun observeQueued(): Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(ONE_TIME_WORK)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }

    private companion object {
        const val ONE_TIME_WORK = "geo_tree_sync"
        const val PERIODIC_WORK = "geo_tree_periodic_sync"
    }
}
