package com.tfournet.treadmill.ui.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    backgroundSyncEnabled: Boolean,
    onBackgroundSyncChanged: (Boolean) -> Unit,
    healthConnectGranted: Boolean,
    onGrantHealthConnect: () -> Unit,
    onBack: () -> Unit,
) {
    var editedUrl by remember(serverUrl) { mutableStateOf(serverUrl) }

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
            // Server section
            SectionHeader("Server")
            OutlinedTextField(
                value = editedUrl,
                onValueChange = { editedUrl = it },
                label = { Text("Server address") },
                prefix = { Text("https://") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            if (editedUrl != serverUrl) {
                TextButton(
                    onClick = { onServerUrlChanged(editedUrl) },
                    modifier = Modifier.padding(start = 16.dp),
                ) {
                    Text("Save")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Sync section
            SectionHeader("Sync")
            ListItem(
                headlineContent = { Text("Background sync") },
                supportingContent = { Text("Sync automatically every 15 minutes") },
                trailingContent = {
                    Switch(
                        checked = backgroundSyncEnabled,
                        onCheckedChange = onBackgroundSyncChanged,
                    )
                },
            )

            // Health Connect section
            SectionHeader("Health Connect")
            ListItem(
                headlineContent = { Text("Health Connect") },
                supportingContent = {
                    if (healthConnectGranted) {
                        Text(
                            "Steps write permission granted",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "Permission required",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                trailingContent = {
                    if (!healthConnectGranted) {
                        TextButton(onClick = onGrantHealthConnect) {
                            Text("Grant")
                        }
                    }
                },
            )

            // About section
            SectionHeader("About")
            ListItem(
                headlineContent = { Text("App version") },
                supportingContent = { Text("1.0.0") },
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
