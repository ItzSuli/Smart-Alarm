package com.smartalarm.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.mobile.ui.theme.NightColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    plan: AlarmPlan,
    profile: SleepProfile,
    lastCheckVerdict: String?,
    onPlanChange: ((AlarmPlan) -> AlarmPlan) -> Unit,
    onResetProfile: () -> Unit,
    onOpenSystemCheck: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightColors.SkyGradient)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = NightColors.TextPrimary)

        Spacer(Modifier.height(20.dp))
        SystemCheckEntry(lastCheckVerdict, onOpenSystemCheck)

        Spacer(Modifier.height(16.dp))
        SettingsCard("Alarm") {
            SettingSwitch(
                title = "Gentle volume ramp",
                subtitle = "Starts quiet and builds over a minute",
                checked = plan.gentleVolumeRamp,
                onChange = { value -> onPlanChange { it.copy(gentleVolumeRamp = value) } },
            )
            SettingDivider()
            SettingSwitch(
                title = "Phone backup if the watch goes quiet",
                subtitle = "Rings the phone anyway if the watch stops reporting for ten minutes",
                checked = plan.phoneBackupIfWatchSilent,
                onChange = { value -> onPlanChange { it.copy(phoneBackupIfWatchSilent = value) } },
            )
            SettingDivider()
            SettingSlider(
                title = "Snooze length",
                value = plan.snoozeMinutes.toFloat(),
                range = 3f..20f,
                steps = 16,
                display = "${plan.snoozeMinutes} min",
                onChange = { value -> onPlanChange { it.copy(snoozeMinutes = value.roundToInt()) } },
            )
            SettingDivider()
            SettingSlider(
                title = "Vibration strength",
                value = plan.vibrationIntensity.toFloat(),
                range = 0f..2f,
                steps = 1,
                display = when (plan.vibrationIntensity) {
                    0 -> "Gentle"
                    2 -> "Strong"
                    else -> "Medium"
                },
                onChange = { value -> onPlanChange { it.copy(vibrationIntensity = value.roundToInt()) } },
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsCard("Smart wake") {
            SettingSlider(
                title = "How early it may wake you",
                value = plan.windowBeforeMinutes.toFloat(),
                range = 0f..45f,
                steps = 8,
                display = "${plan.windowBeforeMinutes} min",
                onChange = { value -> onPlanChange { it.copy(windowBeforeMinutes = value.roundToInt()) } },
            )
            SettingDivider()
            SettingSlider(
                title = "How late it may wake you",
                value = plan.windowAfterMinutes.toFloat(),
                range = 0f..30f,
                steps = 5,
                display = "${plan.windowAfterMinutes} min",
                onChange = { value -> onPlanChange { it.copy(windowAfterMinutes = value.roundToInt()) } },
            )
            SettingDivider()
            SettingSlider(
                title = "Time to fall asleep",
                value = plan.sleepLatencyMinutes.toFloat(),
                range = 0f..45f,
                steps = 8,
                display = "${plan.sleepLatencyMinutes} min",
                onChange = { value -> onPlanChange { it.copy(sleepLatencyMinutes = value.roundToInt()) } },
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsCard("What the app has learned") {
            LearnedRow("Your cycle length", "${profile.cycleMinutes.roundToInt()} min")
            LearnedRow(
                "Measured from",
                "${profile.cycleSamples} cycle${if (profile.cycleSamples == 1) "" else "s"}",
            )
            LearnedRow("Time to fall asleep", "${profile.sleepLatencyMinutes.roundToInt()} min")
            LearnedRow(
                "Resting heart rate",
                if (profile.restingHeartRate > 0) "${profile.restingHeartRate.roundToInt()} bpm" else "not yet",
            )
            LearnedRow("Nights recorded", profile.nightsRecorded.toString())
            Spacer(Modifier.height(10.dp))
            Text(
                "Reset what has been learned",
                style = MaterialTheme.typography.labelLarge,
                color = NightColors.Danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onResetProfile)
                    .padding(vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsCard("About") {
            Text(
                "Smart Alarm watches your movement and heart rate through the night, works out " +
                    "where your real sleep cycles end, and wakes you at the best moment near the " +
                    "time you asked for.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Sleep stages are estimated from a wrist sensor, not measured with EEG. They are " +
                    "good enough to find cycle boundaries and pick a waking moment, and should " +
                    "not be used to diagnose anything.",
                style = MaterialTheme.typography.bodySmall,
                color = NightColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The way to find out whether any of this works without betting a morning on it.
 *
 * Sits at the top of settings rather than buried at the bottom, because the one evening it
 * matters most is the first one, before the user has any reason to trust the app.
 */
@Composable
private fun SystemCheckEntry(lastVerdict: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.PrimaryDeep.copy(alpha = 0.22f))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Test everything now",
                    style = MaterialTheme.typography.titleMedium,
                    color = NightColors.TextPrimary,
                )
                Text(
                    "Checks the sensors, the link to the watch and the alarm itself — " +
                        "takes about 40 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightColors.TextSecondary,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = NightColors.Primary)
        }
        if (lastVerdict != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Last check: $lastVerdict",
                style = MaterialTheme.typography.labelSmall,
                color = NightColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NightColors.Surface)
            .padding(18.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = NightColors.TextTertiary,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = NightColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NightColors.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NightColors.Background,
                checkedTrackColor = NightColors.Primary,
            ),
        )
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = NightColors.TextPrimary)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = NightColors.Primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = NightColors.Primary,
                activeTrackColor = NightColors.Primary,
                inactiveTrackColor = NightColors.SurfaceHigh,
            ),
        )
    }
}

@Composable
private fun SettingDivider() {
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().height(1.dp).background(NightColors.Outline)
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LearnedRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NightColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = NightColors.TextPrimary)
    }
}
