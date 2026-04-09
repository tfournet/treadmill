package com.tfournet.treadmill.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class TreadmillStatus {
    WALKING, CONNECTED, SEARCHING, OFFLINE
}

@Composable
fun StatusPill(
    status: TreadmillStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            TreadmillStatus.WALKING -> MaterialTheme.colorScheme.primary
            TreadmillStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
            TreadmillStatus.SEARCHING -> MaterialTheme.colorScheme.tertiary
            TreadmillStatus.OFFLINE -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(250),
        label = "dotColor",
    )

    Surface(
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulsingDot(
                color = dotColor,
                isPulsing = status == TreadmillStatus.WALKING,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun PulsingDot(
    color: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale = if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val s by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        s
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(8.dp)
            .scale(scale)
            .background(color, CircleShape),
    )
}
