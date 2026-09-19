package com.smartalarm.core

import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.sleep.EpochFeatureBuilder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates a realistic night of raw watch sensor data from a known ground-truth hypnogram.
 *
 * This is the closest thing to a test rig for a sleep algorithm that does not involve a sleep
 * lab: build a night whose stages we know, synthesise the accelerometer and heart-rate streams
 * a wrist would actually produce in those stages, push them through the real
 * [EpochFeatureBuilder], and check what the engine recovers.
 *
 * The signal models are deliberately the *inverse* of the classifier's assumptions rather than
 * a copy of them — movement is generated as discrete bursts at physiological rates, and heart
 * rate as a lagged first-order response to a stage target, neither of which the classifier
 * knows anything about. It sees only the same band-passed activity counts and bpm averages a
 * real Galaxy Watch would hand it.
 */
class SyntheticNight(
    seed: Long = 20240219L,
    private val restingHeartRate: Float = 52f,
    /** The sleeper's true underlying cycle length. */
    private val cycleMinutes: Float = 90f,
    private val cycleCount: Int = 6,
    private val sleepLatencyMinutes: Int = 12,
    /** How long the recording runs, whatever the sleeper does. */
    private val totalMinutes: Int = 600,
    private val startMillis: Long = 1_700_000_000_000L,
    /** Set false to model a watch whose heart-rate sensor never reports. */
    private val heartRateAvailable: Boolean = true,
    private val accelHz: Int = 20,
) {
    private val random = Random(seed)

    /** Ground-truth stage for every 30 s epoch. */
    val trueStages: List<SleepStage> = buildHypnogram()

    val epochCount: Int get() = trueStages.size

    /** Wall-clock time the sleeper truly fell asleep. */
    val trueSleepOnsetMillis: Long = startMillis + sleepLatencyMinutes * 60_000L

    /** True end of each cycle, in wall-clock millis. */
    val trueCycleEndMillis: List<Long> = cycleBoundaries()

    /**
     * Run the raw sensor simulation and return the epoch features the watch would have
     * produced. This exercises the real band-pass filters and aggregators.
     */
    fun generateEpochs(): List<EpochFeatures> {
        val builder = EpochFeatureBuilder()
        val out = ArrayList<EpochFeatures>(trueStages.size)

        // Wrist orientation; posture changes rotate it.
        var tiltRadians = 0.35
        var rollRadians = 0.2
        var heartRate = restingHeartRate + 16f

        val sampleIntervalMillis = 1000L / accelHz
        val samplesPerEpoch = (EPOCH_SECONDS * accelHz)

        trueStages.forEachIndexed { epochIndex, stage ->
            val epochStart = startMillis + epochIndex * 30_000L

            if (random.nextFloat() < postureChangeProbability(stage)) {
                tiltRadians += (random.nextDouble() - 0.5) * 1.2
                rollRadians += (random.nextDouble() - 0.5) * 1.2
                tiltRadians = tiltRadians.coerceIn(-1.4, 1.4)
                rollRadians = rollRadians.coerceIn(-1.4, 1.4)
            }

            val bursts = generateBursts(stage)

            for (sample in 0 until samplesPerEpoch) {
                val timestamp = epochStart + sample * sampleIntervalMillis
                val seconds = sample.toDouble() / accelHz

                // Gravity in the watch frame for the current wrist orientation.
                var x = (sin(rollRadians) * cos(tiltRadians) * G).toFloat()
                var y = (-sin(tiltRadians) * G).toFloat()
                var z = (cos(rollRadians) * cos(tiltRadians) * G).toFloat()

                // Movement bursts: damped oscillation in the 1-3 Hz band where limbs move.
                for (burst in bursts) {
                    if (seconds >= burst.startSecond && seconds < burst.startSecond + burst.durationSeconds) {
                        val phase = (seconds - burst.startSecond) / burst.durationSeconds
                        val envelope = sin(PI * phase)
                        val wave = sin(2 * PI * burst.frequencyHz * (seconds - burst.startSecond))
                        val amplitude = (burst.amplitudeG * G * envelope * wave).toFloat()
                        x += amplitude * burst.axisX
                        y += amplitude * burst.axisY
                        z += amplitude * burst.axisZ
                    }
                }

                // Sensor noise.
                x += (gaussian() * NOISE_G * G).toFloat()
                y += (gaussian() * NOISE_G * G).toFloat()
                z += (gaussian() * NOISE_G * G).toFloat()

                builder.addAccelSample(timestamp, x, y, z)
            }

            if (heartRateAvailable) {
                val target = restingHeartRate + heartRateOffset(stage)
                repeat(HR_SAMPLES_PER_EPOCH) { hrSample ->
                    // Heart rate lags its target rather than teleporting to it.
                    heartRate += (target - heartRate) * HR_LAG_ALPHA
                    val jitter = gaussian() * heartRateNoise(stage)
                    val reported = (heartRate + jitter).coerceIn(35f, 140f)
                    val timestamp = epochStart + hrSample * (30_000L / HR_SAMPLES_PER_EPOCH)
                    builder.addHeartRateSample(timestamp, reported)
                }
            }

            out += builder.drain(epochStart + 30_000L)
        }
        out += builder.flush(startMillis + trueStages.size * 30_000L)
        return out
    }

    private fun buildHypnogram(): List<SleepStage> {
        val stages = ArrayList<SleepStage>(totalMinutes * 2)
        repeat(sleepLatencyMinutes * 2) { stages += SleepStage.AWAKE }

        for (cycle in 1..cycleCount) {
            if (stages.size >= totalMinutes * 2) break
            val jitter = 1f + (random.nextFloat() - 0.5f) * 0.12f
            val lengthEpochs = (cycleMinutes * shapeFactor(cycle) * jitter * 2).toInt()

            // Slow-wave sleep is front-loaded; REM periods lengthen through the night.
            val deepFraction = (0.34f - 0.075f * (cycle - 1)).coerceAtLeast(0f)
            val remFraction = (0.08f + 0.052f * (cycle - 1)).coerceAtMost(0.32f)
            val deepEpochs = (lengthEpochs * deepFraction).toInt()
            val remEpochs = (lengthEpochs * remFraction).toInt()
            val lightEpochs = (lengthEpochs - deepEpochs - remEpochs).coerceAtLeast(4)
            val descentEpochs = (lightEpochs * 0.45f).toInt()
            val ascentEpochs = lightEpochs - descentEpochs

            repeat(descentEpochs) { stages += SleepStage.LIGHT }
            repeat(deepEpochs) { stages += SleepStage.DEEP }
            repeat(ascentEpochs) { stages += SleepStage.LIGHT }
            // Brief awakenings cluster around cycle ends, more often late in the night.
            if (cycle >= 3 && random.nextFloat() < 0.45f) {
                repeat(2 + random.nextInt(3)) { stages += SleepStage.AWAKE }
            }
            repeat(remEpochs) { stages += SleepStage.REM }
        }

        // Whatever is left of the recording: fragmented light sleep with REM, as at dawn.
        while (stages.size < totalMinutes * 2) {
            val run = 6 + random.nextInt(14)
            val stage = if (random.nextFloat() < 0.4f) SleepStage.REM else SleepStage.LIGHT
            repeat(run) { if (stages.size < totalMinutes * 2) stages += stage }
        }
        return stages
    }

    private fun cycleBoundaries(): List<Long> {
        val ends = ArrayList<Long>()
        var previous = SleepStage.AWAKE
        var sawRem = false
        trueStages.forEachIndexed { index, stage ->
            if (previous == SleepStage.REM && stage != SleepStage.REM && sawRem) {
                ends += startMillis + index * 30_000L
                sawRem = false
            }
            if (stage == SleepStage.REM) sawRem = true
            previous = stage
        }
        return ends
    }

    private fun shapeFactor(cycle: Int): Float =
        floatArrayOf(0.93f, 0.97f, 1.00f, 1.03f, 1.05f, 1.06f, 1.06f)[(cycle - 1).coerceIn(0, 6)]

    private class Burst(
        val startSecond: Double,
        val durationSeconds: Double,
        val amplitudeG: Double,
        val frequencyHz: Double,
        val axisX: Float,
        val axisY: Float,
        val axisZ: Float,
    )

    private fun generateBursts(stage: SleepStage): List<Burst> {
        val (probability, maxBursts, amplitudeRange, durationRange) = when (stage) {
            SleepStage.AWAKE -> BurstSpec(0.97f, 9, 0.12 to 0.95, 0.8 to 4.5)
            SleepStage.LIGHT -> BurstSpec(0.45f, 2, 0.03 to 0.22, 0.3 to 1.6)
            SleepStage.DEEP -> BurstSpec(0.06f, 1, 0.02 to 0.06, 0.2 to 0.6)
            // REM: muscle atonia, punctuated by the occasional brief twitch.
            SleepStage.REM -> BurstSpec(0.13f, 1, 0.02 to 0.10, 0.15 to 0.5)
            SleepStage.UNKNOWN -> BurstSpec(0f, 0, 0.0 to 0.0, 0.0 to 0.0)
        }
        if (random.nextFloat() > probability || maxBursts == 0) return emptyList()

        val count = 1 + random.nextInt(maxBursts)
        return (0 until count).map {
            val duration = durationRange.first +
                random.nextDouble() * (durationRange.second - durationRange.first)
            val axisAngle = random.nextDouble() * 2 * PI
            val axisTilt = random.nextDouble() * PI
            Burst(
                startSecond = random.nextDouble() * (EPOCH_SECONDS - duration).coerceAtLeast(0.1),
                durationSeconds = duration,
                amplitudeG = amplitudeRange.first +
                    random.nextDouble() * (amplitudeRange.second - amplitudeRange.first),
                frequencyHz = 0.9 + random.nextDouble() * 2.2,
                axisX = (sin(axisTilt) * cos(axisAngle)).toFloat(),
                axisY = (sin(axisTilt) * sin(axisAngle)).toFloat(),
                axisZ = cos(axisTilt).toFloat(),
            )
        }
    }

    private data class BurstSpec(
        val probability: Float,
        val maxBursts: Int,
        val amplitudeRange: Pair<Double, Double>,
        val durationRange: Pair<Double, Double>,
    )

    private fun postureChangeProbability(stage: SleepStage): Float = when (stage) {
        SleepStage.AWAKE -> 0.30f
        SleepStage.LIGHT -> 0.030f
        SleepStage.DEEP -> 0.004f
        SleepStage.REM -> 0.006f
        SleepStage.UNKNOWN -> 0f
    }

    private fun heartRateOffset(stage: SleepStage): Float = when (stage) {
        SleepStage.AWAKE -> 16f
        SleepStage.LIGHT -> 4.5f
        SleepStage.DEEP -> 0f
        SleepStage.REM -> 9.5f
        SleepStage.UNKNOWN -> 8f
    }

    private fun heartRateNoise(stage: SleepStage): Float = when (stage) {
        SleepStage.AWAKE -> 5.5f
        SleepStage.LIGHT -> 2.2f
        SleepStage.DEEP -> 1.1f
        // The defining cardiac signature of REM: an irregular, surging heart rate.
        SleepStage.REM -> 5.0f
        SleepStage.UNKNOWN -> 3f
    }

    /** Box-Muller normal sample. */
    private fun gaussian(): Float {
        val u1 = random.nextDouble().coerceAtLeast(1e-9)
        val u2 = random.nextDouble()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * PI * u2)).toFloat()
    }

    companion object {
        private const val G = 9.80665
        private const val NOISE_G = 0.004f
        private const val EPOCH_SECONDS = 30
        private const val HR_SAMPLES_PER_EPOCH = 3
        private const val HR_LAG_ALPHA = 0.055f
    }
}
