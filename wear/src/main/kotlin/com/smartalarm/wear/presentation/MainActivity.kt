package com.smartalarm.wear.presentation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartalarm.wear.alarm.WatchAlarmActivity
import com.smartalarm.wear.service.SleepTrackingService
import com.smartalarm.wear.service.WatchPhase
import com.smartalarm.wear.service.WatchSession

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchSession.restore(this)

        setContent {
            SmartAlarmWearTheme {
                WatchRoot(
                    onStart = { plan -> SleepTrackingService.start(this, plan) },
                    onStop = { SleepTrackingService.send(this, SleepTrackingService.ACTION_STOP) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the alarm fired while the app was closed, go straight to the dismiss screen.
        if (WatchSession.phase.value == WatchPhase.ALARM) {
            startActivity(Intent(this, WatchAlarmActivity::class.java))
        }
    }
}

@Composable
private fun WatchRoot(
    onStart: (com.smartalarm.core.model.AlarmPlan) -> Unit,
    onStop: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val phase by WatchSession.phase.collectAsStateWithLifecycle()
    val status by WatchSession.status.collectAsStateWithLifecycle()
    val plan by WatchSession.plan.collectAsStateWithLifecycle()
    val connected by WatchSession.phoneConnected.collectAsStateWithLifecycle()
    val message by WatchSession.message.collectAsStateWithLifecycle()

    var sensorsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        sensorsGranted = results[Manifest.permission.BODY_SENSORS] ?: sensorsGranted
    }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    when (phase) {
        WatchPhase.TRACKING, WatchPhase.ALARM -> TrackingScreen(
            status = status,
            plan = plan,
            phoneConnected = connected,
            onStop = onStop,
        )

        WatchPhase.IDLE -> IdleScreen(
            plan = plan,
            phoneConnected = connected,
            sensorsGranted = sensorsGranted,
            message = message,
            onCycles = { WatchSession.setCycles(context, it) },
            onWakeMode = { WatchSession.setWakeMode(context, it) },
            onRequestPermissions = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
                )
            },
            onStart = { onStart(plan) },
        )
    }
}
