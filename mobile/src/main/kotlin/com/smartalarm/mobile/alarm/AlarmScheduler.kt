package com.smartalarm.mobile.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.smartalarm.mobile.MainActivity

/**
 * Parks an exact alarm at the hard deadline, as a backstop behind the watch.
 *
 * [AlarmManager.setAlarmClock] is the right primitive and the only one that really works here:
 * it is exempt from Doze, it survives the app being killed, it shows in the system's next-alarm
 * slot, and on Android 12 and up it does not need the exact-alarm permission the way
 * `setExactAndAllowWhileIdle` does. The cost is that it is visible as a system alarm, which for
 * an alarm clock is a feature.
 */
class AlarmScheduler(private val context: Context) {

    private val manager = context.getSystemService(AlarmManager::class.java)

    fun scheduleFallback(sessionId: String, triggerAtMillis: Long, reason: String) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        val operation = pendingIntent(REQUEST_FALLBACK, sessionId, reason, AlarmReceiver.ACTION_FALLBACK)
        val show = PendingIntent.getActivity(
            context, REQUEST_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), operation)
        }.onFailure { Log.e(TAG, "could not schedule fallback alarm", it) }
    }

    /**
     * Ring as soon as the system can get to it.
     *
     * Not a direct service start: from Android 12 an app in the background may not start a
     * foreground service, and the watch's "wake now" arrives on a listener service that Play
     * Services started, which carries no exemption. An exact alarm firing does carry one, so
     * the alarm is bounced through AlarmManager and the ring starts from inside the receiver.
     */
    fun fireNow(sessionId: String, reason: String) {
        val operation = pendingIntent(REQUEST_FIRE, sessionId, reason, AlarmReceiver.ACTION_FIRE)
        val triggerAt = System.currentTimeMillis() + IMMEDIATE_DELAY_MILLIS
        val show = PendingIntent.getActivity(
            context, REQUEST_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
        }.onFailure {
            Log.e(TAG, "could not schedule the immediate alarm", it)
            runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        }
    }

    fun scheduleSnooze(sessionId: String, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        val operation = pendingIntent(REQUEST_SNOOZE, sessionId, "Snooze finished", AlarmReceiver.ACTION_SNOOZE_END)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        }.onFailure { Log.e(TAG, "could not schedule snooze", it) }
    }

    fun cancelAll() {
        listOf(
            REQUEST_FALLBACK to AlarmReceiver.ACTION_FALLBACK,
            REQUEST_SNOOZE to AlarmReceiver.ACTION_SNOOZE_END,
            REQUEST_FIRE to AlarmReceiver.ACTION_FIRE,
        ).forEach { (request, action) ->
            manager.cancel(pendingIntent(request, "", "", action))
        }
    }

    private fun pendingIntent(
        requestCode: Int,
        sessionId: String,
        reason: String,
        action: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmReceiver.EXTRA_SESSION_ID, sessionId)
            .putExtra(AlarmReceiver.EXTRA_REASON, reason),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val TAG = "AlarmScheduler"
        const val REQUEST_FALLBACK = 100
        const val REQUEST_SNOOZE = 101
        const val REQUEST_SHOW = 102
        const val REQUEST_FIRE = 103

        /** Long enough for AlarmManager to treat it as a real alarm, short enough to be instant. */
        const val IMMEDIATE_DELAY_MILLIS = 200L
    }
}
