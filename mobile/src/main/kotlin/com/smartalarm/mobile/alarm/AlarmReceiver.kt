package com.smartalarm.mobile.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartalarm.mobile.SmartAlarmApp

/** Wakes the phone at the hard deadline, or when a snooze runs out. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val sessions = SmartAlarmApp.from(context).sessions

        when (intent.action) {
            // The watch decided; ring regardless of how quiet it has been.
            ACTION_FIRE -> sessions.onRingNow(sessionId, reason.ifEmpty { "Time to wake up" })
            // The deadline arrived on its own; ring only if the wake mode or the watch's silence
            // calls for it.
            ACTION_FALLBACK -> sessions.onFallbackAlarm(sessionId, reason.ifEmpty { "Time to wake up" })
            ACTION_SNOOZE_END -> sessions.onRingNow(sessionId, "Snooze finished")
        }
    }

    companion object {
        const val ACTION_FIRE = "com.smartalarm.FIRE_ALARM"
        const val ACTION_FALLBACK = "com.smartalarm.FALLBACK_ALARM"
        const val ACTION_SNOOZE_END = "com.smartalarm.SNOOZE_END"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REASON = "reason"
    }
}
