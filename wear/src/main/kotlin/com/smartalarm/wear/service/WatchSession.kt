package com.smartalarm.wear.service

import android.content.Context
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.core.model.WakeMode
import com.smartalarm.core.protocol.WearJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** What the watch is doing right now. */
enum class WatchPhase { IDLE, TRACKING, ALARM }

/**
 * The watch's shared state, readable by the UI and written by [SleepTrackingService].
 *
 * A plain singleton rather than a bound service: the tracking service runs for ten hours while
 * the activity comes and goes with every wrist raise, and rebinding on each of those is more
 * machinery than a handful of state flows.
 */
object WatchSession {
    private val _phase = MutableStateFlow(WatchPhase.IDLE)
    val phase: StateFlow<WatchPhase> = _phase.asStateFlow()

    private val _status = MutableStateFlow<LiveStatus?>(null)
    val status: StateFlow<LiveStatus?> = _status.asStateFlow()

    private val _plan = MutableStateFlow(AlarmPlan())
    val plan: StateFlow<AlarmPlan> = _plan.asStateFlow()

    private val _phoneConnected = MutableStateFlow(false)
    val phoneConnected: StateFlow<Boolean> = _phoneConnected.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    var profile: SleepProfile = SleepProfile()
        private set

    internal fun setPhase(phase: WatchPhase) {
        _phase.value = phase
    }

    internal fun setStatus(status: LiveStatus?) {
        _status.value = status
    }

    internal fun setPhoneConnected(connected: Boolean) {
        _phoneConnected.value = connected
    }

    internal fun setMessage(message: String?) {
        _message.value = message
    }

    internal fun setProfile(profile: SleepProfile) {
        this.profile = profile
    }

    fun updatePlan(context: Context, plan: AlarmPlan) {
        _plan.value = plan
        persist(context, plan)
    }

    fun setCycles(context: Context, cycles: Int) =
        updatePlan(context, _plan.value.copy(cycles = cycles.coerceIn(AlarmPlan.MIN_CYCLES, AlarmPlan.MAX_CYCLES)))

    fun setWakeMode(context: Context, mode: WakeMode) =
        updatePlan(context, _plan.value.copy(wakeMode = mode))

    /** Reload the last plan and profile from disk, so a restarted watch app is not amnesiac. */
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PLAN, null)?.let { json ->
            runCatching { WearJson.instance.decodeFromString<AlarmPlan>(json) }
                .onSuccess { _plan.value = it }
        }
        prefs.getString(KEY_PROFILE, null)?.let { json ->
            runCatching { WearJson.instance.decodeFromString<SleepProfile>(json) }
                .onSuccess { profile = it }
        }
    }

    internal fun persistProfile(context: Context, profile: SleepProfile) {
        this.profile = profile
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILE, WearJson.instance.encodeToString(profile))
            .apply()
    }

    private fun persist(context: Context, plan: AlarmPlan) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PLAN, WearJson.instance.encodeToString(plan))
            .apply()
    }

    private const val PREFS = "smartalarm_wear"
    private const val KEY_PLAN = "plan"
    private const val KEY_PROFILE = "profile"
}
