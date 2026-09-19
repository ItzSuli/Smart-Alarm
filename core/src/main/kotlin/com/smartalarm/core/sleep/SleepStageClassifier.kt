package com.smartalarm.core.sleep

import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.SleepStage
import kotlin.math.exp
import kotlin.math.ln

/**
 * Online hidden-Markov stage classifier.
 *
 * Each 30 s epoch gets an emission score per stage from four features, the stages are tied
 * together by a transition matrix that encodes which stage changes are physiologically
 * plausible, and the best path is recovered with Viterbi after every epoch. Running Viterbi
 * rather than classifying epochs independently is what stops the output flickering between
 * DEEP and REM every thirty seconds, and it is what lets a later epoch correct an earlier one.
 *
 * The four features and what separates the stages:
 *
 * | stage | movement          | heart rate        | HR variability | posture changes |
 * |-------|-------------------|-------------------|----------------|-----------------|
 * | AWAKE | high              | high              | high           | frequent        |
 * | LIGHT | small, frequent   | mid               | mid            | occasional      |
 * | DEEP  | almost none       | at nightly floor  | low            | almost none     |
 * | REM   | none (atonia)     | raised, irregular | high           | almost none     |
 *
 * DEEP and REM look identical to an accelerometer — both are motionless — so the cardiac
 * features are doing all the work of telling them apart. Without a heart-rate signal the model
 * degrades gracefully to LIGHT/AWAKE plus the ultradian prior, and reports lower confidence.
 *
 * On top of the emissions sit two priors that make the model *cycle aware*:
 *  - slow-wave pressure decays exponentially through the night, so DEEP is expected early;
 *  - REM propensity rises through the night and peaks late within each cycle, and is suppressed
 *    entirely during the first REM-latency minutes after falling asleep.
 */
class SleepStageClassifier(
    private val baseline: NightBaseline,
) {
    /**
     * Stage the whole night.
     *
     * Every call rebuilds the trellis from scratch. That is deliberate: the baseline these
     * emissions are measured against keeps sharpening as the night accumulates, so re-running
     * the night against the current baseline lets a 3 a.m. reading correct a midnight one. A
     * ten-hour night is 1200 epochs and four states, which is a few hundred microseconds of
     * work once every thirty seconds.
     *
     * @param features every epoch of the night, in order.
     * @param onsetMillis persistent sleep onset, or null if the sleeper is still awake.
     * @param scoredAsleep Cole-Kripke's independent sleep/wake verdict, by epoch index.
     * @param cyclePhaseAt progress through the cycle in flight at a given time, 0..1, taken
     *   from the previous pass's cycle detection. It feeds the prior that puts REM at the end
     *   of a cycle, which is what makes the staging cycle-aware.
     */
    fun classify(
        features: List<EpochFeatures>,
        onsetMillis: Long?,
        scoredAsleep: (Int) -> Boolean,
        cyclePhaseAt: (Long) -> Float,
    ): Classification {
        val n = features.size
        if (n == 0) return Classification(emptyList(), emptyList())

        val stateCount = STATES.size
        val trellis = Array(n) { FloatArray(stateCount) }
        val backPointers = Array(n) { IntArray(stateCount) }
        val posteriors = Array(n) { FloatArray(stateCount) }

        for (index in 0 until n) {
            val epoch = features[index]
            val minutesSinceOnset =
                if (onsetMillis != null && epoch.startMillis >= onsetMillis) {
                    (epoch.startMillis - onsetMillis) / 60_000f
                } else {
                    null
                }
            val emissions = emissionLogProbabilities(
                features = epoch,
                minutesSinceOnset = minutesSinceOnset,
                cyclePhase = cyclePhaseAt(epoch.startMillis),
                scoredAsleep = scoredAsleep(index),
            )
            posteriors[index] = softmax(emissions)

            if (index == 0) {
                for (state in 0 until stateCount) {
                    trellis[0][state] = INITIAL_LOG_PROBS[state] + emissions[state]
                    backPointers[0][state] = -1
                }
            } else {
                val previous = trellis[index - 1]
                for (state in 0 until stateCount) {
                    var bestScore = Float.NEGATIVE_INFINITY
                    var bestFrom = 0
                    for (from in 0 until stateCount) {
                        val candidate = previous[from] + TRANSITION_LOG[from][state]
                        if (candidate > bestScore) {
                            bestScore = candidate
                            bestFrom = from
                        }
                    }
                    trellis[index][state] = bestScore + emissions[state]
                    backPointers[index][state] = bestFrom
                }
            }
            // Keep the trellis numerically sane over a ten-hour night.
            val maxScore = trellis[index].max()
            for (state in 0 until stateCount) trellis[index][state] -= maxScore
        }

        val path = IntArray(n)
        var state = trellis[n - 1].indices.maxByOrNull { trellis[n - 1][it] } ?: 0
        path[n - 1] = state
        for (index in n - 1 downTo 1) {
            state = backPointers[index][state]
            path[index - 1] = state
        }

        val stages = ArrayList<SleepStage>(n)
        val confidences = ArrayList<Float>(n)
        for (index in 0 until n) {
            stages += STATES[path[index]]
            val raw = posteriors[index][path[index]]
            // Without a heart-rate reading, DEEP and REM are barely separable; say so.
            confidences += if (features[index].hasHeartRate) raw else raw * NO_HR_CONFIDENCE_PENALTY
        }
        return Classification(stages, confidences)
    }

    /** The staged night. */
    data class Classification(
        val stages: List<SleepStage>,
        val confidences: List<Float>,
    )

    /**
     * Gaussian log-likelihood of the epoch's features under each stage's template, plus the
     * ultradian priors.
     */
    private fun emissionLogProbabilities(
        features: EpochFeatures,
        minutesSinceOnset: Float?,
        cyclePhase: Float,
        scoredAsleep: Boolean,
    ): FloatArray {
        val activity = baseline.logActivity(features.activityCount)
        val moving = baseline.logMovingSeconds(features.movingSeconds)
        val hasHr = features.hasHeartRate
        val heartRate = if (hasHr) baseline.normalizeHeartRate(features.heartRate) else NEUTRAL
        val hrv = if (hasHr && features.hrvProxyMs > 0f) baseline.normalizeHrv(features.hrvProxyMs) else NEUTRAL
        val angleChange = features.wristAngleChange

        val out = FloatArray(STATES.size)
        for (s in STATES.indices) {
            val template = TEMPLATES[s]
            var logLikelihood = BASE_RATE_LOG_PRIOR[s] * BASE_RATE_WEIGHT
            logLikelihood += gaussianLogPdf(activity, template.activityMean, template.activitySd) * ACTIVITY_WEIGHT
            logLikelihood += gaussianLogPdf(moving, template.movingMean, template.movingSd) * MOVING_WEIGHT
            logLikelihood += gaussianLogPdf(angleChange, template.angleMean, template.angleSd) * ANGLE_WEIGHT
            if (hasHr) {
                logLikelihood += gaussianLogPdf(heartRate, template.hrMean, template.hrSd) * HR_WEIGHT
                logLikelihood += gaussianLogPdf(hrv, template.hrvMean, template.hrvSd) * HRV_WEIGHT
            }
            out[s] = logLikelihood
        }

        applyPriors(out, minutesSinceOnset, cyclePhase, scoredAsleep, features)
        return out
    }

    private fun applyPriors(
        scores: FloatArray,
        minutesSinceOnset: Float?,
        cyclePhase: Float,
        scoredAsleep: Boolean,
        features: EpochFeatures,
    ) {
        val awake = indexOf(SleepStage.AWAKE)
        val deep = indexOf(SleepStage.DEEP)
        val rem = indexOf(SleepStage.REM)
        val light = indexOf(SleepStage.LIGHT)

        if (minutesSinceOnset == null) {
            // Not asleep yet: everything but AWAKE is heavily penalised.
            scores[deep] += PRE_ONSET_PENALTY
            scores[rem] += PRE_ONSET_PENALTY
            scores[light] += PRE_ONSET_PENALTY * 0.25f
            return
        }

        val hours = minutesSinceOnset / 60f

        // Slow-wave pressure dissipates roughly exponentially across the night: deep sleep is
        // concentrated in the first two cycles and becomes rare after about five hours.
        val deepPropensity = exp(-hours / SWS_DECAY_HOURS)
        scores[deep] += ln(deepPropensity.coerceAtLeast(MIN_PRIOR)) * DEEP_PRIOR_WEIGHT

        // REM is the mirror image: suppressed during REM latency, then rising all night.
        val latencyRamp = smoothStep(minutesSinceOnset, REM_LATENCY_MIN_MINUTES, REM_LATENCY_FULL_MINUTES)
        val nightlyRemRise = 1f - exp(-hours / REM_RISE_HOURS)
        // Within a cycle, REM lives at the end: phase 0.6-1.0.
        val phaseBoost = smoothStep(cyclePhase, REM_PHASE_START, REM_PHASE_FULL)
        val remPropensity = (latencyRamp * (REM_BASE + nightlyRemRise) * (REM_PHASE_FLOOR + phaseBoost))
            .coerceAtLeast(MIN_PRIOR)
        scores[rem] += ln(remPropensity) * REM_PRIOR_WEIGHT

        // Cole-Kripke is a well-validated independent opinion on sleep versus wake; let it pull.
        if (scoredAsleep) {
            scores[awake] += SLEEP_SCORED_AWAKE_PENALTY
        } else {
            scores[awake] += WAKE_SCORED_AWAKE_BONUS
            scores[deep] += WAKE_SCORED_SLEEP_PENALTY
            scores[rem] += WAKE_SCORED_SLEEP_PENALTY
        }

        // Deep and REM sleep are told apart from quiet light sleep almost entirely by the
        // heart. With no cardiac reading there is no evidence for either, and claiming them
        // anyway would be invention: fall back to the light/awake distinction that movement
        // alone can genuinely support.
        if (!features.hasHeartRate) {
            scores[deep] += NO_HEART_RATE_PENALTY
            scores[rem] += NO_HEART_RATE_PENALTY
        }

        // The watch is off the wrist: it is not sleep data, whatever it looks like.
        if (features.offWrist) {
            scores[deep] += OFF_WRIST_PENALTY
            scores[rem] += OFF_WRIST_PENALTY
            scores[light] += OFF_WRIST_PENALTY
        }
    }

    private fun indexOf(stage: SleepStage): Int = STATES.indexOf(stage)

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.max()
        var sum = 0f
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            out[i] = exp(logits[i] - maxLogit)
            sum += out[i]
        }
        if (sum <= 0f) return FloatArray(logits.size) { 1f / logits.size }
        for (i in out.indices) out[i] /= sum
        return out
    }

    /** Log of an unnormalised Gaussian; the constant term is irrelevant to the argmax. */
    private fun gaussianLogPdf(value: Float, mean: Float, sd: Float): Float {
        val z = (value - mean) / sd
        return -0.5f * z * z - ln(sd)
    }

    /** 0 below [from], 1 above [to], smooth in between. */
    private fun smoothStep(value: Float, from: Float, to: Float): Float {
        if (to <= from) return if (value >= to) 1f else 0f
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Per-stage feature template: the mean and spread each feature takes in that stage. */
    private data class Template(
        val activityMean: Float, val activitySd: Float,
        val movingMean: Float, val movingSd: Float,
        val hrMean: Float, val hrSd: Float,
        val hrvMean: Float, val hrvSd: Float,
        val angleMean: Float, val angleSd: Float,
    )

    companion object {
        /** Stage order used by every array in this class. */
        private val STATES = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)

        /**
         * Feature templates, in the order AWAKE, REM, LIGHT, DEEP. Columns are
         * (log activity, log moving seconds, normalised heart rate, normalised HRV, tilt change).
         *
         * Heart rate is expressed on the sleeper's own scale — 0 is their nocturnal floor and 1
         * is quiet wakefulness — which is why the same numbers work for an athlete with a
         * resting pulse of 42 and someone sitting at 70.
         */
        private val TEMPLATES = arrayOf(
            // AWAKE: sustained movement, heart raised, posture shifting.
            Template(2.80f, 1.10f, 2.00f, 0.95f, 0.95f, 0.30f, 0.85f, 0.45f, 11f, 13f),
            // REM: as motionless as deep sleep, but the heart is active and irregular.
            Template(0.12f, 0.20f, 0.03f, 0.16f, 0.58f, 0.22f, 0.92f, 0.34f, 1.1f, 2.6f),
            // LIGHT: small brief movements, everything else mid-range.
            Template(0.34f, 0.42f, 0.14f, 0.34f, 0.32f, 0.22f, 0.46f, 0.30f, 2.4f, 3.8f),
            // DEEP: the stillest, heart at its nightly floor and barely varying.
            Template(0.10f, 0.17f, 0.02f, 0.13f, 0.07f, 0.17f, 0.28f, 0.26f, 0.5f, 1.6f),
        )

        /**
         * Log base rates of each stage across a typical adult night — roughly 10% awake, 22%
         * REM, 50% light, 18% deep. Without this the tightly-peaked DEEP and REM templates win
         * every quiet epoch on density alone, and light sleep, which is half of a real night,
         * gets squeezed out.
         */
        private val BASE_RATE_LOG_PRIOR = floatArrayOf(ln(0.10f), ln(0.22f), ln(0.50f), ln(0.18f))

        /**
         * log P(next | current). Encodes the sequence rules of sleep: you reach REM through
         * light sleep and never straight from deep sleep, deep sleep is sticky, and every stage
         * mostly persists from one 30 s epoch to the next.
         */
        private val TRANSITION_LOG: Array<FloatArray> = arrayOf(
            //           AWAKE    REM     LIGHT    DEEP
            floatArrayOf(0.700f, 0.010f, 0.280f, 0.010f), // from AWAKE
            floatArrayOf(0.050f, 0.845f, 0.100f, 0.005f), // from REM
            floatArrayOf(0.045f, 0.065f, 0.810f, 0.080f), // from LIGHT
            floatArrayOf(0.015f, 0.005f, 0.130f, 0.850f), // from DEEP
        ).map { row -> FloatArray(row.size) { ln(row[it]) } }.toTypedArray()

        private val INITIAL_LOG_PROBS = floatArrayOf(ln(0.90f), ln(0.01f), ln(0.08f), ln(0.01f))

        // Relative influence of each feature family on the emission score.
        private const val ACTIVITY_WEIGHT = 1.0f
        private const val MOVING_WEIGHT = 0.9f
        private const val HR_WEIGHT = 0.95f
        private const val HRV_WEIGHT = 0.50f
        private const val ANGLE_WEIGHT = 0.30f
        private const val BASE_RATE_WEIGHT = 0.6f

        // Prior strengths, in log-probability units.
        private const val DEEP_PRIOR_WEIGHT = 0.90f
        private const val REM_PRIOR_WEIGHT = 1.10f
        private const val PRE_ONSET_PENALTY = -6.0f
        private const val SLEEP_SCORED_AWAKE_PENALTY = -2.2f
        private const val WAKE_SCORED_AWAKE_BONUS = 1.6f
        private const val WAKE_SCORED_SLEEP_PENALTY = -1.2f
        private const val OFF_WRIST_PENALTY = -3.0f
        private const val NO_HEART_RATE_PENALTY = -1.8f

        private const val SWS_DECAY_HOURS = 3.6f
        private const val REM_RISE_HOURS = 3.2f
        private const val REM_BASE = 0.35f
        private const val REM_LATENCY_MIN_MINUTES = 25f
        private const val REM_LATENCY_FULL_MINUTES = 55f
        private const val REM_PHASE_START = 0.45f
        private const val REM_PHASE_FULL = 0.95f
        private const val REM_PHASE_FLOOR = 0.30f
        private const val MIN_PRIOR = 1e-4f

        private const val NEUTRAL = 0.5f
        private const val NO_HR_CONFIDENCE_PENALTY = 0.55f

        /**
         * The newest epochs are the ones the Viterbi path is most likely to revise, since they
         * have no future context yet. They are still shown, just flagged as provisional.
         */
        const val PROVISIONAL_EPOCHS = 4
    }
}

