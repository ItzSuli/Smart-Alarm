package com.smartalarm.wear.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.smartalarm.core.protocol.SessionRequest
import com.smartalarm.core.protocol.WearJson
import com.smartalarm.core.protocol.WearPaths

/**
 * Receives commands from the phone.
 *
 * The system starts this even when the watch app is not running, which is what lets "start
 * tracking" on the phone reach a watch whose app has not been opened in days.
 */
class WearDataListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.EVENT_START -> handleStart(event.data)
            WearPaths.EVENT_STOP -> SleepTrackingService.send(this, SleepTrackingService.ACTION_STOP)
            WearPaths.EVENT_DISMISS ->
                SleepTrackingService.send(this, SleepTrackingService.ACTION_DISMISS)

            WearPaths.EVENT_SNOOZE ->
                SleepTrackingService.send(this, SleepTrackingService.ACTION_SNOOZE)

            WearPaths.EVENT_PING -> WatchSession.setPhoneConnected(true)
            else -> Log.d(TAG, "ignoring message on ${event.path}")
        }
    }

    private fun handleStart(payload: ByteArray) {
        val request = runCatching { WearJson.decode<SessionRequest>(payload) }.getOrElse {
            Log.w(TAG, "could not read session request", it)
            return
        }
        WatchSession.setProfile(request.profile)
        WatchSession.updatePlan(this, request.plan)
        if (request.start) {
            SleepTrackingService.startFromBackground(this, request.plan, request.sessionId)
        } else {
            SleepTrackingService.send(this, SleepTrackingService.ACTION_STOP)
        }
    }

    override fun onCreate() {
        super.onCreate()
        WatchSession.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super.onStartCommand(intent, flags, startId)

    private companion object {
        const val TAG = "WearDataListener"
    }
}
