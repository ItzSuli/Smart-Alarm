package com.smartalarm.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.mobile.session.PhonePhase
import com.smartalarm.mobile.session.SessionManager
import com.smartalarm.mobile.ui.screens.HistoryScreen
import com.smartalarm.mobile.ui.screens.HomeScreen
import com.smartalarm.mobile.ui.screens.LiveScreen
import com.smartalarm.mobile.ui.screens.NightDetailScreen
import com.smartalarm.mobile.ui.screens.SettingsScreen
import com.smartalarm.mobile.ui.screens.SystemCheckScreen
import com.smartalarm.mobile.ui.theme.NightColors
import com.smartalarm.mobile.ui.theme.SmartAlarmTheme
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = SmartAlarmApp.from(this)

        setContent {
            SmartAlarmTheme(darkTheme = true) {
                AppRoot(app)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SmartAlarmApp.from(this).sessions.refreshWatchConnection()
    }
}

private enum class Tab(val label: String) { TONIGHT("Tonight"), HISTORY("Nights"), SETTINGS("Settings") }

@Composable
private fun AppRoot(app: SmartAlarmApp) {
    val sessions: SessionManager = app.sessions
    val phase by sessions.phase.collectAsStateWithLifecycle()
    val status by sessions.status.collectAsStateWithLifecycle()
    val hypnogram by sessions.hypnogram.collectAsStateWithLifecycle()
    val plan by sessions.plan.collectAsStateWithLifecycle()
    val profile by app.settings.profile.collectAsStateWithLifecycle()
    val nights by sessions.nights.collectAsStateWithLifecycle()
    val watchConnected by sessions.watchConnected.collectAsStateWithLifecycle()
    val watchNames by sessions.watchNames.collectAsStateWithLifecycle()
    val message by sessions.lastMessage.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.TONIGHT) }
    var openNight by remember { mutableStateOf<SessionSummary?>(null) }
    var systemCheckOpen by remember { mutableStateOf(false) }
    val checkReport by sessions.systemCheck.report.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    // While a night is running, keep the projection and the clock on screen moving.
    LaunchedEffect(phase) {
        while (phase == PhonePhase.TRACKING) {
            sessions.refreshWatchConnection()
            delay(30_000)
        }
    }

    Scaffold(
        containerColor = NightColors.Background,
        bottomBar = {
            NavigationBar(containerColor = NightColors.Surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry && openNight == null && !systemCheckOpen,
                        onClick = {
                            tab = entry
                            openNight = null
                            systemCheckOpen = false
                        },
                        icon = {
                            Icon(
                                painter = painterResource(
                                    when (entry) {
                                        Tab.TONIGHT -> R.drawable.ic_bedtime
                                        Tab.HISTORY -> R.drawable.ic_history
                                        Tab.SETTINGS -> R.drawable.ic_settings
                                    }
                                ),
                                contentDescription = entry.label,
                            )
                        },
                        label = { Text(entry.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NightColors.Background,
                            selectedTextColor = NightColors.Primary,
                            indicatorColor = NightColors.Primary,
                            unselectedIconColor = NightColors.TextTertiary,
                            unselectedTextColor = NightColors.TextTertiary,
                        ),
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        val night = openNight
        when {
            systemCheckOpen -> SystemCheckScreen(
                report = checkReport,
                estimatedSeconds = sessions.systemCheck.estimatedSeconds,
                onRun = { sessions.systemCheck.start() },
                onCancel = { sessions.systemCheck.cancel() },
                onConfirm = { id, yes -> sessions.systemCheck.confirm(id, yes) },
                onStopTestAlarm = { sessions.systemCheck.stopTestAlarm() },
                onBack = { systemCheckOpen = false },
                contentPadding = padding,
            )

            night != null -> NightDetailScreen(
                night = night,
                onBack = { openNight = null },
                onDelete = {
                    openNight = null
                    sessions.deleteNight(night.sessionId)
                },
                contentPadding = padding,
            )

            tab == Tab.HISTORY -> HistoryScreen(
                nights = nights,
                onOpen = { openNight = it },
                contentPadding = padding,
            )

            tab == Tab.SETTINGS -> SettingsScreen(
                plan = plan,
                profile = profile,
                lastCheckVerdict = checkReport.takeIf { it.allChecks.isNotEmpty() }?.verdict,
                onPlanChange = { sessions.updatePlan(it) },
                onResetProfile = { app.settings.updateProfile(SleepProfile()) },
                onOpenSystemCheck = { systemCheckOpen = true },
                contentPadding = padding,
            )

            phase == PhonePhase.TRACKING || phase == PhonePhase.ALARM -> LiveScreen(
                status = status,
                hypnogram = hypnogram,
                watchConnected = watchConnected,
                onStop = { sessions.stopNight() },
                contentPadding = padding,
            )

            else -> HomeScreen(
                plan = plan,
                profile = profile,
                watchConnected = watchConnected,
                watchName = watchNames.firstOrNull(),
                message = message,
                onPlanChange = { sessions.updatePlan(it) },
                onStart = { sessions.startNight(plan) },
                onDismissMessage = { sessions.dismissMessage() },
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
