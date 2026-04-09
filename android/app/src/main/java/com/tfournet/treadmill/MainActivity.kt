package com.tfournet.treadmill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tfournet.treadmill.sync.SyncWorker
import com.tfournet.treadmill.ui.dashboard.DashboardScreen
import com.tfournet.treadmill.ui.dashboard.DashboardViewModel
import com.tfournet.treadmill.ui.settings.SettingsScreen
import com.tfournet.treadmill.ui.theme.TreadSpanTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val healthPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions map */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as TreadSpanApp

        setContent {
            TreadSpanTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                val dashboardViewModel: DashboardViewModel = viewModel()
                dashboardViewModel.api = app.api
                dashboardViewModel.healthConnect = app.healthConnect

                LaunchedEffect(Unit) {
                    dashboardViewModel.startPolling()
                }

                var serverUrl by remember { mutableStateOf("zafedora:8080") }
                var backgroundSync by remember { mutableStateOf(true) }
                var healthGranted by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    healthGranted = app.healthConnect.hasPermissions()
                    if (backgroundSync) SyncWorker.schedule(this@MainActivity)
                }

                NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            tween(300),
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            tween(300),
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            tween(300),
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            tween(300),
                        )
                    },
                ) {
                    composable("dashboard") {
                        DashboardScreen(
                            viewModel = dashboardViewModel,
                            onNavigateSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            serverUrl = serverUrl,
                            onServerUrlChanged = { url ->
                                serverUrl = url
                                app.api.updateBaseUrl("http://$url")
                            },
                            backgroundSyncEnabled = backgroundSync,
                            onBackgroundSyncChanged = { enabled ->
                                backgroundSync = enabled
                                if (enabled) SyncWorker.schedule(this@MainActivity)
                                else SyncWorker.cancel(this@MainActivity)
                            },
                            healthConnectGranted = healthGranted,
                            onGrantHealthConnect = {
                                scope.launch {
                                    val contract = androidx.health.connect.client.PermissionController
                                        .createRequestPermissionResultContract()
                                    // Request Health Connect permissions
                                    healthPermissionLauncher.launch(
                                        app.healthConnect.permissions.toTypedArray()
                                            .map { it.toString() }
                                            .toTypedArray()
                                    )
                                }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
