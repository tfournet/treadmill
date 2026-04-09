package com.tfournet.treadmill

import android.app.Application
import com.tfournet.treadmill.data.HealthConnectManager
import com.tfournet.treadmill.data.TreadmillApi

class TreadSpanApp : Application() {
    lateinit var api: TreadmillApi
    lateinit var healthConnect: HealthConnectManager

    override fun onCreate() {
        super.onCreate()
        api = TreadmillApi("")
        healthConnect = HealthConnectManager(this)
    }
}
