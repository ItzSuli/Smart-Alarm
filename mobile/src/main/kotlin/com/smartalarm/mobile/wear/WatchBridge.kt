package com.smartalarm.mobile.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.core.protocol.AlarmEvent
import com.smartalarm.core.protocol.EventSource
import com.smartalarm.core.protocol.SessionRequest
import com.smartalarm.core.protocol.WearJson
import com.smartalarm.core.protocol.WearPaths
import kotlinx.coroutines.tasks.await

/** The phone's half of the link: sends commands to the watch and reports whether it is there. */
class WatchBridge(private val context: Context) {

    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    suspend fun connectedWatches(): List<String> = runCatching {
        nodeClient.connectedNodes.await().map { it.displayName }
    }.getOrDefault(emptyList())

    suspend fun isWatchConnected(): Boolean = connectedWatches().isNotEmpty()

    /**
     * Ask the watch to start tracking.
     *
     * The request goes out twice on purpose. The message wakes a watch that is reachable right
     * now; the data item is replicated and will be picked up whenever the watch next connects,
     * which covers a watch that is charging in the other room when you press start.
     */
    suspend fun startSession(
        sessionId: String,
        plan: AlarmPlan,
        profile: SleepProfile,
        startAtMillis: Long,
    ): Boolean {
        val request = SessionRequest(
            sessionId = sessionId,
            plan = plan,
            profile = profile,
            startAtMillis = startAtMillis,
            issuedAtMillis = System.currentTimeMillis(),
            start = true,
        )
        val payload = WearJson.encodeSessionRequest(request)
        putData(WearPaths.SESSION_REQUEST, payload)
        return broadcast(WearPaths.EVENT_START, payload)
    }

    suspend fun stopSession(sessionId: String): Boolean =
        broadcast(
            WearPaths.EVENT_STOP,
            WearJson.encodeAlarmEvent(
                AlarmEvent(sessionId, System.currentTimeMillis(), EventSource.PHONE)
            ),
        )

    suspend fun dismissAlarm(sessionId: String): Boolean =
        broadcast(
            WearPaths.EVENT_DISMISS,
            WearJson.encodeAlarmEvent(
                AlarmEvent(sessionId, System.currentTimeMillis(), EventSource.PHONE)
            ),
        )

    suspend fun snoozeAlarm(sessionId: String, minutes: Int): Boolean =
        broadcast(
            WearPaths.EVENT_SNOOZE,
            WearJson.encodeAlarmEvent(
                AlarmEvent(
                    sessionId = sessionId,
                    atMillis = System.currentTimeMillis(),
                    source = EventSource.PHONE,
                    snoozeMinutes = minutes,
                )
            ),
        )

    private suspend fun putData(path: String, payload: ByteArray) {
        runCatching {
            dataClient.putDataItem(
                PutDataRequest.create(path).apply {
                    setUrgent()
                    data = payload
                }
            ).await()
        }.onFailure { Log.w(TAG, "could not publish $path", it) }
    }

    private suspend fun broadcast(path: String, payload: ByteArray): Boolean = runCatching {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) return false
        var delivered = false
        nodes.forEach { node ->
            runCatching { messageClient.sendMessage(node.id, path, payload).await() }
                .onSuccess { delivered = true }
                .onFailure { Log.w(TAG, "message $path to ${node.displayName} failed", it) }
        }
        delivered
    }.getOrDefault(false)

    private companion object {
        const val TAG = "WatchBridge"
    }
}
