package com.smartalarm.mobile.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.model.SleepStage
import com.smartalarm.mobile.ui.components.CycleRing
import com.smartalarm.mobile.ui.components.Hypnogram
import com.smartalarm.mobile.ui.components.StatTile
import com.smartalarm.mobile.ui.formatClock
import com.smartalarm.mobile.ui.formatDuration
import com.smartalarm.mobile.ui.formatDurationShort
import com.smartalarm.mobile.ui.theme.NightColors
import com.smartalarm.mobile.ui.theme.color
import com.smartalarm.mobile.ui.theme.displayName
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import com.smartalarm.mobile.R

/**
 * The night as it happens.
 *
 * Kept quiet and low-contrast because it is mostly looked at in the middle of the night. The
 * projected wake time is the headline and it moves as the watch measures real cycles, which is
 * the whole point of the thing.
 */
@Composable
fun LiveScreen(
    status: LiveStatus?,
    hypnogram: List<SleepStage>,
    watchConnected: Boolean,
    onStop: () -> Unit,
    contentPadding: PaddingValues,
) {
    val stage = status?.stage ?: SleepStage.UNKNOWN
    val accent by animateColorAsState(stage.color(), tween(900), label = "stageAccent")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightColors.SkyGradient)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Tracking",
            style = MaterialTheme.typography.headlineMedium,
            color = NightColors.TextPrimary,
        )
        Text(
            status?.let { "Started ${formatClock(it.trackingStartedMillis)}" } ?: "Waiting for the watch",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextTertiary,
        )

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.35f),
            contentAlignment = Alignment.Center,
        ) {
            val cycles = status?.plan?.cycles ?: 5
            val completed = status?.completedCycles ?: 0
            val progress = ((completed + (status?.currentCycleProgress ?: 0f)) / cycles)
                .coerceIn(0f, 1f)
            val animated by animateFloatAsState(progress, tween(900), label = "progress")

            CycleRing(
                progress = animated,
                cycles = cycles,
                accent = accent,
                modifier = Modifier.fillMaxWidth(0.74f).aspectRatio(1f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BreathingStage(stage = stage, accent = accent)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status?.let { formatClock(it.projectedWakeMillis) } ?: "--:--",
                        style = MaterialTheme.typography.displayMedium,
                        color = NightColors.TextPrimary,
                    )
                    Text(
                        if (status?.alarmFired == true) "waking you now" else "projected wake",
                        style = MaterialTheme.typography.labelSmall,
                        color = NightColors.TextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Cycle ${(completed + 1).coerceAtMost(cycles)} of $cycles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                value = formatDuration(status?.asleepMinutes ?: 0f),
                label = "Asleep",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = status?.latestHeartRate?.takeIf { it > 0f }?.roundToInt()?.toString() ?: "--",
                label = "bpm",
                accent = NightColors.Danger,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${status?.completedCycles ?: 0}",
                label = "Cycles done",
                accent = NightColors.Secondary,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(NightColors.Surface)
                .padding(16.dp),
        ) {
            Text("Tonight so far", style = MaterialTheme.typography.titleMedium, color = NightColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Hypnogram(stages = hypnogram)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageMinutes("Deep", status?.deepMinutes ?: 0f, SleepStage.DEEP, Modifier.weight(1f))
                StageMinutes("Light", status?.lightMinutes ?: 0f, SleepStage.LIGHT, Modifier.weight(1f))
                StageMinutes("REM", status?.remMinutes ?: 0f, SleepStage.REM, Modifier.weight(1f))
                StageMinutes("Awake", status?.awakeMinutes ?: 0f, SleepStage.AWAKE, Modifier.weight(1f))
            }
        }

        if (status != null) {
            Spacer(Modifier.height(16.dp))
            WakeWindowCard(status)
        }

        if (status?.heartRateAvailable == false) {
            Spacer(Modifier.height(12.dp))
            Text(
                "The watch is not reporting a heart rate, so deep and REM cannot be told apart. " +
                    "Sleep and wake are still tracked, and the alarm falls back to your usual " +
                    "cycle length.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.Tertiary,
            )
        }
        if (!watchConnected) {
            Spacer(Modifier.height(12.dp))
            Text(
                "The watch is out of range. It keeps tracking on its own and will catch the " +
                    "phone up when it reconnects.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextTertiary,
            )
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NightColors.TextSecondary),
        ) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Stop tracking")
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun BreathingStage(stage: SleepStage, accent: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "breathe")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "breatheAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(50))
                .background(accent)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stage.displayName(),
            style = MaterialTheme.typography.titleMedium,
            color = accent,
        )
    }
}

@Composable
private fun StageMinutes(
    label: String,
    minutes: Float,
    stage: SleepStage,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(stage.color())
        )
        Spacer(Modifier.height(6.dp))
        Text(formatDurationShort(minutes), style = MaterialTheme.typography.bodyMedium, color = NightColors.TextPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = NightColors.TextTertiary)
    }
}

/** Shows the window the alarm is allowed to fire in, and how choosy it is being right now. */
@Composable
private fun WakeWindowCard(status: LiveStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(16.dp),
    ) {
        Text("Wake window", style = MaterialTheme.typography.titleMedium, color = NightColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            WindowStamp("Earliest", status.windowStartMillis)
            WindowStamp("Target", status.projectedWakeMillis)
            WindowStamp("Latest", status.windowEndMillis)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Right now this moment scores ${(status.wakeScore * 100).roundToInt()}%, " +
                "against a bar of ${(status.wakeThreshold * 100).roundToInt()}%. " +
                "The bar falls as the deadline approaches.",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Cycle length being used: ${status.expectedCycleMinutes.roundToInt()} min" +
                if (status.measuredCycles > 0) " (measured from ${status.measuredCycles} cycles tonight)"
                else " (estimate — no full cycle measured yet)",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextTertiary,
        )
    }
}

@Composable
private fun WindowStamp(label: String, millis: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (millis > 0) formatClock(millis) else "--:--",
            style = MaterialTheme.typography.bodyLarge,
            color = NightColors.TextPrimary,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}
