package com.smartalarm.mobile.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smartalarm.mobile.R
import com.smartalarm.mobile.SmartAlarmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Makes the noise.
 *
 * A foreground service rather than something driven from the activity, because the activity can
 * be dismissed, rotated away or never launched at all if the screen is off and the device is
 * locked — and the alarm has to keep ringing regardless.
 *
 * The volume ramps rather than starting at full blast. Waking out of light sleep, which is the
 * whole point of the app, takes very little; starting quiet and climbing over about a minute
 * means most mornings end before the loud part.
 */
class AlarmRingService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var rampJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                SmartAlarmApp.from(this).sessions.snoozeAlarm()
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_DISMISS -> {
                SmartAlarmApp.from(this).sessions.dismissAlarm()
                stopEverything()
                return START_NOT_STICKY
            }
        }

        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty()
        val quality = intent?.getIntExtra(EXTRA_QUALITY, 0) ?: 0
        startForeground(SmartAlarmApp.NOTIFICATION_ALARM, buildNotification(reason, quality))
        acquireWakeLock()
        startRinging()
        launchFullScreen(reason, quality)
        return START_STICKY
    }

    private fun startRinging() {
        val plan = SmartAlarmApp.from(this).settings.plan.value
        startAudio(plan.alarmToneUri?.let(Uri::parse), plan.gentleVolumeRamp)
        startVibration(plan.vibrationIntensity)
    }

    private fun startAudio(toneUri: Uri?, ramp: Boolean) {
        val uri = toneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                prepare()
                val start = if (ramp) INITIAL_VOLUME else 1f
                setVolume(start, start)
                start()
            }
        }.onFailure { Log.e(TAG, "could not start the alarm tone", it) }

        // Make sure the alarm stream is actually audible; a muted alarm stream is the one way
        // an alarm app can silently fail.
        runCatching {
            val audio = getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < max / 3) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max / 2, 0)
            }
        }

        if (ramp) {
            rampJob = scope.launch {
                var volume = INITIAL_VOLUME
                while (volume < 1f) {
                    delay(RAMP_STEP_MILLIS)
                    volume = (volume + RAMP_STEP).coerceAtMost(1f)
                    runCatching { player?.setVolume(volume, volume) }
                }
            }
        }
    }

    private fun startVibration(intensity: Int) {
        val motor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = motor

        val timings = longArrayOf(0, 500, 700, 500, 700, 900, 900)
        val peak = when (intensity) {
            0 -> 120
            2 -> 255
            else -> 190
        }
        val amplitudes = intArrayOf(0, peak / 2, 0, peak * 3 / 4, 0, peak, 0)
        runCatching {
            if (motor.hasAmplitudeControl()) {
                motor.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 1))
            } else {
                motor.vibrate(VibrationEffect.createWaveform(timings, 1))
            }
        }
    }

    private fun launchFullScreen(reason: String, quality: Int) {
        runCatching {
            startActivity(
                AlarmRingActivity.intent(this, reason, quality)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }.onFailure { Log.w(TAG, "could not launch the alarm screen; notification will carry it", it) }
    }

    private fun buildNotification(reason: String, quality: Int): Notification {
        val full = PendingIntent.getActivity(
            this, 0,
            AlarmRingActivity.intent(this, reason, quality)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getService(
            this, 1, Intent(this, AlarmRingService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = PendingIntent.getService(
            this, 2, Intent(this, AlarmRingService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, SmartAlarmApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Good morning")
            .setContentText(reason.ifEmpty { "Time to wake up" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(R.drawable.ic_alarm, "Snooze", snooze)
            .addAction(R.drawable.ic_alarm, "Dismiss", dismiss)
            .build()
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "SmartAlarm::Ringing",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_RING_MILLIS)
        }
    }

    private fun stopEverything() {
        rampJob?.cancel()
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingService"
        const val ACTION_STOP = "com.smartalarm.RING_STOP"
        const val ACTION_SNOOZE = "com.smartalarm.RING_SNOOZE"
        const val ACTION_DISMISS = "com.smartalarm.RING_DISMISS"
        const val EXTRA_REASON = "reason"
        const val EXTRA_QUALITY = "quality"

        private const val INITIAL_VOLUME = 0.12f
        private const val RAMP_STEP = 0.06f
        private const val RAMP_STEP_MILLIS = 3_000L
        private const val MAX_RING_MILLIS = 15 * 60 * 1000L

        /**
         * Only ever called from an alarm broadcast or a visible activity. Starting a foreground
         * service from anywhere else is refused on Android 12 and up, so the caller is
         * responsible for being somewhere that carries an exemption.
         */
        fun start(context: Context, reason: String, quality: Int) {
            val intent = Intent(context, AlarmRingService::class.java)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_QUALITY, quality)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "the system refused to start the alarm service", it) }
        }

        fun stop(context: Context) {
            // Delivering to an already-running foreground service is allowed, but if it has
            // already stopped itself this throws rather than doing nothing.
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP)
                )
            }.onFailure { Log.d(TAG, "alarm service was not running", it) }
        }
    }
}
