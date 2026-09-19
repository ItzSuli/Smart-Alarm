package com.smartalarm.mobile.session

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.smartalarm.core.model.CheckIds
import com.smartalarm.core.model.CheckResult
import com.smartalarm.core.model.CheckStatus
import com.smartalarm.core.model.SelfTestRequest
import com.smartalarm.core.model.SelfTestResult
import com.smartalarm.core.model.SystemCheckReport
import com.smartalarm.core.model.WakeMode
import com.smartalarm.mobile.alarm.AlarmRingService
import com.smartalarm.mobile.data.SettingsStore
import com.smartalarm.mobile.wear.WatchBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Proves the whole chain works, on an evening when it does not matter.
 *
 * Nothing here is a simulation. It holds the watch's real sensors open, runs the real feature
 * pipeline over what arrives, sends the result back over the real data layer and plays the real
 * alarm — because every one of those has a failure mode that only shows up when it is used for
 * real, and finding them at 6 a.m. on a morning that matters is the thing this exists to avoid.
 *
 * The last two checks cannot be made by a machine: whether the buzz woke you and whether the
 * sound was loud enough are answers only the person has, so the check asks.
 */
class SystemCheck(
    private val context: Context,
    private val settings: SettingsStore,
    private val bridge: WatchBridge,
    private val scope: CoroutineScope,
) {
    private val _report = MutableStateFlow(SystemCheckReport("", 0L))
    val report: StateFlow<SystemCheckReport> = _report.asStateFlow()

    private var job: Job? = null
    private var pendingRequestId: String? = null
    private var watchResult: SelfTestResult? = null

    val isRunning: Boolean get() = _report.value.running

    /** How long the check takes, near enough, so the UI can show honest progress. */
    val estimatedSeconds: Int get() = SENSOR_SECONDS + OVERHEAD_SECONDS

    fun start() {
        if (isRunning) return
        val requestId = UUID.randomUUID().toString()
        pendingRequestId = requestId
        watchResult = null

        _report.value = SystemCheckReport(
            requestId = requestId,
            startedAtMillis = System.currentTimeMillis(),
            running = true,
            phoneChecks = phoneChecks(),
            watchChecks = listOf(
                CheckResult.running(CheckIds.ACCELEROMETER, "Motion sensor"),
                CheckResult.pending(CheckIds.ACCEL_RATE, "Sample rate"),
                CheckResult.pending(CheckIds.HEART_RATE, "Heart rate"),
                CheckResult.pending(CheckIds.EPOCH_PIPELINE, "Sleep analysis"),
                CheckResult.pending(CheckIds.VIBRATOR, "Vibration"),
                CheckResult.pending(CheckIds.WATCH_BATTERY, "Watch battery"),
            ),
        )

        job = scope.launch { run(requestId) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        pendingRequestId = null
        _report.value = _report.value.copy(running = false)
    }

    private suspend fun run(requestId: String) {
        val plan = settings.plan.value
        val sentAt = System.currentTimeMillis()

        val delivered = bridge.runSelfTest(
            SelfTestRequest(
                requestId = requestId,
                issuedAtMillis = sentAt,
                sensorSeconds = SENSOR_SECONDS,
                testAlarm = true,
                wakeMode = plan.wakeMode,
                vibrationIntensity = plan.vibrationIntensity,
            )
        )

        if (!delivered) {
            finishWithWatchFailure(
                "The watch did not receive the request.",
                "Check it is paired and in range, and open Smart Alarm on the watch once.",
            )
            return
        }

        // The watch holds its sensors open for SENSOR_SECONDS, so anything faster than that is
        // the watch failing rather than the link being slow.
        val result = withTimeoutOrNull(WATCH_TIMEOUT_MILLIS) {
            while (watchResult?.requestId != requestId) delay(250)
            watchResult
        }

        if (result == null) {
            finishWithWatchFailure(
                "No answer after ${WATCH_TIMEOUT_MILLIS / 1000} seconds.",
                "The watch may have gone out of range, or the app may have been stopped on it. " +
                    "Open Smart Alarm on the watch and try again.",
            )
            return
        }

        val roundTrip = System.currentTimeMillis() - sentAt
        val phone = phoneChecks().toMutableList()
        phone += roundTripCheck(roundTrip)
        phone += CheckResult(
            CheckIds.EPOCH_TRANSFER, "Data from the watch", CheckStatus.PASS,
            "${result.epochsProduced} epoch${if (result.epochsProduced == 1) "" else "s"} " +
                "of live sensor data made it to the phone.",
        )

        // The phone's half of the test alarm, so the wearer hears what a real morning sounds like.
        if (plan.wakeMode.usesPhone) {
            AlarmRingService.startTest(context, TEST_ALARM_MILLIS)
        }

        _report.value = _report.value.copy(
            phoneChecks = phone,
            watchChecks = result.checks,
            roundTripMillis = roundTrip,
            running = false,
            finishedAtMillis = System.currentTimeMillis(),
            confirmations = confirmationPrompts(plan.wakeMode),
        )
        pendingRequestId = null
    }

    /** Called from the data-layer listener when the watch answers. */
    fun onWatchResult(result: SelfTestResult) {
        if (result.requestId != pendingRequestId) return
        watchResult = result
    }

    /** The wearer's answer to "did you feel it" / "did you hear it". */
    fun confirm(checkId: String, felt: Boolean) {
        val updated = _report.value.confirmations.map { check ->
            if (check.id != checkId) check else check.copy(
                status = if (felt) CheckStatus.PASS else CheckStatus.FAIL,
                detail = if (felt) "Confirmed." else "Not noticed.",
                remedy = if (felt) "" else remedyFor(checkId),
            )
        }
        _report.value = _report.value.copy(confirmations = updated)
    }

    fun stopTestAlarm() = AlarmRingService.stop(context)

    private fun finishWithWatchFailure(detail: String, remedy: String) {
        _report.value = _report.value.copy(
            running = false,
            finishedAtMillis = System.currentTimeMillis(),
            watchChecks = listOf(
                CheckResult(CheckIds.WATCH_LINK, "Watch responded", CheckStatus.FAIL, detail, remedy)
            ),
        )
        pendingRequestId = null
    }

    // ------------------------------------------------------------------ phone-side checks

    private fun phoneChecks(): List<CheckResult> = listOf(
        notificationCheck(),
        exactAlarmCheck(),
        alarmVolumeCheck(),
        batteryOptimisationCheck(),
    )

    private fun notificationCheck(): CheckResult {
        val manager = context.getSystemService(NotificationManager::class.java)
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val enabled = manager.areNotificationsEnabled()
        return when {
            !runtimeGranted || !enabled -> CheckResult(
                CheckIds.NOTIFICATIONS, "Notifications", CheckStatus.FAIL,
                "Blocked for Smart Alarm.",
                "Settings → Apps → Smart Alarm → Notifications. Without this the alarm cannot " +
                    "show over the lock screen.",
            )

            else -> CheckResult(CheckIds.NOTIFICATIONS, "Notifications", CheckStatus.PASS, "Allowed.")
        }
    }

    private fun exactAlarmCheck(): CheckResult {
        val manager = context.getSystemService(AlarmManager::class.java)
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
        return if (allowed) {
            CheckResult(
                CheckIds.EXACT_ALARM, "Exact alarms", CheckStatus.PASS,
                "Allowed — the backup alarm can fire on time.",
            )
        } else {
            CheckResult(
                CheckIds.EXACT_ALARM, "Exact alarms", CheckStatus.FAIL,
                "Not allowed.",
                "Settings → Apps → Smart Alarm → Alarms & reminders. Without this the phone " +
                    "cannot ring if the watch dies overnight.",
            )
        }
    }

    private fun alarmVolumeCheck(): CheckResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val percent = volume * 100 / max
        return when {
            volume == 0 -> CheckResult(
                CheckIds.ALARM_VOLUME, "Alarm volume", CheckStatus.FAIL,
                "Muted.",
                "Turn the alarm volume up. This is separate from ring and media volume.",
            )

            percent < 30 -> CheckResult(
                CheckIds.ALARM_VOLUME, "Alarm volume", CheckStatus.WARN,
                "$percent% — quiet.",
                "The app nudges it up before ringing, but setting it yourself is safer.",
            )

            else -> CheckResult(CheckIds.ALARM_VOLUME, "Alarm volume", CheckStatus.PASS, "$percent%.")
        }
    }

    private fun batteryOptimisationCheck(): CheckResult {
        val power = context.getSystemService(PowerManager::class.java)
        val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        return if (unrestricted) {
            CheckResult(
                CheckIds.BATTERY_OPTIMISATION, "Background activity", CheckStatus.PASS,
                "Unrestricted — the phone can receive data from the watch all night.",
            )
        } else {
            // Not a FAIL: the backstop alarm survives Doze regardless. But on Samsung's
            // battery management this is the usual reason a phone stops hearing the watch.
            CheckResult(
                CheckIds.BATTERY_OPTIMISATION, "Background activity", CheckStatus.WARN,
                "Restricted.",
                "Settings → Apps → Smart Alarm → Battery → Unrestricted. The alarm still fires " +
                    "without this, but the phone may miss data from the watch overnight.",
            )
        }
    }

    private fun roundTripCheck(millis: Long): CheckResult = when {
        millis > SLOW_ROUND_TRIP_MILLIS -> CheckResult(
            CheckIds.ROUND_TRIP, "Watch connection", CheckStatus.WARN,
            "Slow to answer (${millis / 1000}s).",
            "Usually a weak Bluetooth link. Tracking still works; the phone's display may lag.",
        )

        else -> CheckResult(
            CheckIds.ROUND_TRIP, "Watch connection", CheckStatus.PASS,
            "Answered in ${millis / 1000}s.",
        )
    }

    private fun confirmationPrompts(mode: WakeMode): List<CheckResult> = buildList {
        if (mode.usesWatch) {
            add(CheckResult(CheckIds.FELT_BUZZ, "Did the watch buzz?", CheckStatus.PENDING))
        }
        if (mode.usesPhone) {
            add(CheckResult(CheckIds.HEARD_ALARM, "Did the phone ring?", CheckStatus.PENDING))
        }
    }

    private fun remedyFor(checkId: String): String = when (checkId) {
        CheckIds.FELT_BUZZ ->
            "Turn vibration strength up in Settings, and make sure the watch is not in " +
                "theatre mode or bedtime mode, which silence it."

        CheckIds.HEARD_ALARM ->
            "Turn the alarm volume up, and check Do Not Disturb is not blocking alarms."

        else -> ""
    }

    companion object {
        /** Long enough for the watch's heart-rate sensor to lock on, short enough to sit through. */
        const val SENSOR_SECONDS = 30
        private const val OVERHEAD_SECONDS = 8
        private const val WATCH_TIMEOUT_MILLIS = 90_000L
        private const val SLOW_ROUND_TRIP_MILLIS = 50_000L
        const val TEST_ALARM_MILLIS = 3_000L
    }
}
