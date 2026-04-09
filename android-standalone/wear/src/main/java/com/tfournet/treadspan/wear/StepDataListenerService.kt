package com.tfournet.treadspan.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

private const val TAG = "StepDataListener"
const val PATH_STEPS = "/treadspan/steps"
const val KEY_TODAY_STEPS = "today_steps"
const val KEY_SESSION_STEPS = "session_steps"
const val KEY_SPEED = "speed"
const val KEY_IS_WALKING = "is_walking"
const val KEY_TIMESTAMP = "timestamp"

/**
 * Receives step data pushed from the phone via Data Layer API.
 * Updates a shared preference that the watch UI observes.
 */
class StepDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.dataItem.uri.path == PATH_STEPS) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val todaySteps = dataMap.getInt(KEY_TODAY_STEPS, 0)
                val sessionSteps = dataMap.getInt(KEY_SESSION_STEPS, 0)
                val speed = dataMap.getDouble(KEY_SPEED, 0.0)
                val isWalking = dataMap.getBoolean(KEY_IS_WALKING, false)

                Log.i(TAG, "Received: today=$todaySteps session=$sessionSteps speed=$speed walking=$isWalking")

                // Store in SharedPreferences for the watch UI to read
                getSharedPreferences("treadspan_watch", MODE_PRIVATE).edit()
                    .putInt(KEY_TODAY_STEPS, todaySteps)
                    .putInt(KEY_SESSION_STEPS, sessionSteps)
                    .putFloat(KEY_SPEED, speed.toFloat())
                    .putBoolean(KEY_IS_WALKING, isWalking)
                    .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                    .apply()

                // Notify the UI if it's open
                sendBroadcast(Intent("com.tfournet.treadspan.STEPS_UPDATED"))
            }
        }
    }
}
