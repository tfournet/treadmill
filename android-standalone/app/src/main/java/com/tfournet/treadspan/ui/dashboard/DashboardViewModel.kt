package com.tfournet.treadspan.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tfournet.treadspan.ble.TreadmillReading
import com.tfournet.treadspan.data.HealthConnectManager
import com.tfournet.treadspan.data.TreadmillDatabase
import com.tfournet.treadspan.ui.components.ChartBar
import com.tfournet.treadspan.ui.components.SyncState
import com.tfournet.treadspan.ui.components.TreadmillStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// ─── BLE connection state (produced by TreadmillBleManager) ──────────────────

enum class BleConnectionState { SCANNING, CONNECTING, CONNECTED, DISCONNECTED }

// ─── Dashboard UI state ───────────────────────────────────────────────────────

data class DashboardState(
    val todaySteps: Int = 0,
    val todayFlushedSteps: Int = 0,
    val treadmillStatus: TreadmillStatus = TreadmillStatus.SEARCHING,
    val statusLabel: String = "Searching...",
    val syncState: SyncState = SyncState.IDLE,
    val syncDetail: String = "",
    val lastSyncAgo: String = "Never",
    val pendingCount: Int = 0,
    // This Session card
    val sessionTrackedSteps: String = "--",
    val sessionTrackedLabel: String = "No active session",
    // Treadmill card
    val treadmillTotal: String = "--",
    val treadmillStartedAt: String = "",
    val chartBars: List<ChartBar> = emptyList(),
    val chartSummary: String = "",
    val isOffline: Boolean = false,
    val speed: Double = 0.0,
    // Raw values for computing session tracked steps
    val baselineSteps: Int? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val dao = TreadmillDatabase.getInstance(app).dao()
    private val healthConnect = HealthConnectManager(app)
    private var lastSyncTime: Instant? = null

    /** Set by the Activity once TreadmillBleManager is available. */
    var bleState: StateFlow<BleConnectionState>? = null
    var bleLatestReading: StateFlow<TreadmillReading?>? = null

    /**
     * Begin observing Room flows and BLE state. Call from the Activity after
     * injecting bleState and bleLatestReading.
     */
    fun start() {
        observeIntervals()
        observeActiveSession()
        observeBleState()
        observeBleReadings()
    }

    // ─── Room observers ───────────────────────────────────────────────────────

    private fun observeIntervals() {
        viewModelScope.launch {
            // Use UTC date to match Instant-based periodStart timestamps stored as UTC.
            val today = LocalDate.now(ZoneId.of("UTC")).toString()
            val zone = ZoneId.systemDefault()

            dao.getTodayIntervalsFlow(today).collectLatest { intervals ->
                val flushedSteps = intervals.sumOf { it.stepCount }
                val chartBars = intervals.map { interval ->
                    val hour = Instant.parse(interval.periodStart)
                        .atZone(zone)
                        .let { it.hour + it.minute / 60f }
                    ChartBar(hour, interval.stepCount)
                }
                val pendingCount = intervals.count { it.synced == 0 }
                _state.update {
                    it.copy(
                        todayFlushedSteps = flushedSteps,
                        chartBars = chartBars,
                        pendingCount = pendingCount,
                        lastSyncAgo = lastSyncTime?.let { t -> formatTimeAgo(t) } ?: "Never",
                    )
                }
            }
        }
    }

    private fun observeActiveSession() {
        viewModelScope.launch {
            dao.getActiveSessionFlow().collectLatest { session ->
                if (session == null) {
                    _state.update {
                        it.copy(
                            sessionTrackedSteps = "--",
                            sessionTrackedLabel = "No active session",
                            baselineSteps = null,
                        )
                    }
                }
            }
        }
    }

    // ─── BLE observers ────────────────────────────────────────────────────────

    private fun observeBleState() {
        viewModelScope.launch {
            bleState?.collectLatest { ble ->
                val (status, label) = bleConnectionToStatus(ble, _state.value.speed)
                _state.update {
                    it.copy(
                        treadmillStatus = status,
                        statusLabel = label,
                        isOffline = ble == BleConnectionState.DISCONNECTED,
                    )
                }
            }
        }
    }

    private fun observeBleReadings() {
        viewModelScope.launch {
            bleLatestReading?.collectLatest { reading ->
                if (reading == null) return@collectLatest
                val currentBle = bleState?.value ?: BleConnectionState.CONNECTED
                val (status, label) = bleConnectionToStatus(currentBle, reading.speed)
                val fmt = NumberFormat.getNumberInstance(Locale.getDefault())

                _state.update { prev ->
                    val baseline = prev.baselineSteps ?: reading.steps
                    val tracked = reading.steps - baseline
                    val mins = reading.timeSecs / 60
                    val secs = reading.timeSecs % 60

                    prev.copy(
                        treadmillStatus = status,
                        statusLabel = label,
                        speed = reading.speed,
                        baselineSteps = baseline,
                        todaySteps = prev.todayFlushedSteps + tracked,
                        sessionTrackedSteps = "+${fmt.format(tracked)}",
                        sessionTrackedLabel = "since connected",
                        treadmillTotal = "${fmt.format(reading.steps)} steps",
                        treadmillStartedAt = "started at ${fmt.format(baseline)} · ${mins}m ${secs}s on belt",
                    )
                }
            }
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    fun sync() {
        viewModelScope.launch {
            _state.update { it.copy(syncState = SyncState.SYNCING, syncDetail = "Fetching intervals...") }
            try {
                val pending = dao.getPendingIntervals()
                if (pending.isEmpty()) {
                    _state.update {
                        it.copy(syncState = SyncState.SUCCESS, syncDetail = "Everything is synced")
                    }
                    delay(2_000)
                    _state.update { it.copy(syncState = SyncState.IDLE) }
                    return@launch
                }

                pending.forEachIndexed { i, interval ->
                    _state.update {
                        it.copy(syncDetail = "Writing ${i + 1} of ${pending.size} to Health Connect...")
                    }
                    healthConnect.writeSteps(interval)
                    dao.markSynced(interval.id, Instant.now().toString())
                }

                lastSyncTime = Instant.now()
                _state.update {
                    it.copy(
                        syncState = SyncState.SUCCESS,
                        syncDetail = "Synced ${pending.size} intervals to Health Connect",
                        lastSyncAgo = "Just now",
                        pendingCount = 0,
                    )
                }
                delay(2_000)
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun bleConnectionToStatus(
        ble: BleConnectionState,
        speed: Double,
    ): Pair<TreadmillStatus, String> = when (ble) {
        BleConnectionState.CONNECTED -> if (speed > 0) {
            TreadmillStatus.WALKING to "Walking — $speed mph"
        } else {
            TreadmillStatus.CONNECTED to "Connected"
        }
        BleConnectionState.SCANNING -> TreadmillStatus.SEARCHING to "Searching..."
        BleConnectionState.CONNECTING -> TreadmillStatus.SEARCHING to "Connecting..."
        BleConnectionState.DISCONNECTED -> TreadmillStatus.OFFLINE to "Disconnected"
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
