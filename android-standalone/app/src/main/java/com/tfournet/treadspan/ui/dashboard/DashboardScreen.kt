package com.tfournet.treadspan.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tfournet.treadspan.ui.components.HeroMetric
import com.tfournet.treadspan.ui.components.InfoCard
import com.tfournet.treadspan.ui.components.StatusPill
import com.tfournet.treadspan.ui.components.StepBarChart
import com.tfournet.treadspan.ui.components.SyncButton
import com.tfournet.treadspan.ui.components.TreadmillStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "TreadSpan",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroMetric(
                steps = state.todaySteps,
                isOffline = state.isOffline,
            )

            StatusPill(
                status = state.treadmillStatus,
                label = state.statusLabel,
            )

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoCard(
                    title = "Last Sync",
                    value = state.lastSyncAgo,
                    subtitle = if (state.pendingCount > 0) "${state.pendingCount} pending"
                               else "All synced",
                    valueColor = if (state.pendingCount > 0) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val isConnected = state.treadmillStatus == TreadmillStatus.WALKING ||
                    state.treadmillStatus == TreadmillStatus.CONNECTED
                InfoCard(
                    title = if (isConnected) "Current Session" else "Session",
                    value = state.sessionSteps,
                    subtitle = state.sessionSubtitle,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))

            StepBarChart(
                bars = state.chartBars,
                modifier = Modifier.padding(horizontal = 16.dp),
                totalDuration = state.chartSummary,
            )

            Spacer(Modifier.height(24.dp))

            SyncButton(
                state = state.syncState,
                detailText = state.syncDetail,
                onClick = { viewModel.sync() },
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
