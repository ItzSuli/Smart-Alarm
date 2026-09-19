package com.smartalarm.core.sleep

import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.EPOCHS_PER_MINUTE
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.HypnogramPoint
import com.smartalarm.core.model.LiveStatus
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.model.SleepCycle
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.model.StagedEpoch
import kotlin.math.roundToInt

/**
 * One night of sleep tracking, end to end.
 *
 * Feed it [EpochFeatures] as the watch produces them and it runs the whole pipeline — personal
 * baselines, Cole-Kripke sleep/wake scoring, HMM staging, cycle detection, and the wake
 * decision — then hands back a [LiveStatus] the UI can render directly.
 *
 * Both apps run this same class on the same epoch stream, so the phone's display and the
 * watch's alarm decision can never disagree. The watch is authoritative for firing the alarm
 * because it owns the sensors; the phone replays the stream for the UI, history, and its own
 * fallback alarm if the watch goes silent.
 */
class SleepSessionEngine(
    val sessionId: String,
    val plan: AlarmPlan,
    profile: SleepProfile = SleepProfile(),
    val startedAtMillis: Long,
) {
    private val baseline = NightBaseline(
        seedRestingHeartRate = profile.restingHeartRate,
        seedActivityScale = profile.activityScale,
    )
    private val scorer = SleepWakeScorer()
    private val classifier = SleepStageClassifier(baseline)
    private val cycleTracker = CycleTracker(
        seedCycleMinutes = if (profile.cycleSamples > 0) profile.cycleMinutes else plan.baseCycleMinutes,
    )
    private val wakeEngine = SmartWakeEngine(plan)

    private val expectedLatencyMinutes: Float =
        if (profile.latencySamples > 0) profile.sleepLatencyMinutes else plan.sleepLatencyMinutes.toFloat()

    private val featureLog = ArrayList<EpochFeatures>(1400)
    private val staged = ArrayList<StagedEpoch>(1400)
    private var analysis = CycleAnalysis()
    private var firstEpochStartMillis = 0L

    /** Persistent sleep onset, once ten unbroken minutes of sleep have been scored. */
    var sleepOnsetMillis: Long? = null
        private set

    var lastDecision: WakeDecision = WakeDecision(
        shouldWake = false,
        targetMillis = startedAtMillis,
        windowStartMillis = startedAtMillis,
        windowEndMillis = startedAtMillis,
        score = 0f,
        threshold = 1f,
        stage = SleepStage.UNKNOWN,
        reason = "Waiting for data",
    )
        private set

    var alarmFiredMillis: Long? = null
        private set

    /** The decision as it stood at the moment the alarm fired, not as it stands now. */
    var firedDecision: WakeDecision? = null
        private set

    /** Every epoch scored so far. The newest few are provisional and may still be revised. */
    val stagedEpochs: List<StagedEpoch> get() = staged

    val cycles: List<SleepCycle> get() = analysis.cycles

    /** The cycle currently in flight, or null before sleep onset. */
    val currentCycle: SleepCycle? get() = analysis.current

    /** Feed one epoch and get the updated live picture. */
    fun onEpoch(features: EpochFeatures): LiveStatus {
        if (featureLog.isEmpty()) firstEpochStartMillis = features.startMillis
        featureLog += features
        baseline.observe(features.heartRate, features.activityCount, features.hrvProxyMs)
        scorer.addEpoch(features.activityCount, features.movingSeconds)
        recompute()
        return tick(features.endMillis)
    }

    fun onEpochs(features: List<EpochFeatures>): LiveStatus {
        var status = currentStatus(startedAtMillis)
        features.forEach { status = onEpoch(it) }
        return status
    }

    /**
     * Re-run the night: sleep/wake scoring, staging, then cycle detection.
     *
     * The whole night is reprocessed rather than just the newest epoch, because the personal
     * baselines everything is measured against keep improving as the night accumulates. The
     * cost is a few hundred microseconds once every thirty seconds; the benefit is that the
     * first hour of the night gets scored as well as the last.
     */
    private fun recompute() {
        scorer.rescore(baseline)

        val onsetMinute = scorer.persistentSleepOnsetMinute(PERSISTENT_SLEEP_MINUTES)
        sleepOnsetMillis = onsetMinute?.let { firstEpochStartMillis + it * 60_000L }

        // The REM prior wants to know where we are inside the current cycle. Take that from the
        // previous pass's boundaries: they move by at most one epoch between passes, and it
        // avoids a circular dependency between staging and cycle detection.
        val previous = analysis
        val classification = classifier.classify(
            features = featureLog,
            onsetMillis = sleepOnsetMillis,
            scoredAsleep = { index -> scorer.asleepAtEpoch(index) },
            cyclePhaseAt = { millis -> cycleTracker.currentCyclePhase(millis, previous) },
        )

        staged.clear()
        val provisionalFrom = featureLog.size - SleepStageClassifier.PROVISIONAL_EPOCHS
        featureLog.forEachIndexed { index, features ->
            staged += StagedEpoch(
                features = features,
                stage = classification.stages[index],
                confidence = classification.confidences[index],
                scoredAsleep = scorer.asleepAtEpoch(index),
                finalized = index < provisionalFrom,
            )
        }

        analysis = cycleTracker.analyze(staged, sleepOnsetMillis)
        // Stamp each epoch with the cycle it landed in, for the hypnogram.
        staged.forEachIndexed { index, epoch ->
            val cycleIndex = analysis.epochCycleIndex[epoch.index] ?: 0
            if (cycleIndex != 0) staged[index] = epoch.copy(cycleIndex = cycleIndex)
        }
    }

    /**
     * Re-evaluate the wake decision without new sensor data. Call this on a timer as well, so
     * the hard deadline still fires when the sensors have gone quiet.
     */
    fun tick(nowMillis: Long): LiveStatus {
        lastDecision = wakeEngine.evaluate(
            nowMillis = nowMillis,
            targetMillis = projectedWakeMillis(nowMillis),
            recentEpochs = staged,
            completedCycles = analysis.completedCount,
            currentCycleHasRem = analysis.currentCycleHasRem,
        )
        if (lastDecision.shouldWake && alarmFiredMillis == null) {
            alarmFiredMillis = nowMillis
            firedDecision = lastDecision
            wakeEngine.markFired(nowMillis)
        }
        return currentStatus(nowMillis)
    }

    /** `true` when the alarm should be sounding right now. */
    val shouldWakeNow: Boolean get() = alarmFiredMillis != null

    /**
     * Projected wall-clock time the requested cycle ends.
     * Before sleep onset this is a plain estimate; afterwards it tracks the measured cycles.
     */
    fun projectedWakeMillis(nowMillis: Long): Long {
        cycleTracker.projectEndOfCycle(plan.cycles, nowMillis, analysis)?.let { return it }
        val base = if (analysis.completedCount > 0) {
            cycleTracker.personalBaseMinutes(analysis)
        } else {
            plan.baseCycleMinutes
        }
        val onset = sleepOnsetMillis ?: (startedAtMillis + (expectedLatencyMinutes * 60_000f).toLong())
        return onset + (plan.cycles * base * 60_000f).toLong()
    }

    /** The nominal target, used for the preview before any tracking has happened. */
    fun nominalWakeMillis(): Long =
        startedAtMillis + ((expectedLatencyMinutes + plan.nominalSleepMinutes) * 60_000f).toLong()

    fun currentStatus(nowMillis: Long): LiveStatus {
        val target = lastDecision.targetMillis.takeIf { it > 0 } ?: projectedWakeMillis(nowMillis)
        val latest = staged.lastOrNull()
        val minutes = stageMinutes()
        return LiveStatus(
            sessionId = sessionId,
            plan = plan,
            trackingStartedMillis = startedAtMillis,
            sleepOnsetMillis = sleepOnsetMillis,
            nowMillis = nowMillis,
            stage = latest?.stage ?: SleepStage.UNKNOWN,
            stageConfidence = latest?.confidence ?: 0f,
            epochCount = staged.size,
            completedCycles = analysis.completedCount,
            currentCycleProgress = cycleTracker.currentCyclePhase(nowMillis, analysis).coerceIn(0f, 1f),
            projectedWakeMillis = target,
            windowStartMillis = lastDecision.windowStartMillis,
            windowEndMillis = lastDecision.windowEndMillis,
            expectedCycleMinutes = cycleTracker.predictedCycleMinutes(analysis.completedCount + 1, analysis),
            asleepMinutes = minutes.asleep,
            awakeMinutes = minutes.awake,
            deepMinutes = minutes.deep,
            remMinutes = minutes.rem,
            lightMinutes = minutes.light,
            latestHeartRate = staged.lastOrNull { it.features.hasHeartRate }?.features?.heartRate
                ?: EpochFeatures.NO_HR,
            heartRateAvailable = staged.takeLast(HR_PRESENCE_WINDOW).any { it.features.hasHeartRate },
            measuredCycles = cycleTracker.measuredCycleCount(analysis),
            watchBatteryPercent = latest?.features?.batteryPercent ?: -1,
            wakeScore = lastDecision.score,
            wakeThreshold = lastDecision.threshold,
            alarmFired = alarmFiredMillis != null,
            tracking = true,
        )
    }

    /** Build the persisted record of the night. */
    fun summary(endedMillis: Long, wakeMillis: Long? = alarmFiredMillis, notes: String = ""): SessionSummary {
        val minutes = stageMinutes()
        val timeInBed = ((endedMillis - startedAtMillis) / 60_000f).coerceAtLeast(1f)
        val heartRates = staged.mapNotNull { it.features.heartRate.takeIf { hr -> hr > 0f } }
        val wakeStage = wakeMillis?.let { millis ->
            staged.lastOrNull { it.features.startMillis <= millis }?.stage
        } ?: staged.lastOrNull()?.stage ?: SleepStage.UNKNOWN

        return SessionSummary(
            sessionId = sessionId,
            plan = plan,
            startedMillis = startedAtMillis,
            endedMillis = endedMillis,
            sleepOnsetMillis = sleepOnsetMillis,
            wakeMillis = wakeMillis,
            cycles = analysis.cycles + listOfNotNull(analysis.current?.takeIf { it.durationMinutes > 10f }),
            hypnogram = staged.map {
                HypnogramPoint(
                    startMillis = it.features.startMillis,
                    stage = it.stage,
                    heartRate = it.features.heartRate,
                    activity = it.features.activityCount,
                    cycleIndex = it.cycleIndex,
                )
            },
            asleepMinutes = minutes.asleep,
            awakeMinutes = minutes.awake,
            lightMinutes = minutes.light,
            deepMinutes = minutes.deep,
            remMinutes = minutes.rem,
            sleepEfficiency = (minutes.asleep / timeInBed * 100f).coerceIn(0f, 100f),
            averageHeartRate = if (heartRates.isEmpty()) EpochFeatures.NO_HR else heartRates.average().toFloat(),
            minHeartRate = heartRates.minOrNull() ?: EpochFeatures.NO_HR,
            wakeStage = wakeStage,
            wakeQuality = (firedDecision ?: lastDecision).wakeQualityPercent,
            alarmFired = alarmFiredMillis != null,
            notes = notes.ifEmpty { firedDecision?.reason.orEmpty() },
        )
    }

    /**
     * Fold tonight's measurements back into the sleeper's profile.
     *
     * This is what makes the second night better than the first: cycle length, how long this
     * person takes to fall asleep, their resting heart rate and their watch's movement scale
     * all carry forward, so the very first cycle of tomorrow night is already personalised.
     */
    fun updatedProfile(previous: SleepProfile): SleepProfile {
        val completedCycles = analysis.cycles.filter { it.complete }
        val cycleMinutes = if (completedCycles.isEmpty()) {
            previous.cycleMinutes
        } else {
            val tonight = cycleTracker.personalBaseMinutes(analysis)
            val historyWeight = previous.cycleSamples.toFloat()
            val tonightWeight = (completedCycles.size * TONIGHT_WEIGHT).coerceAtLeast(1f)
            ((previous.cycleMinutes * historyWeight + tonight * tonightWeight) /
                (historyWeight + tonightWeight)).coerceIn(65f, 120f)
        }

        val onset = sleepOnsetMillis
        val latency = if (onset == null) {
            previous.sleepLatencyMinutes to previous.latencySamples
        } else {
            val tonightLatency = ((onset - startedAtMillis) / 60_000f).coerceIn(0f, 120f)
            val n = previous.latencySamples
            ((previous.sleepLatencyMinutes * n + tonightLatency) / (n + 1)) to (n + 1)
        }

        val restingHr = staged.mapNotNull { it.features.heartRate.takeIf { hr -> hr > 0f } }
            .sorted()
            .let { if (it.size < 20) null else it[(it.size * 0.05f).toInt()] }

        return previous.copy(
            cycleMinutes = cycleMinutes,
            cycleSamples = previous.cycleSamples + completedCycles.size,
            sleepLatencyMinutes = latency.first,
            latencySamples = latency.second,
            restingHeartRate = when {
                restingHr == null -> previous.restingHeartRate
                previous.restingHeartRate <= 0f -> restingHr
                else -> previous.restingHeartRate * 0.7f + restingHr * 0.3f
            },
            activityScale = if (staged.size < 60) previous.activityScale else {
                val scale = staged.map { it.features.activityCount }.sorted()
                    .let { it[(it.size * 0.75f).toInt()] }.coerceAtLeast(2f)
                previous.activityScale * 0.6f + scale * 0.4f
            },
            nightsRecorded = previous.nightsRecorded + 1,
        )
    }

    private fun stageMinutes(): StageMinutes {
        var light = 0
        var deep = 0
        var rem = 0
        var awake = 0
        for (epoch in staged) {
            when (epoch.stage) {
                SleepStage.LIGHT -> light++
                SleepStage.DEEP -> deep++
                SleepStage.REM -> rem++
                SleepStage.AWAKE -> awake++
                SleepStage.UNKNOWN -> Unit
            }
        }
        val perEpoch = 1f / EPOCHS_PER_MINUTE
        return StageMinutes(
            light = light * perEpoch,
            deep = deep * perEpoch,
            rem = rem * perEpoch,
            awake = awake * perEpoch,
            asleep = (light + deep + rem) * perEpoch,
        )
    }

    private data class StageMinutes(
        val light: Float,
        val deep: Float,
        val rem: Float,
        val awake: Float,
        val asleep: Float,
    )

    companion object {
        /** Consecutive sleep minutes required to call it persistent sleep onset. */
        const val PERSISTENT_SLEEP_MINUTES = 10

        private const val TONIGHT_WEIGHT = 0.8f

        /** How far back to look for a heart-rate reading before declaring the sensor absent. */
        private const val HR_PRESENCE_WINDOW = 20

        fun formatMinutes(minutes: Float): String {
            val total = minutes.roundToInt().coerceAtLeast(0)
            val hours = total / 60
            val rest = total % 60
            return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
        }
    }
}
