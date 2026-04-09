package com.tfournet.treadspan.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    pairedDeviceName: String?,
    pairedDeviceAddress: String?,
    onForgetDevice: () -> Unit,
    onPairNewDevice: () -> Unit,
    backgroundSyncEnabled: Boolean,
    onBackgroundSyncChanged: (Boolean) -> Unit,
    healthConnectGranted: Boolean,
    onGrantHealthConnect: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Paired Device ──────────────────────────────────────────────────
            SectionHeader("Treadmill")
            if (pairedDeviceAddress != null) {
                ListItem(
                    headlineContent = {
                        Text(pairedDeviceName ?: "Unknown device")
                    },
                    supportingContent = {
                        Text(
                            pairedDeviceAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                OutlinedButton(
                    onClick = onForgetDevice,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Forget Device")
                }
            } else {
                ListItem(
                    headlineContent = { Text("No treadmill paired") },
                    supportingContent = { Text("Pair your Sperax RM01 to get started") },
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onPairNewDevice,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(if (pairedDeviceAddress != null) "Pair Different Device" else "Pair Treadmill")
            }

            // ── Background Sync ────────────────────────────────────────────────
            SectionHeader("Sync")
            ListItem(
                headlineContent = { Text("Background sync") },
                supportingContent = { Text("Write steps to Health Connect every 15 minutes") },
                trailingContent = {
                    Switch(
                        checked = backgroundSyncEnabled,
                        onCheckedChange = onBackgroundSyncChanged,
                    )
                },
            )

            // ── Health Connect ─────────────────────────────────────────────────
            SectionHeader("Health Connect")
            Surface(
                onClick = { if (!healthConnectGranted) onGrantHealthConnect() },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Steps permission", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    if (healthConnectGranted) {
                        Text(
                            "Read & write access granted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "Tap to grant permission",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── About ──────────────────────────────────────────────────────────
            SectionHeader("About")
            ListItem(
                headlineContent = { Text("TreadSpan") },
                supportingContent = { Text("Version 1.0.0") },
            )
            ListItem(
                headlineContent = { Text("Treadmill protocol") },
                supportingContent = { Text("Sperax RM01 · WLT6200 BLE UART bridge") },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}
