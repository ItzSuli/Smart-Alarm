package com.smartalarm.wear.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.smartalarm.wear.R
import com.smartalarm.wear.WearApp

/**
 * The watch's half of waking you up: the vibration motor.
 *
 * The pattern escalates. It opens with a couple of gentle taps, on the theory that the whole
 * point of a smart alarm is that it catches you in light sleep where a nudge is enough, and
 * only builds to something insistent if you sleep through the nudge. Escalating also makes the
 * watch-only mode genuinely usable in a shared bed, which is most of why that mode exists.
 */
object WatchAlarm {

    private var vibrator: Vibrator? = null

    fun start(context: Context, intensity: Int) {
        stop(context)
        val motor = vibratorOf(context) ?: return
        vibrator = motor

        val pattern = escalatingPattern(intensity)
        val amplitudes = escalatingAmplitudes(intensity, pattern.size)

        if (motor.hasAmplitudeControl()) {
            motor.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, REPEAT_FROM_INDEX))
        } else {
            motor.vibrate(VibrationEffect.createWaveform(pattern, REPEAT_FROM_INDEX))
        }
    }

    /**
     * A single pass of the real alarm pattern, for the system check.
     *
     * Deliberately the same waveform the alarm uses rather than a generic buzz: the point of
     * the check is to answer "would this actually wake me", and a different pattern would not.
     * It does not repeat, so it stops on its own if the wearer misses it.
     */
    fun testBuzz(context: Context, intensity: Int) {
        val motor = vibratorOf(context) ?: return
        vibrator = motor
        val pattern = escalatingPattern(intensity)
        val amplitudes = escalatingAmplitudes(intensity, pattern.size)
        if (motor.hasAmplitudeControl()) {
            motor.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, NO_REPEAT))
        } else {
            motor.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
        }
    }

    fun stop(context: Context) {
        (vibrator ?: vibratorOf(context))?.cancel()
        vibrator = null
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(WearApp.NOTIFICATION_ALARM)
    }

    /**
     * A heads-up notification, so the alarm is visible and dismissible even if the activity
     * cannot be launched — for instance while the watch is in theatre mode.
     */
    fun showNotification(context: Context, reason: String) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, WatchAlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WearApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Time to wake up")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(WearApp.NOTIFICATION_ALARM, notification)

        runCatching { context.startActivity(openIntent(context)) }
    }

    private fun openIntent(context: Context) =
        Intent(context, WatchAlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun vibratorOf(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /**
     * Timings in milliseconds, alternating off/on. Two soft double-taps, a pause, then longer
     * and longer buzzes; the loop restarts at the insistent part rather than the gentle one.
     */
    private fun escalatingPattern(intensity: Int): LongArray {
        val scale = when (intensity) {
            0 -> 0.7f
            2 -> 1.3f
            else -> 1.0f
        }
        fun t(ms: Long) = (ms * scale).toLong().coerceAtLeast(20L)
        return longArrayOf(
            0,
            // gentle opening
            t(120), t(180), t(120), t(1400),
            t(160), t(200), t(160), t(1400),
            // insistent from here; the waveform repeats into this section
            t(400), t(400), t(400), t(900),
            t(700), t(350), t(700), t(900),
            t(1000), t(500),
        )
    }

    private fun escalatingAmplitudes(intensity: Int, size: Int): IntArray {
        val peak = when (intensity) {
            0 -> 140
            2 -> 255
            else -> 200
        }
        val soft = (peak * 0.45f).toInt().coerceAtLeast(1)
        val medium = (peak * 0.75f).toInt().coerceAtLeast(1)
        val amplitudes = IntArray(size)
        for (index in 0 until size) {
            // Even indices are the silent gaps in a waveform pattern.
            amplitudes[index] = when {
                index % 2 == 0 -> 0
                index <= 4 -> soft
                index <= 8 -> medium
                else -> peak
            }
        }
        return amplitudes
    }

    /** Where the loop restarts: past the gentle opening, into the insistent section. */
    private const val REPEAT_FROM_INDEX = 9

    /** Play the waveform once and stop. */
    private const val NO_REPEAT = -1
}
