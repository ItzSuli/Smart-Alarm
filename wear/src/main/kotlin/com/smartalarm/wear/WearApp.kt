package com.smartalarm.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                getString(R.string.tracking_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tracking_channel_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.alarm_channel_description)
                enableVibration(false) // the alarm drives the motor itself
                setBypassDnd(true)
            }
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "sleep_tracking"
        const val CHANNEL_ALARM = "wake_alarm"
        const val NOTIFICATION_TRACKING = 1001
        const val NOTIFICATION_ALARM = 1002
    }
}
