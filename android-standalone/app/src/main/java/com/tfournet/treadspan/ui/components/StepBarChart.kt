package com.tfournet.treadspan.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class ChartBar(val hour: Float, val steps: Int)

@Composable
fun StepBarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    sessionCount: Int = 0,
    totalDuration: String = "",
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Today's Activity",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (sessionCount > 0) {
                    Text(
                        text = "$sessionCount sessions, $totalDuration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (bars.isEmpty()) {
                Text(
                    text = "No walking data yet today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(top = 48.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                var animProgress by remember { mutableFloatStateOf(0f) }
                val animatedProgress by animateFloatAsState(
                    targetValue = animProgress,
                    animationSpec = tween(500),
                    label = "chartGrow",
                )
                LaunchedEffect(bars) { animProgress = 1f }

                val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                val gridColor = MaterialTheme.colorScheme.outlineVariant
                val maxSteps = bars.maxOf { it.steps }.coerceAtLeast(1)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                ) {
                    val gridDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    for (i in 1..3) {
                        val y = size.height * (1f - i / 4f)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 0.5.dp.toPx(),
                            pathEffect = gridDash,
                        )
                    }

                    val barWidth = 4.dp.toPx()
                    val gap = 2.dp.toPx()
                    val totalBarWidth = bars.size * (barWidth + gap) - gap
                    val startX = (size.width - totalBarWidth) / 2f

                    bars.forEachIndexed { i, bar ->
                        val barHeight = (bar.steps.toFloat() / maxSteps) * size.height * animatedProgress
                        val x = startX + i * (barWidth + gap)
                        drawRect(
                            color = barColor,
                            topLeft = Offset(x, size.height - barHeight),
                            size = Size(barWidth, barHeight),
                        )
                    }
                }
            }
        }
    }
}
