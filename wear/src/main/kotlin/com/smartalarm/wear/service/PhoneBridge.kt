package com.smartalarm.wear.service

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.protocol.AlarmEvent
import com.smartalarm.core.protocol.EpochBatch
import com.smartalarm.core.protocol.EventSource
import com.smartalarm.core.protocol.StateChanged
import com.smartalarm.core.protocol.WearJson
import com.smartalarm.core.protocol.WearPaths
import kotlinx.coroutines.tasks.await

/**
 * Sends the night to the phone.
 *
 * Two transports, chosen deliberately:
 *
 * **DataClient** for anything the phone must eventually see — the live status, the epoch
 * stream, the finished summary. Data items are replicated and survive a disconnection, so a
 * phone left downstairs picks up everything it missed the moment it comes back into range.
 *
 * **MessageClient** for the alarm events, where latency is what matters and a message that
 * cannot be delivered is no loss, because the watch has already buzzed and the phone has its
 * own fallback alarm scheduled.
 */
class PhoneBridge(private val context: Context) {

    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    private var revision = 0L
    private var epochSequence = 0L

    suspend fun isPhoneConnected(): Boolean = runCatching {
        nodeClient.connectedNodes.await().isNotEmpty()
    }.getOrDefault(false)

    suspend fun publishStatus(status: LiveStatus) {
        putData(WearPaths.LIVE_STATUS, WearJson.encodeStatus(status))
    }

    /**
     * Publish a batch of epochs. Each batch gets its own data-item path so a batch the phone
     * has not collected yet is never overwritten by the next one.
     */
    suspend fun publishEpochs(sessionId: String, epochs: List<EpochFeatures>) {
        if (epochs.isEmpty()) return
        val sequence = epochSequence++
        putData(
            WearPaths.epochBatchPath(sequence),
            WearJson.encodeEpochBatch(EpochBatch(sessionId, sequence, epochs)),
        )
    }

    suspend fun publishSummary(summary: SessionSummary) {
        putData(WearPaths.SESSION_SUMMARY, WearJson.encodeSummary(summary))
    }

    suspend fun sendWakeNow(sessionId: String, reason: String, wakeQuality: Int) {
        broadcast(
            WearPaths.EVENT_WAKE_NOW,
            WearJson.encodeAlarmEvent(
                AlarmEvent(
                    sessionId = sessionId,
                    atMillis = System.currentTimeMillis(),
                    source = EventSource.WATCH,
                    reason = reason,
                    wakeQualityPercent = wakeQuality,
                )
            ),
        )
    }

    suspend fun sendDismiss(sessionId: String) {
        broadcast(
            WearPaths.EVENT_DISMISS,
            WearJson.encodeAlarmEvent(
                AlarmEvent(sessionId, System.currentTimeMillis(), EventSource.WATCH)
            ),
        )
    }

    suspend fun sendSnooze(sessionId: String, minutes: Int) {
        broadcast(
            WearPaths.EVENT_SNOOZE,
            WearJson.encodeAlarmEvent(
                AlarmEvent(
                    sessionId = sessionId,
                    atMillis = System.currentTimeMillis(),
                    source = EventSource.WATCH,
                    snoozeMinutes = minutes,
                )
            ),
        )
    }

    suspend fun sendStateChanged(sessionId: String, tracking: Boolean, message: String = "") {
        broadcast(
            WearPaths.EVENT_STATE_CHANGED,
            WearJson.encodeStateChanged(
                StateChanged(sessionId, tracking, System.currentTimeMillis(), message)
            ),
        )
    }

    private suspend fun putData(path: String, payload: ByteArray) {
        runCatching {
            val request = PutDataRequest.create(path).apply {
                setUrgent()
                data = payload
            }
            dataClient.putDataItem(request).await()
        }.onFailure { Log.w(TAG, "could not publish $path", it) }
        revision++
    }

    private suspend fun broadcast(path: String, payload: ByteArray) {
        runCatching {
            val nodes = nodeClient.connectedNodes.await()
            nodes.forEach { node ->
                runCatching { messageClient.sendMessage(node.id, path, payload).await() }
                    .onFailure { Log.w(TAG, "message $path to ${node.displayName} failed", it) }
            }
        }.onFailure { Log.w(TAG, "could not list nodes for $path", it) }
    }

    private companion object {
        const val TAG = "PhoneBridge"
    }
}
