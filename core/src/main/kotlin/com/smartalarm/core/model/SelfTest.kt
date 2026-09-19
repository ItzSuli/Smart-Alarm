package com.smartalarm.core.model

import kotlinx.serialization.Serializable

/** How one check came out. */
@Serializable
enum class CheckStatus {
    /** Working. */
    PASS,

    /** Working, but something about it will bite later. */
    WARN,

    /** Broken. The app will not do its job until this is fixed. */
    FAIL,

    /** Could not be checked, usually because something it depends on failed first. */
    SKIPPED,

    /** Still running. */
    RUNNING,

    /** Not started yet. */
    PENDING,
}

/** One line of the system check. */
@Serializable
data class CheckResult(
    val id: String,
    val label: String,
    val status: CheckStatus,
    /** What was actually measured. Shown under the label. */
    val detail: String = "",
    /** What to do about it. Only meaningful when the status is not [CheckStatus.PASS]. */
    val remedy: String = "",
) {
    companion object {
        fun pending(id: String, label: String) = CheckResult(id, label, CheckStatus.PENDING)
        fun running(id: String, label: String) = CheckResult(id, label, CheckStatus.RUNNING)
    }
}

/** Phone -> watch: run your half of the system check. */
@Serializable
data class SelfTestRequest(
    val requestId: String,
    val issuedAtMillis: Long,
    /** How long to hold the sensors open. Long enough for the heart-rate sensor to lock on. */
    val sensorSeconds: Int = 30,
    /** Buzz at the end, so the wearer can confirm they would actually have felt it. */
    val testAlarm: Boolean = true,
    val wakeMode: WakeMode = WakeMode.BOTH,
    val vibrationIntensity: Int = 2,
)

/** Watch -> phone: what the watch found. */
@Serializable
data class SelfTestResult(
    val requestId: String,
    val completedAtMillis: Long,
    val checks: List<CheckResult> = emptyList(),
    /** Accelerometer samples actually delivered during the test. */
    val accelSamples: Int = 0,
    /** Samples per second the accelerometer really managed, which is not always what was asked. */
    val effectiveAccelHz: Float = 0f,
    val heartRateSamples: Int = 0,
    val lastHeartRate: Float = EpochFeatures.NO_HR,
    /** Epochs the feature pipeline produced end to end. */
    val epochsProduced: Int = 0,
    /** Movement seen during the test, in the same units the sleep engine uses. */
    val activityCount: Float = 0f,
    val watchBatteryPercent: Int = -1,
    val vibrated: Boolean = false,
)

/** The whole check, both halves merged, as the phone renders it. */
@Serializable
data class SystemCheckReport(
    val requestId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long = 0L,
    val phoneChecks: List<CheckResult> = emptyList(),
    val watchChecks: List<CheckResult> = emptyList(),
    /** Round trip to the watch and back, in milliseconds. */
    val roundTripMillis: Long = -1,
    val running: Boolean = false,
    /** Set once the wearer has said whether they felt the buzz and heard the alarm. */
    val confirmations: List<CheckResult> = emptyList(),
) {
    val allChecks: List<CheckResult> get() = phoneChecks + watchChecks + confirmations

    val failures: List<CheckResult> get() = allChecks.filter { it.status == CheckStatus.FAIL }
    val warnings: List<CheckResult> get() = allChecks.filter { it.status == CheckStatus.WARN }

    /**
     * The one-line verdict.
     *
     * A warning is deliberately not "ready": everything in this check exists because it can
     * cause a missed alarm, and "mostly fine" is not a useful thing to be told about an alarm
     * clock at bedtime.
     */
    val verdict: String
        get() = when {
            running -> "Checking…"
            failures.isNotEmpty() -> "${failures.size} problem${if (failures.size == 1) "" else "s"} to fix"
            warnings.isNotEmpty() -> "Works, with ${warnings.size} thing${if (warnings.size == 1) "" else "s"} worth knowing"
            allChecks.isEmpty() -> "Not run yet"
            else -> "Everything works"
        }

    val readyToSleep: Boolean
        get() = !running && allChecks.isNotEmpty() && failures.isEmpty()
}

/** Check identifiers, so the two apps and the tests agree on what each line means. */
object CheckIds {
    // Phone side
    const val WATCH_LINK = "phone.watch_link"
    const val ROUND_TRIP = "phone.round_trip"
    const val EPOCH_TRANSFER = "phone.epoch_transfer"
    const val NOTIFICATIONS = "phone.notifications"
    const val EXACT_ALARM = "phone.exact_alarm"
    const val FULL_SCREEN = "phone.full_screen"
    const val ALARM_VOLUME = "phone.alarm_volume"
    const val BATTERY_OPTIMISATION = "phone.battery_optimisation"

    // Watch side
    const val ACCELEROMETER = "watch.accelerometer"
    const val ACCEL_RATE = "watch.accel_rate"
    const val HEART_RATE = "watch.heart_rate"
    const val EPOCH_PIPELINE = "watch.epoch_pipeline"
    const val VIBRATOR = "watch.vibrator"
    const val WATCH_BATTERY = "watch.battery"

    // Confirmed by the person, not by the machine
    const val FELT_BUZZ = "confirm.felt_buzz"
    const val HEARD_ALARM = "confirm.heard_alarm"
}
