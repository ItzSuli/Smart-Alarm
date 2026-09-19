package com.smartalarm.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.SleepStage
import com.smartalarm.mobile.ui.theme.NightColors
import com.smartalarm.mobile.ui.theme.color
import com.smartalarm.mobile.ui.theme.displayName

/**
 * The night as a hypnogram: four lanes, awake at the top through deep at the bottom, with every
 * 30-second epoch drawn as a bar in its lane.
 *
 * Drawn as discrete bars rather than a connected line on purpose. A line implies the sleeper
 * passed smoothly through the intermediate stages on the way from deep to awake, which is not
 * what the data says — each epoch is an independent classification, and the bars are honest
 * about that while the eye still reads the cycles.
 */
@Composable
fun Hypnogram(
    stages: List<SleepStage>,
    modifier: Modifier = Modifier,
    cycleBoundaries: List<Float> = emptyList(),
    showLegend: Boolean = true,
) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(148.dp)) {
                drawHypnogram(stages, cycleBoundaries)
            }
            if (stages.isEmpty()) {
                Text(
                    text = "Waiting for the first readings",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightColors.TextTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (showLegend) {
            Spacer(Modifier.height(10.dp))
            StageLegend()
        }
    }
}

private val LANES = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)

private fun DrawScope.drawHypnogram(stages: List<SleepStage>, cycleBoundaries: List<Float>) {
    val laneHeight = size.height / LANES.size
    val barHeight = laneHeight * 0.58f

    // Lane guides, so the four levels read even where the night has no data.
    LANES.forEachIndexed { index, _ ->
        val y = laneHeight * index + laneHeight / 2f
        drawLine(
            color = NightColors.Outline.copy(alpha = 0.5f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 10f)),
        )
    }

    // Cycle boundaries behind the bars.
    cycleBoundaries.forEach { fraction ->
        val x = size.width * fraction.coerceIn(0f, 1f)
        drawLine(
            color = NightColors.Secondary.copy(alpha = 0.35f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
    }

    if (stages.isEmpty()) return

    val barWidth = (size.width / stages.size).coerceAtLeast(MIN_BAR_WIDTH)
    stages.forEachIndexed { index, stage ->
        if (stage == SleepStage.UNKNOWN) return@forEachIndexed
        val lane = LANES.indexOf(stage).takeIf { it >= 0 } ?: return@forEachIndexed
        val x = size.width * index / stages.size
        val centreY = laneHeight * lane + laneHeight / 2f
        drawRoundRect(
            color = stage.color(),
            topLeft = Offset(x, centreY - barHeight / 2f),
            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
        )
    }
}

private const val MIN_BAR_WIDTH = 1.2f

@Composable
fun StageLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LANES.forEach { stage ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(stage.color(), CircleShape),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stage.displayName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = NightColors.TextSecondary,
                )
            }
        }
    }
}

/** A single-row summary of how the night was spent, for compact history rows. */
@Composable
fun StageBar(
    lightMinutes: Float,
    deepMinutes: Float,
    remMinutes: Float,
    awakeMinutes: Float,
    modifier: Modifier = Modifier,
) {
    val total = (lightMinutes + deepMinutes + remMinutes + awakeMinutes).coerceAtLeast(1f)
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        var x = 0f
        val segments = listOf(
            deepMinutes to SleepStage.DEEP,
            lightMinutes to SleepStage.LIGHT,
            remMinutes to SleepStage.REM,
            awakeMinutes to SleepStage.AWAKE,
        )
        segments.forEach { (minutes, stage) ->
            val width = size.width * (minutes / total)
            if (width > 0f) {
                drawRoundRect(
                    color = stage.color(),
                    topLeft = Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                )
            }
            x += width
        }
    }
}

/** A ring showing progress through the requested cycles. */
@Composable
fun CycleRing(
    progress: Float,
    cycles: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    strokeWidth: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable () -> Unit = {},
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().padding(strokeWidth / 2)) {
            val stroke = strokeWidth.toPx()
            val diameter = minOf(size.width, size.height)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

            drawArc(
                color = NightColors.SurfaceHigh,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Notches between cycles, so the ring reads as milestones rather than a bare gauge.
            if (cycles > 1) {
                repeat(cycles) { index ->
                    val angle = -90f + 360f * index / cycles
                    drawArc(
                        color = NightColors.Background,
                        startAngle = angle - 1.4f, sweepAngle = 2.8f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = stroke * 1.25f, cap = StrokeCap.Butt),
                    )
                }
            }
        }
        content()
    }
}
