package com.smartalarm.wear.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.smartalarm.wear.presentation.Night
import com.smartalarm.wear.presentation.SmartAlarmWearTheme
import com.smartalarm.wear.presentation.formatClock
import com.smartalarm.wear.presentation.formatDuration
import com.smartalarm.wear.service.SleepTrackingService
import com.smartalarm.wear.service.WatchSession

/** Full-screen dismiss over the watch face, shown when the alarm fires. */
class WatchAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            SmartAlarmWearTheme {
                val status by WatchSession.status.collectAsStateWithLifecycle()
                AlarmContent(
                    reason = status?.let { live ->
                        "${formatDuration(live.asleepMinutes)} asleep · ${live.completedCycles} cycles"
                    } ?: "",
                    onDismiss = {
                        SleepTrackingService.send(this, SleepTrackingService.ACTION_DISMISS)
                        finish()
                    },
                    onSnooze = {
                        SleepTrackingService.send(this, SleepTrackingService.ACTION_SNOOZE)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun AlarmContent(reason: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "alarmPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Night.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Text(
                text = formatClock(System.currentTimeMillis()),
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                color = Night.TextPrimary,
                modifier = Modifier.scale(pulse),
            )
            Text(
                text = "Good morning",
                style = MaterialTheme.typography.caption1,
                color = Night.Primary,
            )
            if (reason.isNotEmpty()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.caption3,
                    color = Night.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            Chip(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.primaryChipColors(),
                label = {
                    Text(
                        "Dismiss",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.button,
                    )
                },
            )
            Spacer(Modifier.height(6.dp))
            CompactChip(
                onClick = onSnooze,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Snooze", style = MaterialTheme.typography.caption1) },
            )
        }
    }
}
