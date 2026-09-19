package com.smartalarm.wear.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.core.protocol.EventSource
import com.smartalarm.core.sleep.SleepSessionEngine
import com.smartalarm.core.sleep.SleepSessionEngine.Companion.formatMinutes
import com.smartalarm.core.sleep.label
import com.smartalarm.wear.R
import com.smartalarm.wear.WearApp
import com.smartalarm.wear.alarm.WatchAlarm
import com.smartalarm.wear.presentation.MainActivity
import com.smartalarm.wear.sensor.SensorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The night, running on the watch.
 *
 * This service owns the sensors and the [SleepSessionEngine], which means the watch decides for
 * itself when to wake the sleeper. The phone is told what happened, but is never asked what to
 * do — if Bluetooth drops at two in the morning the alarm still fires correctly at six.
 */
class SleepTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var bridge: PhoneBridge

    private var collector: SensorCollector? = null
    private var engine: SleepSessionEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var loop: Job? = null

    /** Epochs produced since the last push to the phone. */
    private val pendingEpochs = ArrayList<EpochFeatures>(32)
    private var lastStatusPushMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bridge = PhoneBridge(this)
        WatchSession.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val plan = intent.planExtra() ?: WatchSession.plan.value
                val profile = WatchSession.profile
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
                startTracking(sessionId, plan, profile)
            }

            ACTION_STOP -> {
                stopTracking(userStopped = true)
                return START_NOT_STICKY
            }

            ACTION_DISMISS -> {
                dismissAlarm()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                snoozeAlarm()
                return START_STICKY
            }

            else -> if (engine == null) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startTracking(sessionId: String, plan: AlarmPlan, profile: SleepProfile) {
        if (engine != null) return

        val startedAt = System.currentTimeMillis()
        val session = SleepSessionEngine(sessionId, plan, profile, startedAt)
        engine = session
        WatchSession.updatePlan(this, plan)
        WatchSession.setPhase(WatchPhase.TRACKING)
        WatchSession.setStatus(session.currentStatus(startedAt))
        WatchSession.setMessage(null)

        startForeground(WearApp.NOTIFICATION_TRACKING, buildNotification(plan, session, startedAt))

        val sensors = SensorCollector(this) { epochs -> onEpochs(epochs) }
        if (!sensors.start()) {
            WatchSession.setMessage("This watch has no usable motion sensor.")
            stopTracking(userStopped = false)
            return
        }
        collector = sensors
        acquireWakeLock()

        scope.launch { bridge.sendStateChanged(sessionId, tracking = true) }
        loop = scope.launch { trackingLoop() }
    }

    /**
     * The heartbeat of the night: close finished epochs, re-evaluate the wake decision, keep
     * the phone and the notification current.
     *
     * The wake decision is re-evaluated on this timer rather than only when an epoch arrives,
     * because the hard deadline has to fire even if the sensors have gone completely quiet.
     */
    private suspend fun trackingLoop() {
        var tick = 0L
        while (scope.isActive && engine != null) {
            val now = System.currentTimeMillis()
            val session = engine ?: break

            collector?.setBatteryPercent(batteryPercent())
            collector?.pump(now)

            val status = session.tick(now)
            WatchSession.setStatus(status)

            // As the window approaches, pull whatever the sensor hub is still holding so the
            // decision is made on fresh data rather than on a batch up to thirty seconds old.
            if (now >= status.windowStartMillis - PREFLUSH_MILLIS && !status.alarmFired) {
                collector?.flushBatch()
            }

            if (session.shouldWakeNow && WatchSession.phase.value != WatchPhase.ALARM) {
                fireAlarm()
            }

            if (now - lastStatusPushMillis >= STATUS_PUSH_INTERVAL_MILLIS) {
                lastStatusPushMillis = now
                pushToPhone(status.sessionId)
                updateNotification(session, now)
                WatchSession.setPhoneConnected(bridge.isPhoneConnected())
            }

            tick++
            delay(LOOP_INTERVAL_MILLIS)
        }
    }

    private fun onEpochs(epochs: List<EpochFeatures>) {
        val session = engine ?: return
        epochs.forEach { session.onEpoch(it) }
        synchronized(pendingEpochs) { pendingEpochs.addAll(epochs) }
    }

    private suspend fun pushToPhone(sessionId: String) {
        val batch = synchronized(pendingEpochs) {
            if (pendingEpochs.isEmpty()) emptyList() else {
                val copy = ArrayList(pendingEpochs)
                pendingEpochs.clear()
                copy
            }
        }
        if (batch.isNotEmpty()) bridge.publishEpochs(sessionId, batch)
        WatchSession.status.value?.let { bridge.publishStatus(it) }
    }

    private fun fireAlarm() {
        val session = engine ?: return
        val decision = session.firedDecision ?: session.lastDecision
        WatchSession.setPhase(WatchPhase.ALARM)

        val mode = session.plan.wakeMode
        if (mode.usesWatch) {
            WatchAlarm.start(this, session.plan.vibrationIntensity)
        }
        if (mode.usesPhone) {
            scope.launch {
                bridge.sendWakeNow(session.sessionId, decision.reason, decision.wakeQualityPercent)
            }
        }
        // The phone still needs the epochs behind the decision, whichever way it was woken.
        scope.launch { pushToPhone(session.sessionId) }

        WatchAlarm.showNotification(this, decision.reason)
        Log.i(TAG, "alarm fired: ${decision.reason} (quality ${decision.wakeQualityPercent}%)")
    }

    private fun dismissAlarm() {
        val session = engine
        WatchAlarm.stop(this)
        if (session != null) {
            scope.launch { bridge.sendDismiss(session.sessionId) }
        }
        stopTracking(userStopped = true)
    }

    private fun snoozeAlarm() {
        val session = engine ?: return
        WatchAlarm.stop(this)
        WatchSession.setPhase(WatchPhase.TRACKING)
        val minutes = session.plan.snoozeMinutes
        scope.launch { bridge.sendSnooze(session.sessionId, minutes) }
        scope.launch {
            delay(minutes * 60_000L)
            if (engine != null && WatchSession.phase.value == WatchPhase.TRACKING) {
                WatchSession.setPhase(WatchPhase.ALARM)
                if (session.plan.wakeMode.usesWatch) {
                    WatchAlarm.start(this@SleepTrackingService, session.plan.vibrationIntensity)
                }
                if (session.plan.wakeMode.usesPhone) {
                    bridge.sendWakeNow(session.sessionId, "Snooze finished", 0)
                }
            }
        }
    }

    private fun stopTracking(userStopped: Boolean) {
        val session = engine
        loop?.cancel()
        loop = null
        collector?.stop()
        collector = null
        WatchAlarm.stop(this)
        releaseWakeLock()

        if (session != null) {
            val endedAt = System.currentTimeMillis()
            val summary = session.summary(endedAt)
            val updatedProfile = session.updatedProfile(WatchSession.profile)
            WatchSession.persistProfile(this, updatedProfile)
            scope.launch {
                bridge.publishSummary(summary)
                bridge.sendStateChanged(
                    session.sessionId,
                    tracking = false,
                    message = if (userStopped) EventSource.USER else EventSource.WATCH,
                )
            }
        }

        engine = null
        WatchSession.setPhase(WatchPhase.IDLE)
        WatchSession.setStatus(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Long enough for the longest plan plus slack; the service releases it on stop.
            acquire(MAX_SESSION_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun batteryPercent(): Int = runCatching {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }.getOrDefault(-1)

    private fun updateNotification(session: SleepSessionEngine, nowMillis: Long) {
        val notification = buildNotification(session.plan, session, nowMillis)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(WearApp.NOTIFICATION_TRACKING, notification)
    }

    private fun buildNotification(
        plan: AlarmPlan,
        session: SleepSessionEngine,
        nowMillis: Long,
    ): Notification {
        val status = WatchSession.status.value
        val wakeAt = session.projectedWakeMillis(nowMillis)
        val title = if (status?.alarmFired == true) {
            "Time to wake up"
        } else {
            "Wake around ${clock.format(wakeAt)}"
        }
        val detail = buildString {
            append(status?.stage?.label() ?: "Settling")
            append(" · cycle ")
            append(((status?.completedCycles ?: 0) + 1).coerceAtMost(plan.cycles))
            append('/')
            append(plan.cycles)
            status?.let { append(" · ${formatMinutes(it.asleepMinutes)} asleep") }
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SleepTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, WearApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(R.drawable.ic_moon, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        collector?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SleepTrackingService"
        private const val WAKE_LOCK_TAG = "SmartAlarm::SleepTracking"

        const val ACTION_START = "com.smartalarm.wear.START"
        const val ACTION_STOP = "com.smartalarm.wear.STOP"
        const val ACTION_DISMISS = "com.smartalarm.wear.DISMISS"
        const val ACTION_SNOOZE = "com.smartalarm.wear.SNOOZE"
        const val EXTRA_PLAN = "plan"
        const val EXTRA_SESSION_ID = "session_id"

        private const val LOOP_INTERVAL_MILLIS = 5_000L
        private const val STATUS_PUSH_INTERVAL_MILLIS = 60_000L
        private const val PREFLUSH_MILLIS = 5 * 60_000L
        private const val MAX_SESSION_MILLIS = 13 * 60 * 60 * 1000L

        private val clock = object {
            private val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
            fun format(millis: Long): String = formatter.format(java.util.Date(millis))
        }

        fun start(context: Context, plan: AlarmPlan, sessionId: String? = null) {
            val intent = Intent(context, SleepTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PLAN, com.smartalarm.core.protocol.WearJson.instance.encodeToString(plan))
            sessionId?.let { intent.putExtra(EXTRA_SESSION_ID, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, SleepTrackingService::class.java).setAction(action))
        }

        private fun Intent.planExtra(): AlarmPlan? =
            getStringExtra(EXTRA_PLAN)?.let { json ->
                runCatching {
                    com.smartalarm.core.protocol.WearJson.instance.decodeFromString<AlarmPlan>(json)
                }.getOrNull()
            }
    }
}
