package com.smartalarm.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.WakeMode

/**
 * The pre-sleep screen: choose how many cycles, choose how to be woken, start the night.
 *
 * Everything is reachable from the watch alone, so the app still works with the phone in
 * another room — but the phone is the better place to plan, and the screen says so if it
 * cannot see one.
 */
@Composable
fun IdleScreen(
    plan: AlarmPlan,
    phoneConnected: Boolean,
    sensorsGranted: Boolean,
    message: String?,
    onCycles: (Int) -> Unit,
    onWakeMode: (WakeMode) -> Unit,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
) {
    val listState: ScalingLazyListState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().background(Night.Background),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Smart Alarm",
                        style = MaterialTheme.typography.title3,
                        color = Night.TextPrimary,
                    )
                    Text(
                        text = "${plan.cycles} cycle${if (plan.cycles == 1) "" else "s"} · about " +
                            formatDuration(plan.nominalSleepMinutes),
                        style = MaterialTheme.typography.caption2,
                        color = Night.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (message != null) {
                item { WarningCard(message) }
            }
            if (!sensorsGranted) {
                item {
                    Chip(
                        onClick = onRequestPermissions,
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Allow heart rate", style = MaterialTheme.typography.button) },
                        secondaryLabel = {
                            Text("Needed to tell deep from REM", color = Night.TextSecondary)
                        },
                    )
                }
            }

            item { SectionLabel("Sleep cycles") }
            item { CyclePicker(plan.cycles, onCycles) }

            item { SectionLabel("Wake me with") }
            items(WakeMode.entries) { mode ->
                WakeModeChip(
                    mode = mode,
                    selected = plan.wakeMode == mode,
                    enabled = mode == WakeMode.WATCH_ONLY || phoneConnected,
                    onClick = { onWakeMode(mode) },
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Chip(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(),
                    label = {
                        Text(
                            "Start night",
                            style = MaterialTheme.typography.button,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }

            item {
                Text(
                    text = if (phoneConnected) "Phone connected" else "Phone not in range",
                    style = MaterialTheme.typography.caption3,
                    color = if (phoneConnected) Night.Secondary else Night.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.caption3,
        color = Night.TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * One tap per cycle count, showing what each choice actually means in hours — the number the
 * decision is really made on.
 */
@Composable
private fun CyclePicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (AlarmPlan.MIN_CYCLES..4).forEach { CycleDot(it, selected, onSelect) }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (5..AlarmPlan.MAX_CYCLES).forEach { CycleDot(it, selected, onSelect) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = formatDurationLong(selected * 90f) + " of sleep",
            style = MaterialTheme.typography.caption2,
            color = Night.Primary,
        )
    }
}

@Composable
private fun CycleDot(value: Int, selected: Int, onSelect: (Int) -> Unit) {
    val isSelected = value == selected
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (isSelected) Night.Primary else Night.Surface)
            .clickable { onSelect(value) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = value.toString(),
            color = if (isSelected) Night.OnPrimary else Night.TextPrimary,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun WakeModeChip(
    mode: WakeMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    CompactChip(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        label = {
            Text(
                text = mode.watchLabel(),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
    )
}

@Composable
private fun WarningCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Night.Error.copy(alpha = 0.18f))
            .padding(10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.caption2,
            color = Night.Error,
            textAlign = TextAlign.Center,
        )
    }
}

fun WakeMode.watchLabel(): String = when (this) {
    WakeMode.WATCH_ONLY -> "Watch only"
    WakeMode.PHONE_ONLY -> "Phone only"
    WakeMode.BOTH -> "Watch + phone"
}
