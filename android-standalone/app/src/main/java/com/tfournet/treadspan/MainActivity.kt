package com.tfournet.treadspan

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tfournet.treadspan.ble.TreadmillBleManager
import com.tfournet.treadspan.sync.SyncWorker
import com.tfournet.treadspan.ui.dashboard.BleConnectionState
import com.tfournet.treadspan.ui.dashboard.DashboardScreen
import com.tfournet.treadspan.ui.dashboard.DashboardViewModel
import com.tfournet.treadspan.ui.settings.SettingsScreen
import com.tfournet.treadspan.ui.theme.TreadSpanTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private const val TAG = "MainActivity"
private const val PREF_FILE = "treadspan_prefs"
private const val PREF_PAIRED_ADDRESS = "paired_device_address"
private const val PREF_PAIRED_NAME = "paired_device_name"
private const val PREF_BG_SYNC = "background_sync_enabled"

class MainActivity : ComponentActivity() {

    private lateinit var app: TreadSpanApp
    private lateinit var prefs: SharedPreferences
    private val dashboardViewModel: DashboardViewModel by viewModels()

    // ── Compose-observable state (initialized in onCreate) ──────────────────
    private var pairedAddress by mutableStateOf<String?>(null)
    private var pairedName by mutableStateOf<String?>(null)
    private var backgroundSyncEnabled by mutableStateOf(false)
    private var healthConnectGranted by mutableStateOf(false)
    private var blePermissionsGranted by mutableStateOf(false)

    // ── Activity Result Launchers ───────────────────────────────────────────

    /** Step 2 of CompanionDeviceManager pairing: launches the system picker UI. */
    private val associateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // BLE companion returns ScanResult, not BluetoothDevice
            @Suppress("DEPRECATION")
            val scanResult: android.bluetooth.le.ScanResult? = result.data
                ?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            val device: BluetoothDevice? = scanResult?.device
                ?: result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            if (device != null) {
                savePairedDevice(device)
                startBleIfReady()
            }
        }
    }

    /** Request BLUETOOTH_SCAN + BLUETOOTH_CONNECT (Android 12+). */
    private val bleLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        blePermissionsGranted = grants.values.all { it }
        if (blePermissionsGranted) startBleIfReady()
    }

    /** Health Connect permission request. */
    private val healthConnectLauncher = registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        healthConnectGranted = app.healthConnect.permissions.all { it in granted }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as TreadSpanApp
        prefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        pairedAddress = prefs.getString(PREF_PAIRED_ADDRESS, null)
        pairedName = prefs.getString(PREF_PAIRED_NAME, null)
        backgroundSyncEnabled = prefs.getBoolean(PREF_BG_SYNC, false)

        checkBlePermissions()
        checkHealthConnect()
        wireViewModel()

        setContent {
            TreadSpanTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        DashboardScreen(
                            viewModel = dashboardViewModel,
                            onNavigateSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            pairedDeviceName = pairedName,
                            pairedDeviceAddress = pairedAddress,
                            onForgetDevice = ::forgetDevice,
                            onPairNewDevice = ::startPairing,
                            backgroundSyncEnabled = backgroundSyncEnabled,
                            onBackgroundSyncChanged = ::setBackgroundSync,
                            healthConnectGranted = healthConnectGranted,
                            onGrantHealthConnect = { healthConnectLauncher.launch(app.healthConnect.permissions) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startBleIfReady()
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { app.bleManager.disconnect() }
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    private fun checkBlePermissions() {
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        blePermissionsGranted = needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!blePermissionsGranted) bleLauncher.launch(needed)
    }

    private fun checkHealthConnect() {
        lifecycleScope.launch {
            healthConnectGranted = try {
                app.healthConnect.hasPermissions()
            } catch (e: Exception) {
                false
            }
        }
    }

    // ── CompanionDeviceManager Pairing ──────────────────────────────────────

    /** Trigger the system BLE device picker filtered to devices named *SPERAX*. */
    private fun startPairing() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(Pattern.compile(".*SPERAX.*", Pattern.CASE_INSENSITIVE))
                    .build()
            )
            .setSingleDevice(true)
            .build()

        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                associateLauncher.launch(
                    IntentSenderRequest.Builder(chooserLauncher).build()
                )
            }
            override fun onFailure(error: CharSequence?) {
                Log.e(TAG, "CompanionDeviceManager association failed: $error")
            }
        }, null)
    }

    private fun savePairedDevice(device: BluetoothDevice) {
        val name = device.name ?: "Sperax RM01"
        val address = device.address
        prefs.edit()
            .putString(PREF_PAIRED_ADDRESS, address)
            .putString(PREF_PAIRED_NAME, name)
            .apply()
        pairedAddress = address
        pairedName = name
        Log.i(TAG, "Paired with $name ($address)")
    }

    private fun forgetDevice() {
        prefs.edit()
            .remove(PREF_PAIRED_ADDRESS)
            .remove(PREF_PAIRED_NAME)
            .apply()
        pairedAddress = null
        pairedName = null
        lifecycleScope.launch { app.bleManager.disconnect() }
    }

    // ── BLE Manager ─────────────────────────────────────────────────────────

    private fun startBleIfReady() {
        if (!blePermissionsGranted) return
        if (pairedAddress == null) {
            Log.i(TAG, "No paired device — waiting for pairing")
            return
        }
        lifecycleScope.launch { app.bleManager.connect() }
    }

    // ── ViewModel Wiring ────────────────────────────────────────────────────

    /**
     * Bridge TreadmillBleManager.State → BleConnectionState used by DashboardViewModel.
     * The ViewModel is agnostic of BLE implementation details.
     */
    private fun wireViewModel() {
        val mappedBleState = app.bleManager.state
            .map { s ->
                when (s) {
                    TreadmillBleManager.State.IDLE,
                    TreadmillBleManager.State.BACKOFF -> BleConnectionState.DISCONNECTED
                    TreadmillBleManager.State.SCANNING -> BleConnectionState.SCANNING
                    TreadmillBleManager.State.CONNECTING,
                    TreadmillBleManager.State.INITING -> BleConnectionState.CONNECTING
                    TreadmillBleManager.State.POLLING -> BleConnectionState.CONNECTED
                }
            }
            .stateIn(
                scope = lifecycleScope,
                started = SharingStarted.Eagerly,
                initialValue = BleConnectionState.DISCONNECTED,
            )

        dashboardViewModel.bleState = mappedBleState
        dashboardViewModel.bleLatestReading = app.bleManager.latestReading
        dashboardViewModel.start()
    }

    // ── Background Sync ─────────────────────────────────────────────────────

    private fun setBackgroundSync(enabled: Boolean) {
        backgroundSyncEnabled = enabled
        prefs.edit().putBoolean(PREF_BG_SYNC, enabled).apply()

        val wm = WorkManager.getInstance(this)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(
                SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } else {
            wm.cancelUniqueWork(SyncWorker.WORK_NAME)
        }
    }
}
