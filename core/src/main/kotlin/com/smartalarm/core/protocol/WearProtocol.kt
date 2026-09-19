package com.smartalarm.core.protocol

import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.model.SleepProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The wire contract between the phone and the watch.
 *
 * Everything travels over the Wearable Data Layer as UTF-8 JSON. Two transports are used and
 * the distinction matters:
 *
 *  - **MessageClient** for events (start, stop, wake now, dismiss). Fire and forget, low
 *    latency, but silently dropped when the peer is unreachable.
 *  - **DataClient** for state (the plan, the live status, the night's summary). Replicated and
 *    persistent, so a phone that was out of Bluetooth range for an hour receives the latest
 *    value the moment it reconnects.
 *
 * Epoch batches go over DataClient too, keyed by batch sequence, so a walk to the kitchen
 * without the phone does not punch a hole in the night's data.
 */
object WearPaths {
    private const val ROOT = "/smartalarm"

    // --- DataClient items (replicated state) ---
    /** Phone -> watch: the plan for tonight and the sleeper's profile. */
    const val SESSION_REQUEST = "$ROOT/session"

    /** Watch -> phone: the live status snapshot, overwritten each epoch. */
    const val LIVE_STATUS = "$ROOT/status"

    /** Watch -> phone: a batch of epochs. The sequence number is appended to the path. */
    const val EPOCH_BATCH = "$ROOT/epochs"

    /** Watch -> phone: the finished night. */
    const val SESSION_SUMMARY = "$ROOT/summary"

    // --- MessageClient events ---
    /** Either direction: the alarm should sound now. */
    const val EVENT_WAKE_NOW = "$ROOT/event/wake"

    /** Either direction: the alarm was dismissed. */
    const val EVENT_DISMISS = "$ROOT/event/dismiss"

    /** Either direction: the alarm was snoozed. */
    const val EVENT_SNOOZE = "$ROOT/event/snooze"

    /** Phone -> watch: start tracking now. */
    const val EVENT_START = "$ROOT/event/start"

    /** Phone -> watch: stop tracking. */
    const val EVENT_STOP = "$ROOT/event/stop"

    /** Watch -> phone: tracking really started / really stopped. */
    const val EVENT_STATE_CHANGED = "$ROOT/event/state"

    /** Either direction: are you there? Used by the connection indicator. */
    const val EVENT_PING = "$ROOT/event/ping"

    /** Reply to [EVENT_PING]. */
    const val EVENT_PONG = "$ROOT/event/pong"

    /** The data item key every payload is stored under. */
    const val KEY_PAYLOAD = "payload"

    /** Monotonic counter so DataClient always sees the item as changed. */
    const val KEY_REVISION = "revision"

    fun epochBatchPath(sequence: Long): String = "$EPOCH_BATCH/$sequence"
}

/** Phone -> watch: everything the watch needs to run the night on its own. */
@Serializable
data class SessionRequest(
    val sessionId: String,
    val plan: AlarmPlan,
    val profile: SleepProfile = SleepProfile(),
    /** Wall-clock time the phone wants tracking to have started. */
    val startAtMillis: Long,
    val issuedAtMillis: Long,
    val start: Boolean = true,
)

/** Watch -> phone: a run of epochs. */
@Serializable
data class EpochBatch(
    val sessionId: String,
    val sequence: Long,
    val epochs: List<EpochFeatures>,
)

/** Either direction: fire, dismiss or snooze the alarm. */
@Serializable
data class AlarmEvent(
    val sessionId: String,
    val atMillis: Long,
    /** Which device decided. */
    val source: String,
    /** Human-readable justification, shown in the app and stored with the night. */
    val reason: String = "",
    val wakeQualityPercent: Int = 0,
    val snoozeMinutes: Int = 0,
)

/** Watch -> phone: tracking started or stopped. */
@Serializable
data class StateChanged(
    val sessionId: String,
    val tracking: Boolean,
    val atMillis: Long,
    val message: String = "",
)

/** Serialisation shared by both apps. Lenient so an older peer never crashes a newer one. */
object WearJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encodeSessionRequest(value: SessionRequest): ByteArray = encode(value)
    fun decodeSessionRequest(bytes: ByteArray): SessionRequest = decode(bytes)

    fun encodeStatus(value: LiveStatus): ByteArray = encode(value)
    fun decodeStatus(bytes: ByteArray): LiveStatus = decode(bytes)

    fun encodeEpochBatch(value: EpochBatch): ByteArray = encode(value)
    fun decodeEpochBatch(bytes: ByteArray): EpochBatch = decode(bytes)

    fun encodeSummary(value: SessionSummary): ByteArray = encode(value)
    fun decodeSummary(bytes: ByteArray): SessionSummary = decode(bytes)

    fun encodeAlarmEvent(value: AlarmEvent): ByteArray = encode(value)
    fun decodeAlarmEvent(bytes: ByteArray): AlarmEvent = decode(bytes)

    fun encodeStateChanged(value: StateChanged): ByteArray = encode(value)
    fun decodeStateChanged(bytes: ByteArray): StateChanged = decode(bytes)

    inline fun <reified T> encode(value: T): ByteArray =
        instance.encodeToString(value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decode(bytes: ByteArray): T =
        instance.decodeFromString(String(bytes, Charsets.UTF_8))
}

/** Which device an [AlarmEvent] came from. */
object EventSource {
    const val WATCH = "watch"
    const val PHONE = "phone"
    const val USER = "user"
    const val FALLBACK = "fallback"
}
