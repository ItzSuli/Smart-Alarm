package com.smartalarm.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.smartalarm.mobile.data.SessionRepository
import com.smartalarm.mobile.data.SettingsStore
import com.smartalarm.mobile.session.SessionManager

class SmartAlarmApp : Application() {

    lateinit var repository: SessionRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var sessions: SessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = SessionRepository(this)
        settings = SettingsStore(this)
        sessions = SessionManager(this, repository, settings)
        createChannels()
        sessions.restore()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.alarm_channel_description)
                setBypassDnd(true)
                // The ring service drives the audio and the motor itself, so the notification
                // must stay silent or the two fight each other.
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION,
                getString(R.string.session_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.session_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ALARM = "wake_alarm"
        const val CHANNEL_SESSION = "sleep_session"
        const val NOTIFICATION_ALARM = 2001
        const val NOTIFICATION_SESSION = 2002

        /**
         * Receivers and services are handed a plain [android.content.Context], and the
         * alternative to this is threading a dependency graph through every entry point for
         * one Application-scoped object.
         */
        @Volatile
        private var instance: SmartAlarmApp? = null

        fun from(context: android.content.Context): SmartAlarmApp =
            instance ?: (context.applicationContext as SmartAlarmApp).also { instance = it }
    }
}
