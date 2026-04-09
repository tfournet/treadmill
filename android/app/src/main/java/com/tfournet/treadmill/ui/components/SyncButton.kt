package com.tfournet.treadmill.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR }

@Composable
fun SyncButton(
    state: SyncState,
    detailText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledTonalButton(
            onClick = onClick,
            enabled = state != SyncState.SYNCING,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            when (state) {
                SyncState.SYNCING -> {
                    val transition = rememberInfiniteTransition(label = "syncSpin")
                    val rotation by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                        label = "rotation",
                    )
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.rotate(rotation),
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Syncing...")
                }
                SyncState.SUCCESS -> {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Synced")
                }
                SyncState.ERROR -> {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Sync failed — tap to retry")
                }
                SyncState.IDLE -> {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Sync Now")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = detailText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
