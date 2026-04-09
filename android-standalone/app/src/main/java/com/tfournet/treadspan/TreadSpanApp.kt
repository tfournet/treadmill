package com.tfournet.treadspan

import android.app.Application
import com.tfournet.treadspan.ble.TreadmillBleManager
import com.tfournet.treadspan.data.HealthConnectManager
import com.tfournet.treadspan.data.SessionTracker
import com.tfournet.treadspan.data.TreadmillDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TreadSpanApp : Application() {

    /** Application-wide coroutine scope. SupervisorJob so one child failure doesn't cancel others. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: TreadmillDatabase by lazy { TreadmillDatabase.getInstance(this) }

    val sessionTracker: SessionTracker by lazy { SessionTracker(db.dao()) }

    val healthConnect: HealthConnectManager by lazy { HealthConnectManager(this) }

    val bleManager: TreadmillBleManager by lazy {
        TreadmillBleManager(
            context = this,
            scope = appScope,
            onReading = { reading ->
                appScope.launch { sessionTracker.onReading(reading) }
            },
        )
    }
}
