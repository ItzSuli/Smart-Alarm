package com.smartalarm.core

import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.model.StagedEpoch
import kotlin.random.Random

/**
 * A sleeper who falls asleep and never stirs again: motionless wrist, flat low heart rate.
 *
 * There is no good moment to wake this person, which is exactly the point — the alarm still
 * has to go off.
 */
class ImmovableSleeper(
    private val totalMinutes: Int = 480,
    private val startMillis: Long = 1_700_000_000_000L,
    seed: Long = 5L,
) {
    private val random = Random(seed)

    fun epochs(): List<EpochFeatures> = (0 until totalMinutes * 2).map { index ->
        val awake = index < 20 // two minutes of settling, then out cold
        EpochFeatures(
            index = index,
            startMillis = startMillis + index * 30_000L,
            activityCount = if (awake) 900f + random.nextFloat() * 400f else 46f + random.nextFloat() * 3f,
            zeroCrossings = if (awake) 60 else 2,
            peakAcceleration = if (awake) 0.5f else 0.006f,
            movingSeconds = if (awake) 9f else 0f,
            wristAngle = 20f,
            wristAngleChange = if (awake) 14f else 0.2f,
            heartRate = if (awake) 68f else 50.5f + random.nextFloat(),
            heartRateMin = if (awake) 62f else 50f,
            heartRateMax = if (awake) 75f else 52f,
            heartRateStdDev = if (awake) 5f else 0.6f,
            hrvProxyMs = if (awake) 90f else 22f + random.nextFloat() * 4f,
            hrSampleCount = 3,
        )
    }
}

/** Builds a run of staged epochs all in one stage, for testing the wake engine directly. */
fun stagedRun(
    stage: SleepStage,
    count: Int,
    startMillis: Long,
    startIndex: Int = 0,
    confidence: Float = 0.8f,
    movingSeconds: Float = 0f,
): List<StagedEpoch> = (0 until count).map { offset ->
    StagedEpoch(
        features = EpochFeatures(
            index = startIndex + offset,
            startMillis = startMillis + offset * 30_000L,
            movingSeconds = movingSeconds,
            heartRate = 55f,
            hrSampleCount = 3,
        ),
        stage = stage,
        confidence = confidence,
        scoredAsleep = stage != SleepStage.AWAKE,
        finalized = true,
    )
}
