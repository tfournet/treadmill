package com.tfournet.treadspan.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociatedDevice
import android.companion.CompanionDeviceService
import android.util.Log
import com.tfournet.treadspan.TreadSpanApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val TAG = "CompanionService"
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val OP_TIMEOUT_MS      = 5_000L

// ─── GATT event bus ──────────────────────────────────────────────────────────

private sealed class GattEvent {
    data class ConnectionState(val newState: Int) : GattEvent()
    data object ServicesDiscovered : GattEvent()
    data object WriteAck : GattEvent()
    data class Notification(val value: ByteArray) : GattEvent()
}

/** Receives the next event of type [T], silently discarding any that don't match. */
private suspend inline fun <reified T : GattEvent> Channel<GattEvent>.awaitType(): T {
    while (true) {
        val event = receive()
        if (event is T) return event
    }
}

// ─── Service ─────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
class TreadmillCompanionService : CompanionDeviceService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceAppeared(address: String) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val btDevice = adapter.getRemoteDevice(address)
        scope.launch {
            try {
                runBleSession(btDevice)
            } catch (e: Exception) {
                Log.e(TAG, "BLE session error for $address: ${e.message}")
            }
        }
    }

    override fun onDeviceDisappeared(address: String) {
        val tracker = (application as TreadSpanApp).sessionTracker
        scope.launch { tracker.flush() }
    }

    // ─── BLE session ─────────────────────────────────────────────────────────

    private suspend fun runBleSession(device: BluetoothDevice) {
        val events  = Channel<GattEvent>(capacity = Channel.UNLIMITED)
        val decoder = TreadmillDecoder()
        val tracker = (application as TreadSpanApp).sessionTracker

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(GattEvent.ConnectionState(newState))
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                events.trySend(GattEvent.ServicesDiscovered)
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                events.trySend(GattEvent.WriteAck)
            }
            override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                events.trySend(GattEvent.WriteAck)
            }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                events.trySend(GattEvent.Notification(value.copyOf()))
            }
        }

        val gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            // 1. Wait for connection
            withTimeout(CONNECT_TIMEOUT_MS) {
                val ev = events.awaitType<GattEvent.ConnectionState>()
                check(ev.newState == BluetoothProfile.STATE_CONNECTED) { "Connect failed, state=${ev.newState}" }
            }
            Log.i(TAG, "Connected: ${device.address}")

            // 2. Discover services
            withTimeout(OP_TIMEOUT_MS) {
                gatt.discoverServices()
                events.awaitType<GattEvent.ServicesDiscovered>()
            }

            val svc = checkNotNull(gatt.getService(UUID.fromString(TreadmillProtocol.SERVICE_UUID))) {
                "Treadmill service not found"
            }
            val notifyChar = checkNotNull(svc.getCharacteristic(UUID.fromString(TreadmillProtocol.NOTIFY_UUID)))
            val writeChar  = checkNotNull(svc.getCharacteristic(UUID.fromString(TreadmillProtocol.WRITE_UUID)))

            // 3. Enable CCCD notifications
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(UUID.fromString(TreadmillProtocol.CCCD_UUID))
            withTimeout(OP_TIMEOUT_MS) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                events.awaitType<GattEvent.WriteAck>()
            }

            // 4. Handshake: CMD_INIT_1 × 2, CMD_INIT_2, CMD_POLL
            for (cmd in listOf(
                TreadmillProtocol.CMD_INIT_1,
                TreadmillProtocol.CMD_INIT_1,
                TreadmillProtocol.CMD_INIT_2,
                TreadmillProtocol.CMD_POLL,
            )) {
                withTimeout(OP_TIMEOUT_MS) {
                    gatt.writeCharacteristic(writeChar, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    events.awaitType<GattEvent.WriteAck>()
                }
            }

            // 5. Collect notification fragments until decoder produces a reading
            withTimeout(OP_TIMEOUT_MS) {
                while (true) {
                    val n = events.awaitType<GattEvent.Notification>()
                    val readings = decoder.feed(n.value)
                    readings.forEach { tracker.onReading(it) }
                    if (readings.isNotEmpty()) break
                }
            }
        } finally {
            gatt.disconnect()
            gatt.close()
            Log.i(TAG, "Disconnected: ${device.address}")
        }
    }
}
