package com.tfournet.treadspan.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

private const val TAG = "WatchSync"
private const val PATH_STEPS = "/treadspan/steps"

/**
 * Pushes step data to the Wear OS companion via Data Layer API.
 * setUrgent() delivers within seconds over Bluetooth.
 */
object WatchSync {

    suspend fun pushSteps(
        context: Context,
        todaySteps: Int,
        sessionSteps: Int,
        speed: Double,
        isWalking: Boolean,
    ) {
        try {
            val request = PutDataMapRequest.create(PATH_STEPS).apply {
                dataMap.putInt("today_steps", todaySteps)
                dataMap.putInt("session_steps", sessionSteps)
                dataMap.putDouble("speed", speed)
                dataMap.putBoolean("is_walking", isWalking)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "Pushed to watch: today=$todaySteps session=$sessionSteps walking=$isWalking")
        } catch (e: Exception) {
            // Watch not connected or Wear OS not available — silently ignore
            Log.d(TAG, "Watch push skipped: ${e.message}")
        }
    }
}
