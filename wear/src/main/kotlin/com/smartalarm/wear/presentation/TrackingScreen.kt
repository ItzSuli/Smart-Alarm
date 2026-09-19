package com.smartalarm.wear.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.sleep.label
import kotlin.math.roundToInt

/**
 * The night in progress.
 *
 * Deliberately dim and sparse: this screen is read at 3 a.m. by someone who should be going
 * straight back to sleep. One ring for progress through the requested cycles, the stage, and
 * when the alarm currently expects to go off.
 */
@Composable
fun TrackingScreen(
    status: LiveStatus?,
    plan: AlarmPlan,
    phoneConnected: Boolean,
    onStop: () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier.fillMaxSize().background(Night.Background),
            contentAlignment = Alignment.Center,
        ) {
            val completed = status?.completedCycles ?: 0
            val progress = ((completed + (status?.currentCycleProgress ?: 0f)) / plan.cycles)
                .coerceIn(0f, 1f)
            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(700),
                label = "cycleProgress",
            )
            val stageColor by animateColorAsState(
                targetValue = status?.stage?.color() ?: Night.TextSecondary,
                animationSpec = tween(900),
                label = "stageColor",
            )

            CycleRing(
                progress = animatedProgress,
                cycles = plan.cycles,
                completed = completed,
                color = stageColor,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Text(
                    text = status?.stage?.label() ?: "Settling",
                    color = stageColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Cycle ${(completed + 1).coerceAtMost(plan.cycles)} of ${plan.cycles}",
                    style = MaterialTheme.typography.caption2,
                    color = Night.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = status?.let { formatClock(it.projectedWakeMillis) } ?: "--:--",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    color = Night.TextPrimary,
                )
                Text(
                    text = if (status?.alarmFired == true) "waking you now" else "wake around",
                    style = MaterialTheme.typography.caption3,
                    color = Night.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    status?.let { live ->
                        Metric(formatDuration(live.asleepMinutes), "asleep")
                        if (live.latestHeartRate > 0f) {
                            Metric("${live.latestHeartRate.roundToInt()}", "bpm")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                CompactChip(
                    onClick = onStop,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        Text("Stop", style = MaterialTheme.typography.caption1)
                    },
                )
                if (status?.heartRateAvailable == false) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "No heart rate — stages are rough",
                        style = MaterialTheme.typography.caption3,
                        color = Night.Awake,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, color = Night.TextPrimary, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.caption3, color = Night.TextSecondary)
    }
}

/**
 * A ring around the watch face: one tick per requested cycle, filled as the night progresses.
 * Completed cycles are solid, so a glance tells you how many you have banked.
 */
@Composable
private fun CycleRing(progress: Float, cycles: Int, completed: Int, color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = Night.Surface,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        // A gap between each cycle's segment, so they read as distinct milestones.
        if (cycles > 1) {
            val gap = 3f
            repeat(cycles) { index ->
                val angle = -90f + 360f * index / cycles
                drawArc(
                    color = Night.Background,
                    startAngle = angle - gap / 2f,
                    sweepAngle = gap,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 1.2f, cap = StrokeCap.Butt),
                )
            }
        }
    }
}
