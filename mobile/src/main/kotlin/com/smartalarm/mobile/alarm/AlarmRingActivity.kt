package com.smartalarm.mobile.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartalarm.mobile.SmartAlarmApp
import com.smartalarm.mobile.ui.formatClock
import com.smartalarm.mobile.ui.formatDuration
import com.smartalarm.mobile.ui.formatFullDate
import com.smartalarm.mobile.ui.theme.NightColors
import com.smartalarm.mobile.ui.theme.SmartAlarmTheme
import com.smartalarm.mobile.ui.theme.color
import com.smartalarm.mobile.ui.theme.displayName
import kotlinx.coroutines.delay

/**
 * The full-screen alarm, shown over the lock screen.
 *
 * Dismiss is a deliberately large target and snooze is a small text button, because at that
 * moment the user is barely awake and the expensive mistake is hitting snooze by accident.
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()

        val reason = intent.getStringExtra(AlarmRingService.EXTRA_REASON).orEmpty()
        val quality = intent.getIntExtra(AlarmRingService.EXTRA_QUALITY, 0)
        val app = SmartAlarmApp.from(this)

        setContent {
            SmartAlarmTheme(darkTheme = true) {
                AlarmScreen(
                    reason = reason,
                    quality = quality,
                    asleepMinutes = app.sessions.status.value?.asleepMinutes ?: 0f,
                    stageName = app.sessions.status.value?.stage?.displayName().orEmpty(),
                    stageColor = app.sessions.status.value?.stage?.color() ?: NightColors.Primary,
                    onDismiss = {
                        app.sessions.dismissAlarm()
                        finish()
                    },
                    onSnooze = {
                        app.sessions.snoozeAlarm()
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context, reason: String, quality: Int): Intent =
            Intent(context, AlarmRingActivity::class.java)
                .putExtra(AlarmRingService.EXTRA_REASON, reason)
                .putExtra(AlarmRingService.EXTRA_QUALITY, quality)
    }
}

@Composable
private fun AlarmScreen(
    reason: String,
    quality: Int,
    asleepMinutes: Float,
    stageName: String,
    stageColor: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val transition = rememberInfiniteTransition(label = "alarm")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = Modifier.fillMaxSize().background(NightColors.SkyGradient),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                formatClock(now),
                style = MaterialTheme.typography.displayLarge,
                color = NightColors.TextPrimary,
                modifier = Modifier.scale(pulse),
            )
            Text(
                formatFullDate(now),
                style = MaterialTheme.typography.bodyMedium,
                color = NightColors.TextSecondary,
            )

            Spacer(Modifier.height(28.dp))
            Text(
                "Good morning",
                style = MaterialTheme.typography.headlineSmall,
                color = NightColors.Primary,
            )
            Spacer(Modifier.height(8.dp))
            if (asleepMinutes > 0f) {
                Text(
                    "${formatDuration(asleepMinutes)} of sleep",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NightColors.TextSecondary,
                )
            }
            if (reason.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stageName.isEmpty()) NightColors.TextTertiary else stageColor,
                    textAlign = TextAlign.Center,
                )
            }
            if (quality > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wake quality $quality%",
                    style = MaterialTheme.typography.labelSmall,
                    color = NightColors.TextTertiary,
                )
            }

            Spacer(Modifier.height(44.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NightColors.Primary,
                    contentColor = NightColors.Background,
                ),
            ) {
                Text("Dismiss", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSnooze, modifier = Modifier.size(width = 160.dp, height = 44.dp)) {
                Text("Snooze", color = NightColors.TextSecondary)
            }
        }
    }
}
