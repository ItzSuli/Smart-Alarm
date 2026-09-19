package com.smartalarm.core.model

import kotlinx.serialization.Serializable

/**
 * Sleep stages we are able to estimate from a wrist-worn device.
 *
 * A consumer watch has no EEG, so LIGHT/DEEP/REM are *estimates* derived from movement and
 * cardiac features, not polysomnography-grade scoring. See docs/ALGORITHM.md.
 */
@Serializable
enum class SleepStage(val code: Int) {
    /** Awake, or at least not scorable as sleep. */
    AWAKE(0),

    /** REM sleep: muscle atonia (no gross movement) with an active, variable heart. */
    REM(1),

    /** N1/N2 light sleep. */
    LIGHT(2),

    /** N3 slow-wave / deep sleep: the hardest stage to be woken from. */
    DEEP(3),

    /** Not enough data yet. */
    UNKNOWN(-1);

    /** `true` for every stage that counts as being asleep. */
    val isAsleep: Boolean get() = this == REM || this == LIGHT || this == DEEP

    /**
     * How pleasant it is to be woken from this stage, 0 (awful) to 1 (ideal).
     * Waking out of DEEP causes sleep inertia; waking mid-REM leaves you groggy and dream-fogged.
     */
    val wakeFriendliness: Float
        get() = when (this) {
            AWAKE -> 1.0f
            LIGHT -> 0.82f
            REM -> 0.40f
            DEEP -> 0.05f
            UNKNOWN -> 0.3f
        }

    /** Arousal depth used for trend analysis: bigger means harder to wake. */
    val depth: Float
        get() = when (this) {
            AWAKE -> 0f
            REM -> 1.2f
            LIGHT -> 2f
            DEEP -> 3f
            UNKNOWN -> 1.5f
        }

    companion object {
        fun fromCode(code: Int): SleepStage = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** How the user wants to be woken. */
@Serializable
enum class WakeMode {
    /** Only the watch buzzes. Silent for anyone else in the room. */
    WATCH_ONLY,

    /** Only the phone alarm rings. */
    PHONE_ONLY,

    /** Watch vibration *and* phone alarm together. */
    BOTH;

    val usesWatch: Boolean get() = this == WATCH_ONLY || this == BOTH
    val usesPhone: Boolean get() = this == PHONE_ONLY || this == BOTH
}

/**
 * The per-epoch feature vector the watch computes from raw sensor samples.
 *
 * One of these is produced every [EPOCH_MILLIS]. Everything downstream — staging, cycle
 * detection, the wake decision — consumes only this, which is why the phone can replay a
 * night exactly as the watch saw it.
 */
@Serializable
data class EpochFeatures(
    val index: Int,
    val startMillis: Long,
    val durationMillis: Long = EPOCH_MILLIS,
    /** Integrated band-passed acceleration ("proportional integration mode"), milli-g seconds. */
    val activityCount: Float = 0f,
    /** Zero-crossing count of the band-passed signal above a dead-band. */
    val zeroCrossings: Int = 0,
    /** Largest band-passed excursion in the epoch, in g. */
    val peakAcceleration: Float = 0f,
    /** Seconds within the epoch spent above the movement threshold. */
    val movingSeconds: Float = 0f,
    /** Mean wrist tilt (van Hees z-angle) in degrees. */
    val wristAngle: Float = 0f,
    /** |wristAngle - previous epoch's wristAngle| in degrees; posture changes. */
    val wristAngleChange: Float = 0f,
    /** Mean heart rate in bpm, or [NO_HR] when the sensor gave nothing usable. */
    val heartRate: Float = NO_HR,
    val heartRateMin: Float = NO_HR,
    val heartRateMax: Float = NO_HR,
    /** Standard deviation of the bpm samples inside the epoch. */
    val heartRateStdDev: Float = 0f,
    /** RMSSD over inter-beat intervals derived from consecutive bpm readings, in ms. A proxy. */
    val hrvProxyMs: Float = 0f,
    val hrSampleCount: Int = 0,
    /** The watch reported the sensor was not in skin contact. */
    val offWrist: Boolean = false,
    val batteryPercent: Int = -1,
) {
    val hasHeartRate: Boolean get() = heartRate > 0f && hrSampleCount > 0
    val endMillis: Long get() = startMillis + durationMillis

    companion object {
        const val NO_HR = -1f
    }
}

/** One 30-second epoch of the night after the classifier has labelled it. */
@Serializable
data class StagedEpoch(
    val features: EpochFeatures,
    val stage: SleepStage,
    /** Posterior confidence of [stage], 0..1. */
    val confidence: Float,
    /** Cole-Kripke + Webster sleep/wake verdict, independent of the stage model. */
    val scoredAsleep: Boolean,
    /** 1-based cycle this epoch belongs to, or 0 before sleep onset. */
    val cycleIndex: Int = 0,
    /** `true` once the Viterbi path for this epoch can no longer change. */
    val finalized: Boolean = false,
) {
    val startMillis: Long get() = features.startMillis
    val index: Int get() = features.index
}

/** A detected NREM-REM sleep cycle. */
@Serializable
data class SleepCycle(
    val index: Int,
    val startMillis: Long,
    val endMillis: Long,
    /** When this cycle's REM period ended, if it had a scorable one. */
    val remEndMillis: Long? = null,
    /** `false` while the cycle is still in progress. */
    val complete: Boolean = false,
    val lightMinutes: Float = 0f,
    val deepMinutes: Float = 0f,
    val remMinutes: Float = 0f,
    val awakeMinutes: Float = 0f,
    /** Why the tracker decided this cycle ended. */
    val boundaryReason: String = "",
) {
    val durationMinutes: Float get() = (endMillis - startMillis) / 60_000f
    val nremMinutes: Float get() = lightMinutes + deepMinutes
}

/** User-facing alarm configuration for one night. */
@Serializable
data class AlarmPlan(
    /** 1..7 sleep cycles. Cycle 6 is roughly nine hours. */
    val cycles: Int = 5,
    val wakeMode: WakeMode = WakeMode.BOTH,
    /** How early inside the window smart wake is allowed to fire. */
    val windowBeforeMinutes: Int = 30,
    /** Hard deadline after the projected target; the alarm never fires later than this. */
    val windowAfterMinutes: Int = 15,
    /** When false the alarm fires exactly at the projected target, no stage awareness. */
    val smartWakeEnabled: Boolean = true,
    /** Starting guess for cycle length before this night has taught us anything. */
    val baseCycleMinutes: Float = 90f,
    /** Expected minutes between pressing start and actually falling asleep. */
    val sleepLatencyMinutes: Int = 14,
    val alarmToneUri: String? = null,
    /** 0 gentle, 1 medium, 2 strong. */
    val vibrationIntensity: Int = 2,
    val snoozeMinutes: Int = 9,
    /** Gradually ramp the alarm volume instead of starting at full blast. */
    val gentleVolumeRamp: Boolean = true,
    /**
     * Ring the phone anyway if the watch stops reporting, whatever [wakeMode] says.
     *
     * A flat watch battery or a Bluetooth drop should not mean sleeping through the morning,
     * so the phone keeps an alarm scheduled at the hard deadline as a backstop even in
     * watch-only mode. It is cancelled the moment the watch checks in again.
     */
    val phoneBackupIfWatchSilent: Boolean = true,
) {
    init {
        require(cycles in MIN_CYCLES..MAX_CYCLES) { "cycles must be $MIN_CYCLES..$MAX_CYCLES" }
    }

    /** Nominal duration, used for the preview on the home screen before any tracking happens. */
    val nominalSleepMinutes: Float get() = cycles * baseCycleMinutes

    companion object {
        const val MIN_CYCLES = 1
        const val MAX_CYCLES = 7
    }
}

/** What the watch learns about this particular sleeper, carried across nights. */
@Serializable
data class SleepProfile(
    /** Personal mean NREM-REM cycle length in minutes, learned from completed cycles. */
    val cycleMinutes: Float = 90f,
    /** How many completed cycles have contributed to [cycleMinutes]. */
    val cycleSamples: Int = 0,
    /** Typical minutes from "start tracking" to persistent sleep. */
    val sleepLatencyMinutes: Float = 14f,
    val latencySamples: Int = 0,
    /** Lowest sustained sleeping heart rate seen, used as the per-night HR floor prior. */
    val restingHeartRate: Float = EpochFeatures.NO_HR,
    /** Device-specific movement scale, so activity counts mean the same thing night to night. */
    val activityScale: Float = DEFAULT_ACTIVITY_SCALE,
    val nightsRecorded: Int = 0,
) {
    companion object {
        const val DEFAULT_ACTIVITY_SCALE = 12f
    }
}

/** The live snapshot the watch pushes to the phone, and the phone renders. */
@Serializable
data class LiveStatus(
    val sessionId: String,
    val plan: AlarmPlan,
    val trackingStartedMillis: Long,
    val sleepOnsetMillis: Long? = null,
    val nowMillis: Long = 0L,
    val stage: SleepStage = SleepStage.UNKNOWN,
    val stageConfidence: Float = 0f,
    val epochCount: Int = 0,
    val completedCycles: Int = 0,
    /** Progress through the cycle currently in flight, 0..1. */
    val currentCycleProgress: Float = 0f,
    val projectedWakeMillis: Long = 0L,
    val windowStartMillis: Long = 0L,
    val windowEndMillis: Long = 0L,
    val expectedCycleMinutes: Float = 90f,
    val asleepMinutes: Float = 0f,
    val awakeMinutes: Float = 0f,
    val deepMinutes: Float = 0f,
    val remMinutes: Float = 0f,
    val lightMinutes: Float = 0f,
    val latestHeartRate: Float = EpochFeatures.NO_HR,
    /** False when the watch's heart-rate sensor is not reporting; staging is degraded. */
    val heartRateAvailable: Boolean = true,
    /** Cycle boundaries drawn from a real REM period ending, as opposed to fallbacks. */
    val measuredCycles: Int = 0,
    val watchBatteryPercent: Int = -1,
    val wakeScore: Float = 0f,
    val wakeThreshold: Float = 1f,
    val alarmFired: Boolean = false,
    val tracking: Boolean = true,
) {
    val elapsedMinutes: Float
        get() = if (nowMillis > trackingStartedMillis) (nowMillis - trackingStartedMillis) / 60_000f else 0f
}

/** The persisted record of a finished night. */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val plan: AlarmPlan,
    val startedMillis: Long,
    val endedMillis: Long,
    val sleepOnsetMillis: Long?,
    val wakeMillis: Long?,
    val cycles: List<SleepCycle> = emptyList(),
    val hypnogram: List<HypnogramPoint> = emptyList(),
    val asleepMinutes: Float = 0f,
    val awakeMinutes: Float = 0f,
    val lightMinutes: Float = 0f,
    val deepMinutes: Float = 0f,
    val remMinutes: Float = 0f,
    val sleepEfficiency: Float = 0f,
    val averageHeartRate: Float = EpochFeatures.NO_HR,
    val minHeartRate: Float = EpochFeatures.NO_HR,
    val wakeStage: SleepStage = SleepStage.UNKNOWN,
    /** 0..100 quality of the moment the alarm actually fired. */
    val wakeQuality: Int = 0,
    val alarmFired: Boolean = false,
    val notes: String = "",
) {
    val timeInBedMinutes: Float get() = (endedMillis - startedMillis) / 60_000f
}

/** A compact hypnogram sample for charting and storage. */
@Serializable
data class HypnogramPoint(
    val startMillis: Long,
    val stage: SleepStage,
    val heartRate: Float = EpochFeatures.NO_HR,
    val activity: Float = 0f,
    val cycleIndex: Int = 0,
)

/** Length of one scoring epoch. 30 s is the polysomnography standard. */
const val EPOCH_MILLIS: Long = 30_000L

/** Epochs per minute. */
const val EPOCHS_PER_MINUTE: Int = 2
