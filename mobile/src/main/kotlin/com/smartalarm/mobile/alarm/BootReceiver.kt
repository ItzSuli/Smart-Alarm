package com.smartalarm.mobile.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartalarm.mobile.SmartAlarmApp

/**
 * Exact alarms do not survive a reboot, so a night in progress has to re-arm its backstop.
 * Restoring the session re-schedules it as a side effect of replaying the epochs.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                SmartAlarmApp.from(context).sessions.restore()
        }
    }
}
