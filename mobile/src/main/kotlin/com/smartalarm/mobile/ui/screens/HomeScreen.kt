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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.core.model.WakeMode
import com.smartalarm.mobile.ui.components.ConnectionPill
import com.smartalarm.mobile.ui.components.CycleSelector
import com.smartalarm.mobile.ui.components.WakeModeSelector
import com.smartalarm.mobile.ui.formatClock
import com.smartalarm.mobile.ui.formatDurationShort
import com.smartalarm.mobile.ui.relativeDayLabel
import com.smartalarm.mobile.ui.theme.NightColors
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import com.smartalarm.mobile.R

/**
 * Plan tonight.
 *
 * The hero is the projected wake time, not the cycle count, because that is the thing being
 * decided. Everything below it adjusts that one number.
 */
@Composable
fun HomeScreen(
    plan: AlarmPlan,
    profile: SleepProfile,
    watchConnected: Boolean,
    watchName: String?,
    message: String?,
    onPlanChange: ((AlarmPlan) -> AlarmPlan) -> Unit,
    onStart: () -> Unit,
    onDismissMessage: () -> Unit,
    contentPadding: PaddingValues,
) {
    val now = System.currentTimeMillis()
    val cycleMinutes = if (profile.cycleSamples > 0) profile.cycleMinutes else plan.baseCycleMinutes
    val latency = if (profile.latencySamples > 0) profile.sleepLatencyMinutes
    else plan.sleepLatencyMinutes.toFloat()

    fun wakeMillisFor(cycles: Int): Long =
        now + ((latency + cycles * cycleMinutes) * 60_000f).toLong()

    val target = wakeMillisFor(plan.cycles)
    val earliest = target - plan.windowBeforeMinutes * 60_000L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightColors.SkyGradient)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tonight", style = MaterialTheme.typography.headlineMedium, color = NightColors.TextPrimary)
            ConnectionPill(watchConnected, watchName)
        }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            NoticeCard(message, onDismissMessage)
        }

        Spacer(Modifier.height(18.dp))
        WakeTimeCard(
            target = target,
            earliest = earliest,
            cycles = plan.cycles,
            cycleMinutes = cycleMinutes,
            latency = latency,
            personalised = profile.cycleSamples > 0,
            nightsRecorded = profile.nightsRecorded,
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("Sleep cycles", "Each is roughly ${cycleMinutes.roundToInt()} minutes for you")
        Spacer(Modifier.height(10.dp))
        CycleSelector(
            selected = plan.cycles,
            wakeTimeFor = ::wakeMillisFor,
            onSelect = { cycles -> onPlanChange { it.copy(cycles = cycles) } },
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("Wake me with", null)
        Spacer(Modifier.height(10.dp))
        WakeModeSelector(
            selected = plan.wakeMode,
            watchConnected = watchConnected,
            onSelect = { mode -> onPlanChange { it.copy(wakeMode = mode) } },
        )

        Spacer(Modifier.height(24.dp))
        SmartWindowCard(plan = plan, onPlanChange = onPlanChange)

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NightColors.Primary,
                contentColor = NightColors.Background,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_bedtime), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Text("Start tracking", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = if (watchConnected) {
                "Your watch will start tracking and decide the best moment to wake you."
            } else {
                "Bring your watch into range, or press start on the watch itself."
            },
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun WakeTimeCard(
    target: Long,
    earliest: Long,
    cycles: Int,
    cycleMinutes: Float,
    latency: Float,
    personalised: Boolean,
    nightsRecorded: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(NightColors.Surface)
            .padding(22.dp),
    ) {
        Text(
            "IF YOU FALL ASLEEP NOW",
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.TextTertiary,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatClock(target),
                style = MaterialTheme.typography.displayMedium,
                color = NightColors.TextPrimary,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                relativeDayLabel(target),
                style = MaterialTheme.typography.bodyMedium,
                color = NightColors.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$cycles cycle${if (cycles == 1) "" else "s"} · " +
                formatDurationShort(cycles * cycleMinutes) + " of sleep",
            style = MaterialTheme.typography.bodyMedium,
            color = NightColors.Primary,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NightColors.Outline)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Smart wake looks for a good moment from ${formatClock(earliest)}, " +
                "and never lets you sleep past the window.",
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (personalised) {
                "Using your measured cycle length from $nightsRecorded night" +
                    "${if (nightsRecorded == 1) "" else "s"}, plus ${latency.roundToInt()} min to fall asleep."
            } else {
                "Starting from a 90-minute estimate. After tonight this uses your own cycle length."
            },
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.TextTertiary,
        )
    }
}

@Composable
private fun SmartWindowCard(plan: AlarmPlan, onPlanChange: ((AlarmPlan) -> AlarmPlan) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Smart wake", style = MaterialTheme.typography.titleMedium, color = NightColors.TextPrimary)
                Text(
                    if (plan.smartWakeEnabled) {
                        "Wakes you in light sleep, up to ${plan.windowBeforeMinutes} min early"
                    } else {
                        "Wakes you at the exact time, whatever stage you are in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NightColors.TextSecondary,
                )
            }
            Switch(
                checked = plan.smartWakeEnabled,
                onCheckedChange = { enabled -> onPlanChange { it.copy(smartWakeEnabled = enabled) } },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NightColors.Background,
                    checkedTrackColor = NightColors.Primary,
                ),
            )
        }

        if (plan.smartWakeEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                "How early it may wake you: ${plan.windowBeforeMinutes} min",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextSecondary,
            )
            Slider(
                value = plan.windowBeforeMinutes.toFloat(),
                onValueChange = { value ->
                    onPlanChange { it.copy(windowBeforeMinutes = value.roundToInt()) }
                },
                valueRange = 0f..45f,
                steps = 8,
                colors = SliderDefaults.colors(
                    thumbColor = NightColors.Primary,
                    activeTrackColor = NightColors.Primary,
                    inactiveTrackColor = NightColors.SurfaceHigh,
                ),
            )
            Text(
                "A wider window finds a better moment but costs a little sleep. " +
                    "Zero means it only ever wakes you late, never early.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextTertiary,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = NightColors.TextPrimary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NightColors.TextTertiary)
        }
    }
}

@Composable
private fun NoticeCard(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NightColors.Tertiary.copy(alpha = 0.14f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info, contentDescription = null,
            tint = NightColors.Tertiary, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = NightColors.Tertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Dismiss",
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.Tertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
