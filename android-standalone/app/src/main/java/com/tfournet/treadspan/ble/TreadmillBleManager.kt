package com.tfournet.treadspan.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private const val TAG = "TreadmillBleManager"
private const val DEVICE_NAME = "SPERAX_RM01"
private const val POLL_INTERVAL_MS = 10_000L   // poll every 10s for responsive live display
private const val INIT_GAP_1_MS = 300L         // gap between two CMD_INIT_1 writes
private const val INIT_GAP_2_MS = 500L         // gap before CMD_INIT_2
private const val GATT_SETUP_DELAY_MS = 600L   // settle after connection before service discovery
private const val BACKOFF_MAX_SECS = 60L        // cap exponential backoff; retry forever

@SuppressLint("MissingPermission")
class TreadmillBleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onReading: (TreadmillReading) -> Unit,
) {
    enum class State { IDLE, SCANNING, CONNECTING, INITING, POLLING, BACKOFF }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _latestReading = MutableStateFlow<TreadmillReading?>(null)
    val latestReading: StateFlow<TreadmillReading?> = _latestReading.asStateFlow()

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private val decoder = TreadmillDecoder()

    private val serviceUuid = UUID.fromString(TreadmillProtocol.SERVICE_UUID)
    private val notifyUuid  = UUID.fromString(TreadmillProtocol.NOTIFY_UUID)
    private val writeUuid   = UUID.fromString(TreadmillProtocol.WRITE_UUID)
    private val cccdUuid    = UUID.fromString(TreadmillProtocol.CCCD_UUID)

    private var failureCount = 0

    // ─── Public API ──────────────────────────────────────────────────────────

    suspend fun connect() {
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable")
            return
        }
        failureCount = 0
        startScan()
    }

    suspend fun disconnect() {
        cancelJobs()
        closeGatt()
        _state.value = State.IDLE
    }

    // ─── Scan ────────────────────────────────────────────────────────────────

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable — retrying in 2s")
            connectJob = scope.launch { delay(2_000); startScan() }
            return
        }
        _state.value = State.SCANNING
        Log.i(TAG, "Scanning for $DEVICE_NAME")

        // Clear any stale scan state — Android returns SCAN_FAILED_ALREADY_STARTED (1)
        // if a previous scan wasn't properly stopped.
        try { scanner.stopScan(scanCallback) } catch (_: Exception) {}

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
    }

    private fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name != DEVICE_NAME) return
            Log.i(TAG, "Found $DEVICE_NAME at ${result.device.address}")
            stopScan()
            connectGatt(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scheduleBackoff()
        }
    }

    // ─── GATT Connection ─────────────────────────────────────────────────────

    private fun connectGatt(device: BluetoothDevice) {
        _state.value = State.CONNECTING
        Log.i(TAG, "Connecting GATT to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        // Callbacks arrive on a binder thread — dispatch work to the coroutine scope.

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected — discovering services")
                    scope.launch(Dispatchers.IO) {
                        delay(GATT_SETUP_DELAY_MS)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    scope.launch(Dispatchers.IO) { handleDisconnect() }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                scope.launch(Dispatchers.IO) { handleDisconnect() }
                return
            }
            val service = gatt.getService(serviceUuid)
            if (service == null) {
                Log.e(TAG, "FFF0 service not found")
                scope.launch(Dispatchers.IO) { handleDisconnect() }
                return
            }
            val notifyChar = service.getCharacteristic(notifyUuid)
            if (notifyChar == null) {
                Log.e(TAG, "FFF1 notify characteristic not found")
                scope.launch(Dispatchers.IO) { handleDisconnect() }
                return
            }

            // Enable local notification routing
            gatt.setCharacteristicNotification(notifyChar, true)

            // Write CCCD descriptor to enable remote notifications
            val cccd = notifyChar.getDescriptor(cccdUuid)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            } else {
                // Some firmware omits CCCD — try to init directly
                scope.launch(Dispatchers.IO) { runInitSequence(gatt) }
            }
        }

        @Deprecated("API < 33")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == cccdUuid) {
                scope.launch(Dispatchers.IO) { runInitSequence(gatt) }
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == notifyUuid) {
                scope.launch(Dispatchers.Default) {
                    handleNotification(characteristic.value)
                }
            }
        }
    }

    // ─── Init + Poll Sequence ─────────────────────────────────────────────────

    private suspend fun runInitSequence(gatt: BluetoothGatt) {
        _state.value = State.INITING
        val service = gatt.getService(serviceUuid) ?: run {
            handleDisconnect(); return
        }
        val writeChar = service.getCharacteristic(writeUuid) ?: run {
            handleDisconnect(); return
        }

        Log.i(TAG, "Sending init sequence")

        // CMD_INIT_1 sent twice, 300ms apart
        // CMD_INIT_2 intentionally skipped — it switches the display to metric.
        // Without it, data still flows and the treadmill keeps its current unit setting.
        writeChar.value = TreadmillProtocol.CMD_INIT_1
        gatt.writeCharacteristic(writeChar)
        delay(INIT_GAP_1_MS)
        writeChar.value = TreadmillProtocol.CMD_INIT_1
        gatt.writeCharacteristic(writeChar)
        delay(INIT_GAP_2_MS)

        failureCount = 0
        _state.value = State.POLLING
        Log.i(TAG, "Init complete, entering poll loop")
        startPollLoop(gatt, writeChar)
    }

    private fun startPollLoop(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                writeChar.value = TreadmillProtocol.CMD_POLL
                gatt.writeCharacteristic(writeChar)
                Log.d(TAG, "Sent CMD_POLL keep-alive")
            }
        }
    }

    // ─── Notification Handling ────────────────────────────────────────────────

    private fun handleNotification(data: ByteArray) {
        val readings = decoder.feed(data)
        for (reading in readings) {
            _latestReading.value = reading
            onReading(reading)
        }
    }

    // ─── Disconnect + Backoff ─────────────────────────────────────────────────

    private fun handleDisconnect() {
        cancelJobs()
        closeGatt()
        scheduleBackoff()
    }

    private fun scheduleBackoff() {
        failureCount++
        _state.value = State.BACKOFF
        val backoffSecs = min(2.0.pow(failureCount).toLong(), BACKOFF_MAX_SECS)
        val jitter = (backoffSecs * 0.1 * Random.nextDouble()).toLong()
        val delaySecs = backoffSecs + jitter
        Log.i(TAG, "Backoff ${delaySecs}s (failure #$failureCount)")
        connectJob = scope.launch {
            delay(delaySecs * 1_000)
            startScan()
        }
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    private fun cancelJobs() {
        pollJob?.cancel(); pollJob = null
        connectJob?.cancel(); connectJob = null
    }

    private fun closeGatt() {
        gatt?.apply { disconnect(); close() }
        gatt = null
    }
}
