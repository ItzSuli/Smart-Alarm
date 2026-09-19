package com.smartalarm.mobile.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.protocol.AlarmEvent
import com.smartalarm.core.protocol.EpochBatch
import com.smartalarm.core.protocol.StateChanged
import com.smartalarm.core.protocol.WearJson
import com.smartalarm.core.protocol.WearPaths
import com.smartalarm.mobile.SmartAlarmApp

/**
 * Everything the watch sends arrives here.
 *
 * The system starts this service on the watch's behalf even when the app is not running and
 * the phone is idle in the pocket, which is what makes "the watch decides, the phone rings"
 * work at six in the morning without the phone having to stay awake all night.
 */
class PhoneWearListenerService : WearableListenerService() {

    private val sessions get() = SmartAlarmApp.from(this).sessions

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.EVENT_WAKE_NOW -> event.decodeAlarm()?.let { sessions.onWatchWakeNow(it) }
            WearPaths.EVENT_DISMISS -> event.decodeAlarm()?.let { sessions.onWatchDismiss(it) }
            WearPaths.EVENT_SNOOZE -> event.decodeAlarm()?.let { sessions.onWatchSnooze(it) }
            WearPaths.EVENT_STATE_CHANGED ->
                runCatching { WearJson.decode<StateChanged>(event.data) }
                    .getOrNull()?.let { sessions.onWatchStateChanged(it) }

            else -> Log.d(TAG, "ignoring message on ${event.path}")
        }
        sessions.noteWatchContact()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path ?: return@forEach
            val payload = event.dataItem.data ?: return@forEach
            when {
                path == WearPaths.LIVE_STATUS ->
                    runCatching { WearJson.decode<LiveStatus>(payload) }
                        .getOrNull()?.let { sessions.onWatchStatus(it) }

                path.startsWith(WearPaths.EPOCH_BATCH) ->
                    runCatching { WearJson.decode<EpochBatch>(payload) }
                        .getOrNull()?.let { sessions.onEpochBatch(it) }

                path == WearPaths.SESSION_SUMMARY ->
                    runCatching { WearJson.decode<SessionSummary>(payload) }
                        .getOrNull()?.let { sessions.onWatchSummary(it) }
            }
        }
        sessions.noteWatchContact()
        events.release()
    }

    private fun MessageEvent.decodeAlarm(): AlarmEvent? =
        runCatching { WearJson.decode<AlarmEvent>(data) }
            .onFailure { Log.w(TAG, "could not read alarm event on $path", it) }
            .getOrNull()

    private companion object {
        const val TAG = "PhoneWearListener"
    }
}
