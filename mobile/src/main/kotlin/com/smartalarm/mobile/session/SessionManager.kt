package com.smartalarm.mobile.session

import android.content.Context
import android.util.Log
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.protocol.AlarmEvent
import com.smartalarm.core.protocol.EpochBatch
import com.smartalarm.core.protocol.StateChanged
import com.smartalarm.core.sleep.SleepSessionEngine
import com.smartalarm.mobile.alarm.AlarmScheduler
import com.smartalarm.mobile.alarm.AlarmRingService
import com.smartalarm.mobile.data.SessionRepository
import com.smartalarm.mobile.data.SettingsStore
import com.smartalarm.mobile.wear.WatchBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** What the phone believes is happening tonight. */
enum class PhonePhase { IDLE, TRACKING, ALARM }

/**
 * The phone's view of the night.
 *
 * The watch owns the sensors and makes the wake decision. The phone replays the same epoch
 * stream through the same [SleepSessionEngine] so its display, its history and its fallback
 * alarm all agree with what the watch decided — and so that a night still renders correctly if
 * the watch's summary never arrives.
 *
 * The phone never overrules the watch. Its one piece of independent authority is the backstop
 * alarm: if the watch stops reporting for [WATCH_SILENCE_TIMEOUT_MILLIS], the phone assumes the
 * watch is dead and rings on its own rather than letting the morning pass in silence.
 */
class SessionManager(
    private val context: Context,
    private val repository: SessionRepository,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bridge = WatchBridge(context)
    private val scheduler = AlarmScheduler(context)

    private var engine: SleepSessionEngine? = null

    private val _phase = MutableStateFlow(PhonePhase.IDLE)
    val phase: StateFlow<PhonePhase> = _phase.asStateFlow()

    private val _status = MutableStateFlow<LiveStatus?>(null)
    val status: StateFlow<LiveStatus?> = _status.asStateFlow()

    /** Live hypnogram for the night in progress, derived from the phone's own replay. */
    private val _hypnogram = MutableStateFlow<List<SleepStage>>(emptyList())
    val hypnogram: StateFlow<List<SleepStage>> = _hypnogram.asStateFlow()

    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _watchNames = MutableStateFlow<List<String>>(emptyList())
    val watchNames: StateFlow<List<String>> = _watchNames.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    val nights: StateFlow<List<SessionSummary>> get() = repository.nights
    val plan: StateFlow<AlarmPlan> get() = settings.plan

    /** Pick a night back up after the process was killed. */
    fun restore() {
        val sessionId = settings.activeSessionId
        if (sessionId == null) {
            repository.pruneActive(null)
            refreshWatchConnection()
            return
        }
        val epochs = repository.readEpochs(sessionId)
        val startedAt = settings.activeSessionStartedMillis.takeIf { it > 0 }
            ?: epochs.firstOrNull()?.startMillis
            ?: System.currentTimeMillis()

        val session = SleepSessionEngine(sessionId, settings.plan.value, settings.profile.value, startedAt)
        epochs.forEach { session.onEpoch(it) }
        engine = session
        _phase.value = PhonePhase.TRACKING
        publish(session.tick(System.currentTimeMillis()))
        refreshWatchConnection()
        Log.i(TAG, "restored session $sessionId with ${epochs.size} epochs")
    }

    // ---------------------------------------------------------------- user actions

    fun startNight(plan: AlarmPlan) {
        if (engine != null) return
        val sessionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        settings.updatePlan { plan }
        settings.activeSessionId = sessionId
        settings.activeSessionStartedMillis = startedAt
        settings.lastWatchContactMillis = startedAt
        repository.pruneActive(sessionId)

        val session = SleepSessionEngine(sessionId, plan, settings.profile.value, startedAt)
        engine = session
        _phase.value = PhonePhase.TRACKING
        publish(session.currentStatus(startedAt))

        scope.launch {
            val delivered = bridge.startSession(sessionId, plan, settings.profile.value, startedAt)
            _lastMessage.value = if (delivered) {
                null
            } else {
                "Your watch is not in range. Start the night on the watch too, or bring it closer."
            }
            refreshWatchConnection()
        }
        rescheduleFallback()
    }

    fun stopNight() {
        val session = engine ?: return
        AlarmRingService.stop(context)
        scheduler.cancelAll()
        scope.launch { bridge.stopSession(session.sessionId) }
        finish(session.summary(System.currentTimeMillis()))
    }

    fun dismissAlarm() {
        val session = engine
        AlarmRingService.stop(context)
        scheduler.cancelAll()
        if (session != null) {
            scope.launch { bridge.dismissAlarm(session.sessionId) }
            finish(session.summary(System.currentTimeMillis()))
        } else {
            _phase.value = PhonePhase.IDLE
        }
    }

    fun snoozeAlarm() {
        val session = engine ?: return
        val minutes = session.plan.snoozeMinutes
        AlarmRingService.stop(context)
        _phase.value = PhonePhase.TRACKING
        scheduler.scheduleSnooze(session.sessionId, minutes)
        scope.launch { bridge.snoozeAlarm(session.sessionId, minutes) }
    }

    fun updatePlan(transform: (AlarmPlan) -> AlarmPlan) {
        settings.updatePlan(transform)
        // A plan changed mid-night still has to reach the watch, or the two disagree.
        val session = engine ?: return
        scope.launch {
            bridge.startSession(
                session.sessionId, settings.plan.value, settings.profile.value, session.startedAtMillis,
            )
        }
    }

    fun refreshWatchConnection() {
        scope.launch {
            val names = bridge.connectedWatches()
            _watchNames.value = names
            _watchConnected.value = names.isNotEmpty()
        }
    }

    fun dismissMessage() {
        _lastMessage.value = null
    }

    fun deleteNight(sessionId: String) {
        scope.launch { repository.deleteNight(sessionId) }
    }

    // ------------------------------------------------------------- watch callbacks

    fun noteWatchContact() {
        settings.lastWatchContactMillis = System.currentTimeMillis()
        _watchConnected.value = true
    }

    fun onEpochBatch(batch: EpochBatch) {
        val session = engine
        if (session == null || session.sessionId != batch.sessionId) {
            // The watch started a night the phone does not know about; adopt it.
            adoptWatchSession(batch)
            return
        }
        repository.appendEpochs(batch.sessionId, batch.epochs)
        batch.epochs.forEach { session.onEpoch(it) }
        publish(session.tick(System.currentTimeMillis()))
        rescheduleFallback()
    }

    fun onWatchStatus(status: LiveStatus) {
        // The watch's own numbers are authoritative for the alarm; the phone's replay is what
        // draws the hypnogram. Showing the watch's stage keeps the two screens in step even
        // when a batch of epochs is still in flight.
        if (engine?.sessionId == status.sessionId) {
            _status.value = status
        } else if (engine == null && status.tracking) {
            _status.value = status
            _phase.value = PhonePhase.TRACKING
        }
        noteWatchContact()
    }

    fun onWatchWakeNow(event: AlarmEvent) {
        val session = engine
        if (session != null && session.sessionId != event.sessionId) return
        val wakeMode = session?.plan?.wakeMode ?: settings.plan.value.wakeMode
        _phase.value = PhonePhase.ALARM
        if (wakeMode.usesPhone) {
            AlarmRingService.start(context, event.reason, event.wakeQualityPercent)
        }
        scheduler.cancelAll()
    }

    fun onWatchDismiss(event: AlarmEvent) {
        val session = engine ?: run {
            _phase.value = PhonePhase.IDLE
            AlarmRingService.stop(context)
            return
        }
        if (session.sessionId != event.sessionId) return
        AlarmRingService.stop(context)
        scheduler.cancelAll()
        finish(session.summary(event.atMillis))
    }

    fun onWatchSnooze(event: AlarmEvent) {
        if (engine?.sessionId != event.sessionId) return
        AlarmRingService.stop(context)
        _phase.value = PhonePhase.TRACKING
        scheduler.scheduleSnooze(event.sessionId, event.snoozeMinutes)
    }

    fun onWatchStateChanged(state: StateChanged) {
        noteWatchContact()
        if (!state.tracking && engine?.sessionId == state.sessionId) {
            // The watch stopped; wait for its summary rather than racing it.
            Log.i(TAG, "watch stopped tracking ${state.sessionId}")
        }
    }

    /** The watch's own summary wins, because it saw every epoch including any the phone missed. */
    fun onWatchSummary(summary: SessionSummary) {
        scope.launch {
            repository.saveNight(summary)
            settings.updateProfile(
                engine?.updatedProfile(settings.profile.value) ?: settings.profile.value
            )
            clearActive(summary.sessionId)
        }
    }

    /**
     * The backstop. Called from the fallback alarm: if the watch has been silent long enough
     * that it is probably dead, ring anyway.
     */
    fun onFallbackAlarm(sessionId: String, reason: String) {
        val session = engine
        if (session != null && session.sessionId != sessionId) return
        val plan = session?.plan ?: settings.plan.value
        val silentFor = System.currentTimeMillis() - settings.lastWatchContactMillis
        val watchIsSilent = silentFor > WATCH_SILENCE_TIMEOUT_MILLIS

        val shouldRing = plan.wakeMode.usesPhone || (plan.phoneBackupIfWatchSilent && watchIsSilent)
        if (!shouldRing) {
            Log.i(TAG, "fallback declined: watch mode and the watch is still reporting")
            return
        }
        _phase.value = PhonePhase.ALARM
        AlarmRingService.start(
            context,
            if (watchIsSilent) "Watch stopped reporting — backup alarm" else reason,
            session?.lastDecision?.wakeQualityPercent ?: 0,
        )
    }

    // -------------------------------------------------------------------- internals

    private fun adoptWatchSession(batch: EpochBatch) {
        val startedAt = batch.epochs.firstOrNull()?.startMillis ?: System.currentTimeMillis()
        settings.activeSessionId = batch.sessionId
        settings.activeSessionStartedMillis = startedAt
        repository.pruneActive(batch.sessionId)
        repository.appendEpochs(batch.sessionId, batch.epochs)

        val session = SleepSessionEngine(
            batch.sessionId, settings.plan.value, settings.profile.value, startedAt,
        )
        repository.readEpochs(batch.sessionId).forEach { session.onEpoch(it) }
        engine = session
        _phase.value = PhonePhase.TRACKING
        publish(session.tick(System.currentTimeMillis()))
        rescheduleFallback()
        Log.i(TAG, "adopted watch-started session ${batch.sessionId}")
    }

    private fun publish(status: LiveStatus) {
        _status.value = status
        _hypnogram.value = engine?.stagedEpochs?.map { it.stage } ?: emptyList()
    }

    /**
     * Keep an exact alarm parked at the hard deadline.
     *
     * This is what makes the design safe: the watch normally rings first and cancels this, but
     * if it never does — flat battery, Bluetooth gone, app killed — the phone has an
     * `AlarmClock` alarm already scheduled that survives Doze and fires regardless.
     */
    private fun rescheduleFallback() {
        val session = engine ?: return
        val decision = session.lastDecision
        val deadline = decision.windowEndMillis.takeIf { it > System.currentTimeMillis() }
            ?: session.projectedWakeMillis(System.currentTimeMillis())
        scheduler.scheduleFallback(session.sessionId, deadline, decision.reason)
    }

    private fun finish(summary: SessionSummary) {
        scope.launch {
            repository.saveNight(summary)
            engine?.let { settings.updateProfile(it.updatedProfile(settings.profile.value)) }
            clearActive(summary.sessionId)
        }
    }

    private fun clearActive(sessionId: String) {
        repository.clearActive(sessionId)
        settings.activeSessionId = null
        settings.activeSessionStartedMillis = 0L
        engine = null
        _phase.value = PhonePhase.IDLE
        _status.value = null
        _hypnogram.value = emptyList()
    }

    companion object {
        private const val TAG = "SessionManager"

        /**
         * How long the watch may go quiet before the phone assumes it is gone. The watch
         * publishes a status every minute, so ten minutes of silence is well past noise.
         */
        const val WATCH_SILENCE_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}
