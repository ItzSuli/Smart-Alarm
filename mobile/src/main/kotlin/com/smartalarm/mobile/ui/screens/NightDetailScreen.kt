package com.smartalarm.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.model.SleepCycle
import com.smartalarm.mobile.ui.components.Hypnogram
import com.smartalarm.mobile.ui.components.StageBar
import com.smartalarm.mobile.ui.components.StatTile
import com.smartalarm.mobile.ui.formatClock
import com.smartalarm.mobile.ui.formatDuration
import com.smartalarm.mobile.ui.formatDurationShort
import com.smartalarm.mobile.ui.formatFullDate
import com.smartalarm.mobile.ui.theme.NightColors
import com.smartalarm.mobile.ui.theme.color
import com.smartalarm.mobile.ui.theme.displayName
import kotlin.math.roundToInt

@Composable
fun NightDetailScreen(
    night: SessionSummary,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    contentPadding: PaddingValues,
) {
    val span = (night.endedMillis - night.startedMillis).toFloat().coerceAtLeast(1f)
    val boundaries = night.cycles.filter { it.complete }
        .map { (it.endMillis - night.startedMillis) / span }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightColors.SkyGradient)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NightColors.TextPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    formatFullDate(night.startedMillis),
                    style = MaterialTheme.typography.titleMedium,
                    color = NightColors.TextPrimary,
                )
                Text(
                    "${formatClock(night.startedMillis)} – ${formatClock(night.endedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightColors.TextTertiary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = NightColors.TextTertiary)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(formatDuration(night.asleepMinutes), "Asleep", modifier = Modifier.weight(1f))
            StatTile(
                "${night.sleepEfficiency.roundToInt()}%", "Efficiency",
                accent = NightColors.Secondary, modifier = Modifier.weight(1f),
            )
            StatTile(
                "${night.cycles.count { it.complete }}", "Cycles",
                accent = NightColors.Primary, modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(NightColors.Surface)
                .padding(16.dp),
        ) {
            Text("Hypnogram", style = MaterialTheme.typography.titleMedium, color = NightColors.TextPrimary)
            Text(
                "Dashed lines mark where each sleep cycle ended",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextTertiary,
            )
            Spacer(Modifier.height(12.dp))
            Hypnogram(
                stages = night.hypnogram.map { it.stage },
                cycleBoundaries = boundaries,
            )
            Spacer(Modifier.height(14.dp))
            StageBar(
                lightMinutes = night.lightMinutes,
                deepMinutes = night.deepMinutes,
                remMinutes = night.remMinutes,
                awakeMinutes = night.awakeMinutes,
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                com.smartalarm.core.model.SleepStage.DEEP to night.deepMinutes,
                com.smartalarm.core.model.SleepStage.LIGHT to night.lightMinutes,
                com.smartalarm.core.model.SleepStage.REM to night.remMinutes,
                com.smartalarm.core.model.SleepStage.AWAKE to night.awakeMinutes,
            ).forEach { (stage, minutes) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(stage.color())
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stage.displayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NightColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatDurationShort(minutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NightColors.TextPrimary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "${(minutes / night.timeInBedMinutes * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightColors.TextTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        CyclesCard(night.cycles)

        Spacer(Modifier.height(16.dp))
        WakeCard(night)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CyclesCard(cycles: List<SleepCycle>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(16.dp),
    ) {
        Text("Sleep cycles", style = MaterialTheme.typography.titleMedium, color = NightColors.TextPrimary)
        Text(
            "Measured from where each REM period ended, not assumed",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextTertiary,
        )
        Spacer(Modifier.height(10.dp))
        if (cycles.isEmpty()) {
            Text(
                "No complete cycles were detected.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextSecondary,
            )
            return@Column
        }
        cycles.forEach { cycle ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${cycle.index}",
                    style = MaterialTheme.typography.titleMedium,
                    color = NightColors.Primary,
                    modifier = Modifier.size(width = 22.dp, height = 22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${formatClock(cycle.startMillis)} – ${formatClock(cycle.endMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NightColors.TextPrimary,
                    )
                    Text(
                        buildString {
                            append("deep ${cycle.deepMinutes.roundToInt()}m · ")
                            append("light ${cycle.lightMinutes.roundToInt()}m · ")
                            append("REM ${cycle.remMinutes.roundToInt()}m")
                            if (!cycle.complete) append(" · in progress")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = NightColors.TextTertiary,
                    )
                }
                Text(
                    "${cycle.durationMinutes.roundToInt()}m",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NightColors.Secondary,
                )
            }
        }
        val complete = cycles.filter { it.complete }
        if (complete.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Average ${complete.map { it.durationMinutes }.average().roundToInt()} minutes per cycle.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun WakeCard(night: SessionSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(16.dp),
    ) {
        Text("How you were woken", style = MaterialTheme.typography.titleMedium, color = NightColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        if (!night.alarmFired) {
            Text(
                "Tracking was stopped before the alarm fired.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextSecondary,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                night.wakeMillis?.let { formatClock(it) } ?: "--:--",
                style = MaterialTheme.typography.headlineSmall,
                color = NightColors.TextPrimary,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                "out of ${night.wakeStage.displayName().lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = night.wakeStage.color(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Wake quality ${night.wakeQuality}%" + if (night.notes.isNotEmpty()) " · ${night.notes}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextSecondary,
        )
        night.sleepOnsetMillis?.let { onset ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Fell asleep at ${formatClock(onset)}, " +
                    "${((onset - night.startedMillis) / 60_000f).roundToInt()} minutes after starting.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextTertiary,
            )
        }
    }
}
