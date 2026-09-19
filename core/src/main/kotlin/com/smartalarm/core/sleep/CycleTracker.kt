package com.smartalarm.core.sleep

import com.smartalarm.core.model.SleepCycle
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.model.StagedEpoch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Detects real NREM-REM sleep cycles as the night happens, and learns how long *this* sleeper's
 * cycles actually are.
 *
 * A sleep cycle is not "90 minutes". It is a descent into NREM followed by a REM period, and
 * the cycle ends when that REM period ends — the classical Feinberg & Floyd definition. Real
 * cycles run 70 to 120 minutes, the first ones are shorter and slow-wave heavy, the last ones
 * are longer and REM heavy, and they vary between people and between nights. So this class
 * detects each boundary from the staged data as it arrives, and the alarm target is recomputed
 * from the cycles that have *actually* happened plus a prediction only for the ones still to
 * come. By the last cycle — the one the alarm cares about — the prediction is anchored on four
 * or five measured cycles from tonight.
 *
 * A cycle is closed when any of these happens:
 *  - a REM period of at least [minRemEpisodeMinutes] ends, meaning no REM for
 *    [remEndGapMinutes] — the textbook boundary;
 *  - the sleeper is awake for [arousalCloseMinutes] well into a cycle, since long awakenings
 *    cluster at cycle ends;
 *  - the cycle has run past [maxCycleMinutes] with no scorable REM, in which case the boundary
 *    is placed at the lightest point of the recent ascent out of NREM.
 */
/** Everything cycle detection knows after one pass over the night. */
data class CycleAnalysis(
    /** Cycles whose boundary has been established. */
    val cycles: List<SleepCycle> = emptyList(),
    /** The cycle in flight, or null before sleep onset. */
    val current: SleepCycle? = null,
    /** 1-based cycle each epoch belongs to, by epoch index; 0 before sleep onset. */
    val epochCycleIndex: Map<Int, Int> = emptyMap(),
    /** The cycle in flight has already had a REM period long enough to end it. */
    val currentCycleHasRem: Boolean = false,
    /** Start of the cycle in flight. */
    val currentCycleStartMillis: Long = 0L,
    /** Persistent sleep onset this analysis was built from. */
    val onsetMillis: Long = 0L,
) {
    val completedCount: Int get() = cycles.size
}

class CycleTracker(
    private val minCycleMinutes: Float = 45f,
    private val maxCycleMinutes: Float = 130f,
    private val minRemEpisodeMinutes: Float = 5f,
    /**
     * The night's first REM period is routinely only a few minutes long, and the classical
     * cycle criteria make an explicit exception for it. Holding it to the full five minutes
     * merges the first two cycles into one.
     */
    private val minFirstRemEpisodeMinutes: Float = 3f,
    private val remGapToleranceMinutes: Float = 2f,
    private val remEndGapMinutes: Float = 5f,
    private val minNremBeforeRemMinutes: Float = 15f,
    private val arousalCloseMinutes: Float = 10f,
    private val arousalMinCycleMinutes: Float = 40f,
    seedCycleMinutes: Float = 90f,
    private val seedWeight: Float = 1.5f,
) {
    private val seedBase = seedCycleMinutes.coerceIn(MIN_BASE, MAX_BASE)

    /**
     * Find every cycle boundary in the night so far.
     *
     * A single forward pass over the staged epochs: accumulate a cycle, watch for the REM
     * period that ends it, and start the next one at the boundary. Recomputed from scratch on
     * every new epoch, so a stage the classifier revises later moves the boundary with it
     * rather than leaving a stale cycle behind.
     */
    fun analyze(epochs: List<StagedEpoch>, onsetMillis: Long?): CycleAnalysis {
        val onset: Long = onsetMillis ?: return CycleAnalysis()

        val completed = ArrayList<SleepCycle>(8)
        val epochCycleIndex = HashMap<Int, Int>(epochs.size)
        var cycleStart: Long = onset
        var currentEpochs = ArrayList<StagedEpoch>(300)

        var remRunEpochs = 0
        var gapEpochs = 0
        var remEstablished = false
        var lastRemEnd = 0L
        var awakeRunEpochs = 0
        var awakeRunStart = 0L

        fun resetCycleState() {
            remRunEpochs = 0
            gapEpochs = 0
            remEstablished = false
            lastRemEnd = 0L
            awakeRunEpochs = 0
            awakeRunStart = 0L
        }

        /** Replay the epochs carried past a boundary so the new cycle starts coherent. */
        fun replay(list: List<StagedEpoch>) {
            list.forEach { epoch ->
                if (epoch.stage == SleepStage.AWAKE) {
                    if (awakeRunEpochs == 0) awakeRunStart = epoch.features.startMillis
                    awakeRunEpochs++
                } else {
                    awakeRunEpochs = 0
                }
                if (epoch.stage == SleepStage.REM) {
                    remRunEpochs++
                    gapEpochs = 0
                    lastRemEnd = epoch.features.endMillis
                } else if (remRunEpochs > 0) {
                    gapEpochs++
                }
            }
        }

        for (epoch in epochs) {
            if (epoch.features.endMillis <= cycleStart) continue
            currentEpochs += epoch
            epochCycleIndex[epoch.index] = completed.size + 1

            val stage = epoch.stage
            val elapsedMinutes = (epoch.features.endMillis - cycleStart) / 60_000f

            if (stage == SleepStage.REM) {
                val gapMinutes = gapEpochs * EPOCH_MINUTES
                remRunEpochs = if (remRunEpochs > 0 && gapMinutes <= remGapToleranceMinutes) {
                    // A brief non-REM blip inside a REM period does not end the period.
                    remRunEpochs + gapEpochs + 1
                } else {
                    1
                }
                gapEpochs = 0
                lastRemEnd = epoch.features.endMillis
                val remRunStart = lastRemEnd - (remRunEpochs * EPOCH_MINUTES * 60_000f).toLong()
                val nremBefore = currentEpochs.count {
                    it.features.endMillis <= remRunStart &&
                        (it.stage == SleepStage.LIGHT || it.stage == SleepStage.DEEP)
                } * EPOCH_MINUTES
                val requiredRemMinutes =
                    if (completed.isEmpty()) minFirstRemEpisodeMinutes else minRemEpisodeMinutes
                if (remRunEpochs * EPOCH_MINUTES >= requiredRemMinutes &&
                    nremBefore >= minNremBeforeRemMinutes
                ) {
                    remEstablished = true
                }
            } else if (remRunEpochs > 0) {
                gapEpochs++
            }

            if (stage == SleepStage.AWAKE) {
                if (awakeRunEpochs == 0) awakeRunStart = epoch.features.startMillis
                awakeRunEpochs++
            } else {
                awakeRunEpochs = 0
            }

            // Boundary tests, in priority order.
            val boundary: Pair<Long, String>? = when {
                remEstablished &&
                    gapEpochs * EPOCH_MINUTES >= remEndGapMinutes &&
                    (lastRemEnd - cycleStart) / 60_000f >= minCycleMinutes ->
                    lastRemEnd to REASON_REM_ENDED

                awakeRunEpochs * EPOCH_MINUTES >= arousalCloseMinutes &&
                    (awakeRunStart - cycleStart) / 60_000f >= arousalMinCycleMinutes ->
                    awakeRunStart to REASON_AWAKENING

                elapsedMinutes >= maxCycleMinutes ->
                    (lightestPointInRecentAscent(currentEpochs) ?: epoch.features.endMillis) to
                        REASON_OVERRAN

                else -> null
            }

            if (boundary != null && (boundary.first - cycleStart) / 60_000f >= minCycleMinutes) {
                val inside = currentEpochs.filter { it.features.startMillis < boundary.first }
                val after = currentEpochs.filter { it.features.startMillis >= boundary.first }
                completed += buildCycle(
                    index = completed.size + 1,
                    startMillis = cycleStart,
                    endMillis = boundary.first,
                    epochs = inside,
                    complete = true,
                    remEndMillis = lastRemEnd.takeIf { it > 0L },
                    reason = boundary.second,
                )
                cycleStart = boundary.first
                currentEpochs = ArrayList(after)
                after.forEach { epochCycleIndex[it.index] = completed.size + 1 }
                resetCycleState()
                replay(after)
            } else if (boundary != null) {
                // Too short to stand as a cycle; keep accumulating rather than inventing one.
                resetCycleState()
            }
        }

        val current = if (currentEpochs.isEmpty()) {
            null
        } else {
            buildCycle(
                index = completed.size + 1,
                startMillis = cycleStart,
                endMillis = currentEpochs.last().features.endMillis,
                epochs = currentEpochs,
                complete = false,
                remEndMillis = lastRemEnd.takeIf { it > 0L },
                reason = "",
            )
        }

        return CycleAnalysis(
            cycles = completed,
            current = current,
            epochCycleIndex = epochCycleIndex,
            currentCycleHasRem = remEstablished,
            currentCycleStartMillis = cycleStart,
            onsetMillis = onset,
        )
    }

    /**
     * Expected length of cycle [index] (1-based) for this sleeper, in minutes.
     *
     * Built from the cycles measured tonight, corrected for the well-known shape of the night:
     * early cycles run short because they are slow-wave dominated, later ones run long because
     * their REM periods lengthen.
     */
    fun predictedCycleMinutes(index: Int, analysis: CycleAnalysis): Float =
        (personalBaseMinutes(analysis) * shapeFactor(index))
            .coerceIn(MIN_CYCLE_PREDICTION, MAX_CYCLE_PREDICTION)

    /**
     * The sleeper's underlying cycle length, with tonight's measurements folded in.
     *
     * Only cycles whose boundary was drawn at the end of an actual REM period count as
     * measurements. A cycle that was closed because it overran, or because of a long
     * awakening, tells us where the night was cut, not how long this sleeper's cycles run —
     * folding those in would drag the estimate towards the cut-off length instead of the truth.
     */
    fun personalBaseMinutes(analysis: CycleAnalysis): Float {
        val measured = analysis.cycles.filter { it.boundaryReason == REASON_REM_ENDED }
        var weightedSum = seedBase * seedWeight
        var weight = seedWeight
        measured.forEachIndexed { position, cycle ->
            // Later cycles are the better guide to the cycles still to come.
            val w = 1f + position * RECENCY_WEIGHT_STEP
            weightedSum += (cycle.durationMinutes / shapeFactor(cycle.index)) * w
            weight += w
        }
        return (weightedSum / weight).coerceIn(MIN_BASE, MAX_BASE)
    }

    /** How many cycle boundaries tonight were drawn from a real REM period ending. */
    fun measuredCycleCount(analysis: CycleAnalysis): Int =
        analysis.cycles.count { it.boundaryReason == REASON_REM_ENDED }

    /** Mean measured cycle length tonight, for display. Null until a cycle completes. */
    fun measuredMeanCycleMinutes(analysis: CycleAnalysis): Float? =
        if (analysis.cycles.isEmpty()) null
        else analysis.cycles.map { it.durationMinutes }.average().toFloat()

    /**
     * When cycle [target] is expected to end, in wall-clock millis.
     *
     * The estimate is anchored on the last boundary we actually *measured* — the end of a real
     * REM period — and predicts forward from there. Cycles closed by a fallback rule are not
     * anchors: a cycle that was cut off at the two-hour mark because no REM could be scored
     * says nothing about where the next one ends, and treating it as a measurement would drag
     * every later prediction along with the cut-off.
     *
     * With no measured boundary at all — a watch whose heart-rate sensor is silent, say — this
     * degrades cleanly to dead reckoning from sleep onset using the sleeper's profile, which
     * is what a conventional sleep-cycle alarm does for the whole night.
     */
    fun projectEndOfCycle(target: Int, nowMillis: Long, analysis: CycleAnalysis): Long? {
        if (analysis.onsetMillis == 0L) return null

        val measured = analysis.cycles.filter { it.boundaryReason == REASON_REM_ENDED }
        if (measured.size >= target) return measured[target - 1].endMillis

        var cursor = measured.lastOrNull()?.endMillis ?: analysis.onsetMillis
        for (index in (measured.size + 1)..target) {
            cursor += (predictedCycleMinutes(index, analysis) * 60_000f).toLong()
        }
        // A cycle running long pushes the target out rather than into the past.
        return maxOf(cursor, nowMillis)
    }

    /** Progress through the cycle in flight, 0..2 (values above 1 mean it has overrun). */
    fun currentCyclePhase(nowMillis: Long, analysis: CycleAnalysis): Float {
        if (analysis.currentCycleStartMillis == 0L) return 0f
        val elapsed = (nowMillis - analysis.currentCycleStartMillis) / 60_000f
        val expected = predictedCycleMinutes(analysis.cycles.size + 1, analysis)
        return (elapsed / expected).coerceIn(0f, 2f)
    }

    /**
     * When a cycle overruns with no scorable REM, put the boundary at the lightest sleep in the
     * recent past: that ascent out of NREM is where the REM period should have been.
     */
    private fun lightestPointInRecentAscent(epochs: List<StagedEpoch>): Long? =
        epochs.takeLast(ASCENT_WINDOW_EPOCHS).minByOrNull { it.stage.depth }?.features?.endMillis

    private fun buildCycle(
        index: Int,
        startMillis: Long,
        endMillis: Long,
        epochs: List<StagedEpoch>,
        complete: Boolean,
        remEndMillis: Long?,
        reason: String,
    ): SleepCycle = SleepCycle(
        index = index,
        startMillis = startMillis,
        endMillis = endMillis,
        remEndMillis = remEndMillis,
        complete = complete,
        lightMinutes = epochs.count { it.stage == SleepStage.LIGHT } * EPOCH_MINUTES,
        deepMinutes = epochs.count { it.stage == SleepStage.DEEP } * EPOCH_MINUTES,
        remMinutes = epochs.count { it.stage == SleepStage.REM } * EPOCH_MINUTES,
        awakeMinutes = epochs.count { it.stage == SleepStage.AWAKE } * EPOCH_MINUTES,
        boundaryReason = reason,
    )

    /**
     * How long cycle [index] runs relative to the sleeper's base cycle. Derived from the
     * standard shape of a night: slow-wave heavy early cycles are shorter, REM heavy late ones
     * are longer.
     */
    private fun shapeFactor(index: Int): Float = SHAPE[(index - 1).coerceIn(0, SHAPE.size - 1)]

    companion object {
        private const val EPOCH_MINUTES = 0.5f
        private val SHAPE = floatArrayOf(0.93f, 0.97f, 1.00f, 1.03f, 1.05f, 1.06f, 1.06f)
        private const val RECENCY_WEIGHT_STEP = 0.35f
        private const val MIN_BASE = 65f
        private const val MAX_BASE = 120f
        private const val MIN_CYCLE_PREDICTION = 60f
        private const val MAX_CYCLE_PREDICTION = 135f
        private const val ASCENT_WINDOW_EPOCHS = 40

        /** The textbook boundary: a REM period ended. Only these count as measurements. */
        const val REASON_REM_ENDED = "REM period ended"
        const val REASON_AWAKENING = "long awakening"
        const val REASON_OVERRAN = "cycle overran without REM"

        /** Round a minute figure for display. */
        fun displayMinutes(minutes: Float): Int = minutes.roundToInt()
    }
}
