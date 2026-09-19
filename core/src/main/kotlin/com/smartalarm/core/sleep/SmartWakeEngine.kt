package com.smartalarm.core.sleep

import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.model.StagedEpoch
import kotlin.math.roundToInt

/** The engine's verdict for one moment. */
data class WakeDecision(
    val shouldWake: Boolean,
    /** Projected end of the requested cycle, updated live from the cycles actually measured. */
    val targetMillis: Long,
    /** Earliest moment smart wake is allowed to fire. */
    val windowStartMillis: Long,
    /** Hard deadline; the alarm always fires by here. */
    val windowEndMillis: Long,
    /** How good this moment is to wake up, 0..1. */
    val score: Float,
    /** The bar [score] had to clear right now; it falls as the deadline approaches. */
    val threshold: Float,
    val stage: SleepStage,
    val reason: String,
) {
    val wakeQualityPercent: Int get() = (score * 100f).roundToInt().coerceIn(0, 100)
}

/**
 * Decides the single best moment to fire the alarm.
 *
 * The target is the projected end of the requested cycle — recomputed continuously by
 * [CycleTracker] from the cycles this night actually produced. Around it sits a window: smart
 * wake may fire from [AlarmPlan.windowBeforeMinutes] early, and never fires later than
 * [AlarmPlan.windowAfterMinutes] past it.
 *
 * Inside the window the engine scores every epoch on how pleasant waking now would be, and
 * compares it against a bar that starts high and falls to zero at the deadline. Early in the
 * window only a genuinely good moment — light sleep, an ascent out of NREM, the tail of a REM
 * period, a natural micro-arousal — is accepted. As the deadline nears the bar drops until any
 * moment will do. The result is that on a normal night you are woken out of light sleep several
 * minutes early, and on a bad night you are still woken on time.
 */
class SmartWakeEngine(private val plan: AlarmPlan) {

    private var firedAtMillis: Long? = null

    val hasFired: Boolean get() = firedAtMillis != null

    fun markFired(atMillis: Long) {
        if (firedAtMillis == null) firedAtMillis = atMillis
    }

    /**
     * @param nowMillis current wall-clock time.
     * @param targetMillis projected end of the requested cycle.
     * @param recentEpochs finalized epochs, most recent last. Only the tail is used.
     * @param completedCycles cycles finished so far tonight.
     * @param currentCycleHasRem whether the cycle in flight has already had its REM period.
     */
    fun evaluate(
        nowMillis: Long,
        targetMillis: Long,
        recentEpochs: List<StagedEpoch>,
        completedCycles: Int,
        currentCycleHasRem: Boolean,
    ): WakeDecision {
        val windowStart = targetMillis - plan.windowBeforeMinutes * 60_000L
        val windowEnd = targetMillis + plan.windowAfterMinutes * 60_000L
        val stage = recentEpochs.lastOrNull()?.stage ?: SleepStage.UNKNOWN

        if (!plan.smartWakeEnabled) {
            return WakeDecision(
                shouldWake = nowMillis >= targetMillis,
                targetMillis = targetMillis,
                windowStartMillis = targetMillis,
                windowEndMillis = targetMillis,
                score = stage.wakeFriendliness,
                threshold = 0f,
                stage = stage,
                reason = if (nowMillis >= targetMillis) "Exact time reached" else "Waiting for exact time",
            )
        }

        if (nowMillis >= windowEnd) {
            return WakeDecision(
                shouldWake = true, targetMillis = targetMillis,
                windowStartMillis = windowStart, windowEndMillis = windowEnd,
                score = stage.wakeFriendliness, threshold = 0f, stage = stage,
                reason = "Deadline reached",
            )
        }

        val score = scoreMoment(nowMillis, recentEpochs, completedCycles, currentCycleHasRem)

        if (nowMillis < windowStart) {
            return WakeDecision(
                shouldWake = false, targetMillis = targetMillis,
                windowStartMillis = windowStart, windowEndMillis = windowEnd,
                score = score, threshold = 1f, stage = stage,
                reason = "Smart wake window has not opened",
            )
        }

        val threshold = thresholdAt(nowMillis, windowStart, windowEnd)
        val shouldWake = score >= threshold
        return WakeDecision(
            shouldWake = shouldWake,
            targetMillis = targetMillis,
            windowStartMillis = windowStart,
            windowEndMillis = windowEnd,
            score = score,
            threshold = threshold,
            stage = stage,
            reason = if (shouldWake) describeGoodMoment(stage, recentEpochs, completedCycles)
            else "Holding for a lighter moment",
        )
    }

    /**
     * The bar a moment has to clear. [START_THRESHOLD] at the window's opening, easing down to
     * zero at the deadline, so the engine grows steadily less fussy as time runs out.
     */
    private fun thresholdAt(nowMillis: Long, windowStart: Long, windowEnd: Long): Float {
        val span = (windowEnd - windowStart).toFloat().coerceAtLeast(1f)
        val progress = ((nowMillis - windowStart) / span).coerceIn(0f, 1f)
        // Ease-in: holds out for a genuinely good moment through most of the window, then
        // concedes quickly as the deadline closes in. A linear or ease-out bar drops so fast
        // that the alarm settles for the first mediocre moment and wakes you needlessly early.
        val eased = progress * progress
        return (START_THRESHOLD * (1f - eased)).coerceIn(0f, 1f)
    }

    /** How good it would be to be woken right now, 0..1. */
    private fun scoreMoment(
        nowMillis: Long,
        recentEpochs: List<StagedEpoch>,
        completedCycles: Int,
        currentCycleHasRem: Boolean,
    ): Float {
        val current = recentEpochs.lastOrNull() ?: return 0f
        var score = current.stage.wakeFriendliness

        val tail = recentEpochs.takeLast(TREND_EPOCHS)

        // Ascending out of deeper sleep is the natural way up.
        if (tail.size >= 3) {
            val older = tail.take(tail.size / 2).map { it.stage.depth }.average()
            val newer = tail.drop(tail.size / 2).map { it.stage.depth }.average()
            if (newer < older - DEPTH_TREND_EPSILON) score += ASCENDING_BONUS
            if (newer > older + DEPTH_TREND_EPSILON) score -= DESCENDING_PENALTY
        }

        // The tail of a REM period is the true cycle boundary: the best moment of the night.
        val sinceRem = epochsSinceStage(recentEpochs, SleepStage.REM)
        if (sinceRem in 1..REM_JUST_ENDED_EPOCHS && current.stage != SleepStage.REM) {
            score += REM_JUST_ENDED_BONUS
        }

        // Do not cut a REM period short; let it finish.
        if (current.stage == SleepStage.REM) {
            val remRun = trailingRunLength(recentEpochs, SleepStage.REM)
            if (remRun * EPOCH_MINUTES < REM_PROTECT_MINUTES) score -= REM_INTERRUPT_PENALTY
        }

        // A natural micro-arousal: movement while already in light sleep. Ride it.
        if (current.stage == SleepStage.LIGHT && current.features.movingSeconds > AROUSAL_MOVING_SECONDS) {
            score += AROUSAL_BONUS
        }

        // The requested number of cycles has genuinely completed: this is what was asked for.
        if (completedCycles >= plan.cycles) score += CYCLE_COMPLETE_BONUS

        // The cycle in flight has had its REM and is winding down; a boundary is imminent.
        if (currentCycleHasRem && current.stage != SleepStage.REM) score += POST_REM_BONUS

        // Low confidence means the stage guess may be wrong; do not act boldly on it.
        if (current.confidence < LOW_CONFIDENCE) score -= LOW_CONFIDENCE_PENALTY

        return score.coerceIn(0f, 1f)
    }

    private fun describeGoodMoment(
        stage: SleepStage,
        recentEpochs: List<StagedEpoch>,
        completedCycles: Int,
    ): String = when {
        completedCycles >= plan.cycles && stage != SleepStage.DEEP ->
            "Cycle ${plan.cycles} complete, in ${stage.label()}"
        epochsSinceStage(recentEpochs, SleepStage.REM) in 1..REM_JUST_ENDED_EPOCHS ->
            "REM period just ended"
        stage == SleepStage.AWAKE -> "Already stirring"
        stage == SleepStage.LIGHT -> "Light sleep, easy to wake"
        else -> "Best moment left in the window"
    }

    private fun epochsSinceStage(epochs: List<StagedEpoch>, stage: SleepStage): Int {
        for (offset in epochs.indices.reversed()) {
            if (epochs[offset].stage == stage) return epochs.size - 1 - offset
        }
        return Int.MAX_VALUE
    }

    private fun trailingRunLength(epochs: List<StagedEpoch>, stage: SleepStage): Int {
        var run = 0
        for (offset in epochs.indices.reversed()) {
            if (epochs[offset].stage == stage) run++ else break
        }
        return run
    }

    companion object {
        private const val EPOCH_MINUTES = 0.5f
        private const val START_THRESHOLD = 0.80f
        private const val TREND_EPOCHS = 10
        private const val DEPTH_TREND_EPSILON = 0.12
        private const val ASCENDING_BONUS = 0.12f
        private const val DESCENDING_PENALTY = 0.10f
        private const val REM_JUST_ENDED_EPOCHS = 6
        private const val REM_JUST_ENDED_BONUS = 0.18f
        private const val REM_PROTECT_MINUTES = 8f
        private const val REM_INTERRUPT_PENALTY = 0.25f
        private const val AROUSAL_MOVING_SECONDS = 2.5f
        private const val AROUSAL_BONUS = 0.10f
        private const val CYCLE_COMPLETE_BONUS = 0.15f
        private const val POST_REM_BONUS = 0.08f
        private const val LOW_CONFIDENCE = 0.35f
        private const val LOW_CONFIDENCE_PENALTY = 0.08f
    }
}

/** Short human label for a stage. */
fun SleepStage.label(): String = when (this) {
    SleepStage.AWAKE -> "Awake"
    SleepStage.REM -> "REM"
    SleepStage.LIGHT -> "Light sleep"
    SleepStage.DEEP -> "Deep sleep"
    SleepStage.UNKNOWN -> "Settling"
}
