package com.smartalarm.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.mobile.ui.components.StageBar
import com.smartalarm.mobile.ui.components.StatTile
import com.smartalarm.mobile.ui.formatClock
import com.smartalarm.mobile.ui.formatDayMonth
import com.smartalarm.mobile.ui.formatDuration
import com.smartalarm.mobile.ui.formatDurationShort
import com.smartalarm.mobile.ui.theme.NightColors
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(
    nights: List<SessionSummary>,
    onOpen: (SessionSummary) -> Unit,
    contentPadding: PaddingValues,
) {
    Box(Modifier.fillMaxSize().background(NightColors.SkyGradient)) {
        if (nights.isEmpty()) {
            EmptyHistory(Modifier.align(Alignment.Center))
            return@Box
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Nights",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NightColors.TextPrimary,
                )
            }
            item { AverageCard(nights) }
            items(nights, key = { it.sessionId }) { night ->
                NightRow(night, onClick = { onOpen(night) })
            }
        }
    }
}

@Composable
private fun AverageCard(nights: List<SessionSummary>) {
    val recent = nights.take(7)
    val averageAsleep = recent.map { it.asleepMinutes }.average().toFloat()
    val averageEfficiency = recent.map { it.sleepEfficiency }.average().toFloat()
    val averageCycles = recent.map { it.cycles.count { cycle -> cycle.complete } }.average()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(16.dp),
    ) {
        Text(
            "LAST ${recent.size} NIGHT${if (recent.size == 1) "" else "S"}",
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.TextTertiary,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(formatDuration(averageAsleep), "Avg asleep", modifier = Modifier.weight(1f))
            StatTile(
                "${averageEfficiency.roundToInt()}%", "Efficiency",
                accent = NightColors.Secondary, modifier = Modifier.weight(1f),
            )
            StatTile(
                String.format("%.1f", averageCycles), "Avg cycles",
                accent = NightColors.Primary, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NightRow(night: SessionSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(NightColors.Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    formatDayMonth(night.startedMillis),
                    style = MaterialTheme.typography.titleMedium,
                    color = NightColors.TextPrimary,
                )
                Text(
                    "${formatClock(night.startedMillis)} – ${formatClock(night.endedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightColors.TextTertiary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDuration(night.asleepMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    color = NightColors.Primary,
                )
                Text(
                    "${night.cycles.count { it.complete }} cycles",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightColors.TextTertiary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        StageBar(
            lightMinutes = night.lightMinutes,
            deepMinutes = night.deepMinutes,
            remMinutes = night.remMinutes,
            awakeMinutes = night.awakeMinutes,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStat("Deep", formatDurationShort(night.deepMinutes))
            MiniStat("REM", formatDurationShort(night.remMinutes))
            MiniStat("Efficiency", "${night.sleepEfficiency.roundToInt()}%")
            if (night.alarmFired) MiniStat("Wake quality", "${night.wakeQuality}%")
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Row {
        Text(
            "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.TextTertiary,
        )
        Text(value, style = MaterialTheme.typography.labelSmall, color = NightColors.TextSecondary)
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No nights yet",
            style = MaterialTheme.typography.headlineSmall,
            color = NightColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Track a night and it will show up here, with the cycles the watch found and how " +
                "good the moment it woke you was.",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}
