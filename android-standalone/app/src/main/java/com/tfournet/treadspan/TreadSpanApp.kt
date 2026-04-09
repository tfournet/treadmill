package com.tfournet.treadspan

import android.app.Application
import com.tfournet.treadspan.ble.TreadmillBleManager
import android.util.Log
import com.tfournet.treadspan.data.HealthConnectManager
import com.tfournet.treadspan.data.SessionTracker
import com.tfournet.treadspan.data.TreadmillDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "TreadSpanApp"

class TreadSpanApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: TreadmillDatabase by lazy { TreadmillDatabase.getInstance(this) }

    val healthConnect: HealthConnectManager by lazy { HealthConnectManager(this) }

    val sessionTracker: SessionTracker by lazy {
        SessionTracker(
            dao = db.dao(),
            onWalkingStopped = { syncPendingToHealthConnect() },
        )
    }

    val bleManager: TreadmillBleManager by lazy {
        TreadmillBleManager(
            context = this,
            scope = appScope,
            onReading = { reading ->
                appScope.launch { sessionTracker.onReading(reading) }
            },
        )
    }

    private suspend fun syncPendingToHealthConnect() {
        try {
            val pending = db.dao().getPendingIntervals()
            if (pending.isEmpty()) return
            Log.i(TAG, "Walking stopped — syncing ${pending.size} intervals to Health Connect")
            for (interval in pending) {
                healthConnect.writeSteps(interval)
                db.dao().markSynced(interval.id, Instant.now().toString())
            }
            Log.i(TAG, "Sync complete: ${pending.size} intervals written")
        } catch (e: Exception) {
            Log.w(TAG, "Auto-sync failed: ${e.message}")
        }
    }
}
