package com.smartalarm.wear.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SelfTestRequest
import com.smartalarm.core.protocol.WearJson
import kotlinx.serialization.decodeFromString

/**
 * Starts sleep tracking on behalf of the phone.
 *
 * The phone's "start" arrives on [WearDataListenerService], which Google Play Services starts
 * in the background — and from Android 12 an app in the background may not start a foreground
 * service. Starting the tracking service straight from that listener throws, which would mean
 * pressing Start on the phone quietly did nothing.
 *
 * An exact alarm firing does put the app in the temporary allowlist, so the listener schedules
 * one for a fraction of a second ahead and the real start happens here, inside the exemption.
 */
class WatchCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_TRACKING -> startTracking(context, intent)
            ACTION_START_SELF_TEST -> startSelfTest(context, intent)
        }
    }

    private fun startSelfTest(context: Context, intent: Intent) {
        val request = intent.getStringExtra(EXTRA_SELF_TEST)
            ?.let { runCatching { WearJson.instance.decodeFromString<SelfTestRequest>(it) }.getOrNull() }
            ?: return
        Log.i(TAG, "running the system check on the phone's behalf")
        SelfTestService.start(context, request)
    }

    private fun startTracking(context: Context, intent: Intent) {
        val plan = intent.getStringExtra(EXTRA_PLAN)
            ?.let { runCatching { WearJson.instance.decodeFromString<AlarmPlan>(it) }.getOrNull() }
            ?: WatchSession.plan.value
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)

        Log.i(TAG, "starting tracking for session $sessionId on the phone's behalf")
        SleepTrackingService.start(context, plan, sessionId)
    }

    companion object {
        const val ACTION_START_TRACKING = "com.smartalarm.wear.START_TRACKING"
        const val ACTION_START_SELF_TEST = "com.smartalarm.wear.START_SELF_TEST"
        const val EXTRA_PLAN = "plan"
        const val EXTRA_SELF_TEST = "self_test"
        const val EXTRA_SESSION_ID = "session_id"
        private const val TAG = "WatchCommandReceiver"
    }
}
