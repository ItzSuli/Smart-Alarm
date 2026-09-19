package com.smartalarm.wear.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibratorManager
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smartalarm.core.model.CheckIds
import com.smartalarm.core.model.CheckResult
import com.smartalarm.core.model.CheckStatus
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.SelfTestRequest
import com.smartalarm.core.model.SelfTestResult
import com.smartalarm.core.protocol.WearJson
import com.smartalarm.wear.R
import com.smartalarm.wear.WearApp
import com.smartalarm.wear.alarm.WatchAlarm
import com.smartalarm.wear.sensor.SensorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The watch's half of the system check.
 *
 * Holds the sensors open for half a minute and reports what actually arrived. It runs the real
 * [SensorCollector] and the real feature pipeline rather than a simplified probe, so "the
 * accelerometer is fine" means the same thing here as it does at 3 a.m. — a sensor that
 * registers successfully and then delivers nothing is a real and otherwise invisible failure
 * mode, and this catches it.
 *
 * It is a foreground service because Android will not hand an app sensor data in the background
 * otherwise, which is the same reason sleep tracking is one.
 */
class SelfTestService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var bridge: PhoneBridge

    private var collector: SensorCollector? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var accelSamples = 0
    private var heartRateSamples = 0
    private var lastHeartRate = EpochFeatures.NO_HR
    private var epochsProduced = 0
    private var activityCount = 0f
    private var sawWristContact = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bridge = PhoneBridge(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.getStringExtra(EXTRA_REQUEST)
            ?.let { runCatching { WearJson.instance.decodeFromString<SelfTestRequest>(it) }.getOrNull() }
            ?: run {
                stopSelf()
                return START_NOT_STICKY
            }

        startForeground(WearApp.NOTIFICATION_TRACKING, buildNotification())
        acquireWakeLock(request.sensorSeconds)
        scope.launch { run(request) }
        return START_NOT_STICKY
    }

    private suspend fun run(request: SelfTestRequest) {
        val checks = ArrayList<CheckResult>(6)
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val hasAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasHeartRate = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
        val sensorPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED

        val startedAt = System.currentTimeMillis()
        if (hasAccelerometer) {
            // No batching: a thirty-second test should not spend its whole life inside the
            // sensor hub's buffer waiting for a report latency that never elapses.
            val sensors = SensorCollector(this, batchSeconds = 0) { epochs ->
                epochsProduced += epochs.size
                epochs.forEach { epoch ->
                    activityCount += epoch.activityCount
                    if (epoch.hasHeartRate) lastHeartRate = epoch.heartRate
                    if (!epoch.offWrist) sawWristContact = true
                }
            }
            collector = sensors
            sensors.start(
                onAccelSample = { accelSamples++ },
                onHeartRateSample = { bpm ->
                    heartRateSamples++
                    lastHeartRate = bpm
                },
            )

            // Drain on a timer, the same way the tracking loop does.
            repeat(request.sensorSeconds) {
                delay(1000)
                sensors.pump()
            }
            sensors.stop()
            collector = null
        }

        val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000f).coerceAtLeast(1f)
        val effectiveHz = accelSamples / elapsedSeconds

        checks += accelerometerCheck(hasAccelerometer)
        checks += accelRateCheck(hasAccelerometer, effectiveHz)
        checks += heartRateCheck(hasHeartRate, sensorPermission)
        checks += epochCheck(hasAccelerometer)
        checks += vibratorCheck()
        checks += batteryCheck()

        var vibrated = false
        if (request.testAlarm && request.wakeMode.usesWatch) {
            vibrated = runCatching {
                WatchAlarm.testBuzz(this, request.vibrationIntensity)
                true
            }.getOrDefault(false)
            delay(TEST_BUZZ_MILLIS)
            WatchAlarm.stop(this)
        }

        val result = SelfTestResult(
            requestId = request.requestId,
            completedAtMillis = System.currentTimeMillis(),
            checks = checks,
            accelSamples = accelSamples,
            effectiveAccelHz = effectiveHz,
            heartRateSamples = heartRateSamples,
            lastHeartRate = lastHeartRate,
            epochsProduced = epochsProduced,
            activityCount = activityCount,
            watchBatteryPercent = batteryPercent(),
            vibrated = vibrated,
        )
        Log.i(TAG, "self test complete: ${checks.count { it.status == CheckStatus.PASS }}/${checks.size} passed")
        bridge.publishSelfTestResult(result)

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun accelerometerCheck(present: Boolean) = when {
        !present -> CheckResult(
            CheckIds.ACCELEROMETER, "Motion sensor", CheckStatus.FAIL,
            "This watch reports no accelerometer.",
            "Sleep tracking cannot work without one.",
        )

        accelSamples == 0 -> CheckResult(
            CheckIds.ACCELEROMETER, "Motion sensor", CheckStatus.FAIL,
            "The sensor registered but delivered nothing.",
            "Restart the watch. If it persists, another app may be holding the sensor.",
        )

        else -> CheckResult(
            CheckIds.ACCELEROMETER, "Motion sensor", CheckStatus.PASS,
            "$accelSamples samples received.",
        )
    }

    private fun accelRateCheck(present: Boolean, effectiveHz: Float): CheckResult {
        if (!present || accelSamples == 0) {
            return CheckResult(CheckIds.ACCEL_RATE, "Sample rate", CheckStatus.SKIPPED)
        }
        val rounded = "%.1f Hz".format(effectiveHz)
        return when {
            // The movement band tops out around 3 Hz, so sampling has to clear 6 Hz to describe
            // it at all. Below that the filters are being fed aliased noise.
            effectiveHz < MIN_USABLE_HZ -> CheckResult(
                CheckIds.ACCEL_RATE, "Sample rate", CheckStatus.FAIL,
                "Only $rounded — too slow to see movement properly.",
                "Something is throttling the sensor. Restart the watch and close other fitness apps.",
            )

            effectiveHz < GOOD_HZ -> CheckResult(
                CheckIds.ACCEL_RATE, "Sample rate", CheckStatus.WARN,
                "$rounded, lower than the 20 Hz requested.",
                "Tracking will still work, a little less precisely.",
            )

            else -> CheckResult(CheckIds.ACCEL_RATE, "Sample rate", CheckStatus.PASS, rounded)
        }
    }

    private fun heartRateCheck(present: Boolean, permitted: Boolean): CheckResult = when {
        !present -> CheckResult(
            CheckIds.HEART_RATE, "Heart rate", CheckStatus.FAIL,
            "This watch reports no heart-rate sensor.",
            "Deep and REM sleep cannot be told apart without it.",
        )

        !permitted -> CheckResult(
            CheckIds.HEART_RATE, "Heart rate", CheckStatus.FAIL,
            "Permission not granted.",
            "Grant body sensors on the watch, or run:\n" +
                "adb shell pm grant com.smartalarm android.permission.BODY_SENSORS",
        )

        heartRateSamples == 0 -> CheckResult(
            CheckIds.HEART_RATE, "Heart rate", CheckStatus.FAIL,
            "No reading in the whole test.",
            "Wear the watch snugly, a finger's width above the wrist bone, and try again. " +
                "Without a pulse the app can only tell asleep from awake.",
        )

        lastHeartRate < 30f || lastHeartRate > 200f -> CheckResult(
            CheckIds.HEART_RATE, "Heart rate", CheckStatus.WARN,
            "Readings look implausible (${lastHeartRate.toInt()} bpm).",
            "Usually means a loose strap.",
        )

        else -> CheckResult(
            CheckIds.HEART_RATE, "Heart rate", CheckStatus.PASS,
            "${lastHeartRate.toInt()} bpm, $heartRateSamples readings.",
        )
    }

    private fun epochCheck(present: Boolean): CheckResult = when {
        !present -> CheckResult(CheckIds.EPOCH_PIPELINE, "Sleep analysis", CheckStatus.SKIPPED)
        epochsProduced == 0 -> CheckResult(
            CheckIds.EPOCH_PIPELINE, "Sleep analysis", CheckStatus.FAIL,
            "The pipeline produced no data from the sensors.",
            "Report this — the sensors worked but the analysis did not run.",
        )

        else -> CheckResult(
            CheckIds.EPOCH_PIPELINE, "Sleep analysis", CheckStatus.PASS,
            "$epochsProduced epoch${if (epochsProduced == 1) "" else "s"} analysed from live sensor data.",
        )
    }

    private fun vibratorCheck(): CheckResult {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        return when {
            vibrator == null || !vibrator.hasVibrator() -> CheckResult(
                CheckIds.VIBRATOR, "Vibration", CheckStatus.FAIL,
                "No vibration motor available.",
                "Watch-only wake mode will not work.",
            )

            else -> CheckResult(
                CheckIds.VIBRATOR, "Vibration", CheckStatus.PASS,
                if (vibrator.hasAmplitudeControl()) "Motor works, with strength control."
                else "Motor works, fixed strength.",
            )
        }
    }

    private fun batteryCheck(): CheckResult {
        val percent = batteryPercent()
        return when {
            percent < 0 -> CheckResult(CheckIds.WATCH_BATTERY, "Watch battery", CheckStatus.SKIPPED)
            // A tracked night costs roughly a fifth to a third of the battery.
            percent < 35 -> CheckResult(
                CheckIds.WATCH_BATTERY, "Watch battery", CheckStatus.WARN,
                "$percent%.",
                "A tracked night costs 20-30%. Charge before bed or the watch may die before morning.",
            )

            else -> CheckResult(CheckIds.WATCH_BATTERY, "Watch battery", CheckStatus.PASS, "$percent%.")
        }
    }

    private fun batteryPercent(): Int = runCatching {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }.getOrDefault(-1)

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, WearApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Checking the watch")
            .setContentText("Hold still for a moment")
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun acquireWakeLock(seconds: Int) {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartAlarm::SelfTest").apply {
            setReferenceCounted(false)
            acquire((seconds + 30) * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        collector?.stop()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SelfTestService"
        const val EXTRA_REQUEST = "request"
        private const val MIN_USABLE_HZ = 6f
        private const val GOOD_HZ = 12f
        private const val TEST_BUZZ_MILLIS = 2500L
        private const val REQUEST_SELF_TEST = 201

        fun start(context: Context, request: SelfTestRequest) {
            val intent = Intent(context, SelfTestService::class.java)
                .putExtra(EXTRA_REQUEST, WearJson.instance.encodeToString(request))
            context.startForegroundService(intent)
        }

        /**
         * Start from the data-layer listener, which runs in the background where a foreground
         * service may not be started directly. See [SleepTrackingService.startFromBackground].
         */
        fun startFromBackground(context: Context, request: SelfTestRequest) {
            val intent = Intent(context, WatchCommandReceiver::class.java)
                .setAction(WatchCommandReceiver.ACTION_START_SELF_TEST)
                .putExtra(
                    WatchCommandReceiver.EXTRA_SELF_TEST,
                    WearJson.instance.encodeToString(request),
                )
            val operation = android.app.PendingIntent.getBroadcast(
                context, REQUEST_SELF_TEST, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
            val scheduled = runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 200L,
                    operation,
                )
            }.onFailure { Log.w(TAG, "could not schedule the self test", it) }.isSuccess

            if (!scheduled) {
                runCatching { start(context, request) }
                    .onFailure { Log.e(TAG, "could not run the self test at all", it) }
            }
        }
    }
}
