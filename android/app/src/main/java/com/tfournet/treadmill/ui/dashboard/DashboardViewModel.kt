package com.tfournet.treadmill.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tfournet.treadmill.data.HealthConnectManager
import com.tfournet.treadmill.data.ServerStatus
import com.tfournet.treadmill.data.StepInterval
import com.tfournet.treadmill.data.TreadmillApi
import com.tfournet.treadmill.ui.components.ChartBar
import com.tfournet.treadmill.ui.components.SyncState
import com.tfournet.treadmill.ui.components.TreadmillStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.text.NumberFormat
import java.util.Locale

data class DashboardState(
    val todaySteps: Int = 0,
    val treadmillStatus: TreadmillStatus = TreadmillStatus.SEARCHING,
    val statusLabel: String = "Connecting...",
    val syncState: SyncState = SyncState.IDLE,
    val syncDetail: String = "",
    val lastSyncAgo: String = "Never",
    val pendingCount: Int = 0,
    val sessionSteps: String = "--",
    val sessionSubtitle: String = "Loading...",
    val chartBars: List<ChartBar> = emptyList(),
    val chartSummary: String = "",
    val isOffline: Boolean = false,
    val speed: Double = 0.0,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    // These will be injected from the app
    lateinit var api: TreadmillApi
    lateinit var healthConnect: HealthConnectManager

    private var allIntervals: List<StepInterval> = emptyList()
    private var lastSyncTime: Instant? = null

    fun startPolling() {
        viewModelScope.launch {
            while (true) {
                refreshStatus()
                refreshIntervals()
                delay(10_000)
            }
        }
    }

    private suspend fun refreshStatus() {
        try {
            val status = api.getStatus()
            updateFromStatus(status)
            _state.update { it.copy(isOffline = false) }
        } catch (e: Exception) {
            android.util.Log.e("TreadSpan", "refreshStatus failed: ${api.debugUrl()}", e)
            _state.update {
                it.copy(
                    isOffline = true,
                    treadmillStatus = TreadmillStatus.OFFLINE,
                    statusLabel = "Server offline",
                )
            }
        }
    }

    private fun updateFromStatus(status: ServerStatus) {
        val (treadmillStatus, label) = when {
            status.ble_state == "polling" && (status.last_reading?.speed ?: 0.0) > 0 ->
                TreadmillStatus.WALKING to "Walking — ${status.last_reading?.speed} km/h"
            status.ble_state == "polling" ->
                TreadmillStatus.CONNECTED to "Connected"
            status.ble_state in listOf("scanning", "connecting", "initing") ->
                TreadmillStatus.SEARCHING to "Searching..."
            status.ble_state in listOf("backoff", "adapter_reset") ->
                TreadmillStatus.SEARCHING to "Reconnecting..."
            else ->
                TreadmillStatus.OFFLINE to "Offline"
        }

        val sessionSteps = status.last_reading?.raw_steps?.let {
            NumberFormat.getNumberInstance(Locale.getDefault()).format(it)
        } ?: "--"

        val sessionTime = status.last_reading?.raw_time_secs?.let { secs ->
            "${secs / 60} min"
        } ?: ""

        val sessionSub = if (status.active_session != null) {
            "steps — $sessionTime"
        } else {
            "No active session"
        }

        _state.update {
            it.copy(
                treadmillStatus = treadmillStatus,
                statusLabel = label,
                sessionSteps = sessionSteps,
                sessionSubtitle = sessionSub,
                speed = status.last_reading?.speed ?: 0.0,
            )
        }
    }

    private suspend fun refreshIntervals() {
        try {
            allIntervals = api.getPendingIntervals()
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()

            // Count today's steps from pending + already-synced estimate
            val todayPendingSteps = allIntervals
                .filter { Instant.parse(it.period_start).atZone(zone).toLocalDate() == today }
                .sumOf { it.step_count }

            // Build chart bars (group by hour fraction)
            val chartBars = allIntervals
                .filter { Instant.parse(it.period_start).atZone(zone).toLocalDate() == today }
                .map { interval ->
                    val hour = Instant.parse(interval.period_start)
                        .atZone(zone).let { it.hour + it.minute / 60f }
                    ChartBar(hour, interval.step_count)
                }

            val lastSync = lastSyncTime?.let { formatTimeAgo(it) } ?: "Never"

            _state.update {
                it.copy(
                    todaySteps = todayPendingSteps,
                    pendingCount = allIntervals.size,
                    lastSyncAgo = lastSync,
                    chartBars = chartBars,
                )
            }
        } catch (_: Exception) { /* silent — status poll handles offline */ }
    }

    fun sync() {
        viewModelScope.launch {
            _state.update { it.copy(syncState = SyncState.SYNCING, syncDetail = "Fetching intervals...") }
            try {
                val pending = api.getPendingIntervals()
                if (pending.isEmpty()) {
                    _state.update { it.copy(syncState = SyncState.SUCCESS, syncDetail = "Everything is synced") }
                    delay(2000)
                    _state.update { it.copy(syncState = SyncState.IDLE) }
                    return@launch
                }

                pending.forEachIndexed { i, interval ->
                    _state.update { it.copy(syncDetail = "Writing ${i + 1} of ${pending.size} to Health Connect...") }
                    healthConnect.writeSteps(interval)
                    api.markSynced(interval.id)
                }

                lastSyncTime = Instant.now()
                _state.update {
                    it.copy(
                        syncState = SyncState.SUCCESS,
                        syncDetail = "Synced ${pending.size} intervals to Health Connect",
                        lastSyncAgo = "Just now",
                        pendingCount = 0,
                        todaySteps = it.todaySteps + pending.sumOf { p -> p.step_count },
                    )
                }
                delay(2000)
                _state.update { it.copy(syncState = SyncState.IDLE) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        syncState = SyncState.ERROR,
                        syncDetail = e.message ?: "Sync failed",
                    )
                }
            }
        }
    }

    private fun formatTimeAgo(instant: Instant): String {
        val secs = Duration.between(instant, Instant.now()).seconds
        return when {
            secs < 60 -> "Just now"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 86400 -> "${secs / 3600}h ago"
            else -> "${secs / 86400}d ago"
        }
    }
}
