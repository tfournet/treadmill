package com.tfournet.treadmill.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class StepInterval(
    val id: Int,
    val session_id: Int,
    val period_start: String,
    val period_end: String,
    val step_count: Int,
)

@Serializable
data class LastReading(
    val timestamp: String? = null,
    val raw_steps: Int = 0,
    val speed: Double = 0.0,
    val distance: Double = 0.0,
    val raw_time_secs: Int = 0,
    val delta_steps: Int = 0,
)

@Serializable
data class ActiveSession(
    val id: Int,
    val started_at: String,
    val ended_at: String? = null,
)

@Serializable
data class ServerStatus(
    val ble_state: String,
    val active_session: ActiveSession? = null,
    val last_reading: LastReading? = null,
)

private val json = Json { ignoreUnknownKeys = true }

class TreadmillApi(private var baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun updateBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    fun debugUrl(): String = baseUrl

    suspend fun getStatus(): ServerStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/status").build()
        val body = client.newCall(request).execute().use { it.body!!.string() }
        json.decodeFromString(body)
    }

    suspend fun getPendingIntervals(): List<StepInterval> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/intervals/pending").build()
        val body = client.newCall(request).execute().use { it.body!!.string() }
        json.decodeFromString(body)
    }

    suspend fun getTodayIntervals(): List<StepInterval> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/intervals/today").build()
        val body = client.newCall(request).execute().use { it.body!!.string() }
        json.decodeFromString(body)
    }

    suspend fun markSynced(intervalId: Int) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/intervals/$intervalId/synced")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), "{}"))
            .build()
        client.newCall(request).execute().close()
    }
}
