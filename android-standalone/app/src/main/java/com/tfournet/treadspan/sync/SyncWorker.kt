package com.tfournet.treadspan.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tfournet.treadspan.data.HealthConnectManager
import com.tfournet.treadspan.data.TreadmillDatabase
import java.time.Instant

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = TreadmillDatabase.getInstance(applicationContext).dao()
        val hcm = HealthConnectManager(applicationContext)

        if (!hcm.hasPermissions()) return Result.failure()

        val pending = dao.getPendingIntervals()
        if (pending.isEmpty()) return Result.success()

        var failureCount = 0
        for (interval in pending) {
            try {
                hcm.writeSteps(interval)
                dao.markSynced(interval.id, Instant.now().toString())
            } catch (e: Exception) {
                failureCount++
            }
        }

        return if (failureCount == 0) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "treadspan_sync"
    }
}
