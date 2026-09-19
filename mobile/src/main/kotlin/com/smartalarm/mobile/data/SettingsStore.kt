package com.smartalarm.mobile.data

import android.content.Context
import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.core.protocol.WearJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The plan for tonight and what the app has learned about this sleeper.
 *
 * Deliberately SharedPreferences rather than DataStore: this is read from broadcast receivers
 * and short-lived services that may only exist for a few milliseconds, and a suspending read is
 * the wrong shape for that.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _plan = MutableStateFlow(readPlan())
    val plan: StateFlow<AlarmPlan> = _plan.asStateFlow()

    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<SleepProfile> = _profile.asStateFlow()

    fun updatePlan(transform: (AlarmPlan) -> AlarmPlan) {
        val updated = transform(_plan.value)
        _plan.value = updated
        prefs.edit().putString(KEY_PLAN, WearJson.instance.encodeToString(updated)).apply()
    }

    fun updateProfile(profile: SleepProfile) {
        _profile.value = profile
        prefs.edit().putString(KEY_PROFILE, WearJson.instance.encodeToString(profile)).apply()
    }

    /** Id of the night currently being tracked, so a restarted process can pick it back up. */
    var activeSessionId: String?
        get() = prefs.getString(KEY_ACTIVE_SESSION, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_ACTIVE_SESSION) else putString(KEY_ACTIVE_SESSION, value)
        }.apply()

    var activeSessionStartedMillis: Long
        get() = prefs.getLong(KEY_ACTIVE_STARTED, 0L)
        set(value) = prefs.edit().putLong(KEY_ACTIVE_STARTED, value).apply()

    /** Wall-clock time the watch last sent anything; drives the "watch has gone quiet" backstop. */
    var lastWatchContactMillis: Long
        get() = prefs.getLong(KEY_LAST_CONTACT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CONTACT, value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    private fun readPlan(): AlarmPlan =
        prefs.getString(KEY_PLAN, null)
            ?.let { runCatching { WearJson.instance.decodeFromString<AlarmPlan>(it) }.getOrNull() }
            ?: AlarmPlan()

    private fun readProfile(): SleepProfile =
        prefs.getString(KEY_PROFILE, null)
            ?.let { runCatching { WearJson.instance.decodeFromString<SleepProfile>(it) }.getOrNull() }
            ?: SleepProfile()

    private companion object {
        const val PREFS = "smartalarm"
        const val KEY_PLAN = "plan"
        const val KEY_PROFILE = "profile"
        const val KEY_ACTIVE_SESSION = "active_session"
        const val KEY_ACTIVE_STARTED = "active_started"
        const val KEY_LAST_CONTACT = "last_watch_contact"
        const val KEY_ONBOARDED = "onboarded"
    }
}
