package com.smartalarm.core.sleep

import com.smartalarm.core.model.EPOCHS_PER_MINUTE
import kotlin.math.ln
import kotlin.math.min

/**
 * Sleep/wake scoring from activity counts, using the Cole-Kripke algorithm with Webster's
 * rescoring rules on top.
 *
 * Cole-Kripke (Cole et al., *Sleep* 1992) weights a window of one-minute activity counts and
 * calls the middle minute sleep when the weighted sum falls below 1. It needs two minutes of
 * *future* data, so the last two minutes are provisional; that lag is irrelevant for sleep
 * tracking.
 *
 * Webster's rules then undo the algorithm's habit of calling the first few minutes after a long
 * awake stretch "sleep" — you do not drop straight into sleep after fifteen minutes of tossing.
 *
 * Raw epoch measurements are kept and the whole night is rescored on every call to [rescore],
 * because the baseline that converts them into ActiGraph-equivalent counts keeps improving as
 * the night goes on. Scoring an epoch once, with whatever baseline happened to exist at the
 * time, bakes the first half hour's guesswork in permanently — and the first half hour is
 * exactly when the baseline knows least.
 */
class SleepWakeScorer {
    private val rawActivity = ArrayList<Float>(1400)
    private val rawMovingSeconds = ArrayList<Float>(1400)

    /** One-minute activity counts, ActiGraph-scaled, rebuilt on each [rescore]. */
    private val minuteCounts = ArrayList<Float>(720)

    /** Verdict per minute after Cole-Kripke and Webster rescoring. */
    private val asleepByMinute = ArrayList<Boolean>(720)

    /** Record one epoch's raw measurements. Scoring happens in [rescore]. */
    fun addEpoch(activityCount: Float, movingSeconds: Float) {
        rawActivity += activityCount
        rawMovingSeconds += movingSeconds
    }

    /** Rescore the whole night with the current baseline. */
    fun rescore(baseline: NightBaseline) {
        minuteCounts.clear()
        var minute = 0
        while (minute * EPOCHS_PER_MINUTE < rawActivity.size) {
            var counts = 0f
            for (offset in 0 until EPOCHS_PER_MINUTE) {
                val epoch = minute * EPOCHS_PER_MINUTE + offset
                if (epoch < rawActivity.size) {
                    counts += baseline.toActiGraphCounts(rawActivity[epoch], rawMovingSeconds[epoch])
                }
            }
            minuteCounts += counts
            minute++
        }

        asleepByMinute.clear()
        for (index in minuteCounts.indices) asleepByMinute += coleKripkeAsleep(index)
        applyWebsterRules()
    }

    /** Verdict for the epoch at [epochIndex]; both epochs of a minute share the minute's verdict. */
    fun asleepAtEpoch(epochIndex: Int): Boolean = asleepAtMinute(epochIndex / EPOCHS_PER_MINUTE)

    fun asleepAtMinute(minute: Int): Boolean =
        if (minute in asleepByMinute.indices) asleepByMinute[minute] else false

    val minuteCount: Int get() = minuteCounts.size

    /**
     * Index of the first minute of the first run of at least [persistentMinutes] consecutive
     * sleep minutes — the standard "persistent sleep onset" criterion — or null if not yet.
     */
    fun persistentSleepOnsetMinute(persistentMinutes: Int = 10): Int? {
        var run = 0
        for (minute in asleepByMinute.indices) {
            if (asleepByMinute[minute]) {
                run++
                if (run >= persistentMinutes) return minute - persistentMinutes + 1
            } else {
                run = 0
            }
        }
        return null
    }

    /**
     * D = P * sum(w_i * A_i) over minutes -4..+2 around the scored minute; sleep when D < 1.
     * Weights are Cole-Kripke's optimised one-minute set.
     */
    private fun coleKripkeAsleep(minute: Int): Boolean {
        var sum = 0f
        for (offset in -4..2) {
            val index = minute + offset
            val count = if (index in minuteCounts.indices) minuteCounts[index] else 0f
            sum += WEIGHTS[offset + 4] * min(count, COUNT_CEILING)
        }
        return SCALE * sum < 1f
    }

    /**
     * Webster's rescoring rules. Each one turns misplaced "sleep" back into wake around long
     * awake stretches, and kills implausibly short sleep islands.
     */
    private fun applyWebsterRules() {
        val n = asleepByMinute.size

        // Rules 1-3: after a stretch of wake, the first minutes of "sleep" are really still wake.
        var wakeRun = 0
        var minute = 0
        while (minute < n) {
            if (!asleepByMinute[minute]) {
                wakeRun++
                minute++
                continue
            }
            val rescoreMinutes = when {
                wakeRun >= 15 -> 4
                wakeRun >= 10 -> 3
                wakeRun >= 4 -> 1
                else -> 0
            }
            var rescored = 0
            while (minute < n && rescored < rescoreMinutes && asleepByMinute[minute]) {
                asleepByMinute[minute] = false
                minute++
                rescored++
            }
            // Skip the rest of this sleep bout before looking for the next wake stretch.
            while (minute < n && asleepByMinute[minute]) minute++
            wakeRun = 0
        }

        // Rules 4-5: short sleep islands between long wake stretches are wake.
        applyIslandRule(n, islandMinutes = 6, surroundingWakeMinutes = 10)
        applyIslandRule(n, islandMinutes = 10, surroundingWakeMinutes = 20)
    }

    private fun applyIslandRule(n: Int, islandMinutes: Int, surroundingWakeMinutes: Int) {
        var minute = 0
        while (minute < n) {
            if (!asleepByMinute[minute]) {
                minute++
                continue
            }
            var end = minute
            while (end + 1 < n && asleepByMinute[end + 1]) end++
            val length = end - minute + 1
            if (length <= islandMinutes &&
                wakeRunBefore(minute) >= surroundingWakeMinutes &&
                wakeRunAfter(end) >= surroundingWakeMinutes
            ) {
                for (i in minute..end) asleepByMinute[i] = false
            }
            minute = end + 1
        }
    }

    private fun wakeRunBefore(minute: Int): Int {
        var run = 0
        var cursor = minute - 1
        while (cursor >= 0 && !asleepByMinute[cursor]) {
            run++
            cursor--
        }
        // The start of the recording counts as an unbounded wake stretch.
        return if (cursor < 0) LONG_RUN else run
    }

    private fun wakeRunAfter(minute: Int): Int {
        var run = 0
        var cursor = minute + 1
        while (cursor < asleepByMinute.size && !asleepByMinute[cursor]) {
            run++
            cursor++
        }
        return run
    }

    companion object {
        /** Cole-Kripke optimised one-minute weights for offsets -4..+2. */
        private val WEIGHTS = floatArrayOf(106f, 54f, 58f, 76f, 230f, 74f, 67f)
        private const val SCALE = 0.001f
        private const val COUNT_CEILING = 300f
        private const val LONG_RUN = 10_000
    }
}

/**
 * Learns, over the course of one night, what "still", "low heart rate" and "calm" mean for this
 * sleeper on this device — so the stage model compares against the person, not a constant.
 *
 * Every scale here is derived from percentiles of the night so far, which makes the whole
 * pipeline device-independent: a watch with a noisier accelerometer simply has a higher floor,
 * and everything downstream still sees "still" as zero.
 */
class NightBaseline(seedRestingHeartRate: Float = -1f, seedActivityScale: Float = 12f) {
    private val heartRates = ArrayList<Float>(1400)
    private val activityCounts = ArrayList<Float>(1400)
    private val hrvValues = ArrayList<Float>(1400)

    /** Lowest heart rate this sleeper sustains tonight; the floor deep sleep sits on. */
    var heartRateFloor: Float = if (seedRestingHeartRate > 0f) seedRestingHeartRate else 55f
        private set

    /** Heart rate at quiet wakefulness; the ceiling. */
    var heartRateCeiling: Float = heartRateFloor + 18f
        private set

    /** Activity count of a completely motionless wrist: this device's noise floor. */
    var activityFloor: Float = 0f
        private set

    /** Movement above the floor that counts as one ordinary light-sleep fidget. */
    var activityScale: Float = seedActivityScale.coerceAtLeast(1f)
        private set

    var heartRateVariabilityScale: Float = 40f
        private set

    fun observe(heartRate: Float, activityCount: Float, hrvProxy: Float) {
        if (heartRate > 0f) {
            heartRates += heartRate
            if (heartRates.size >= MIN_HR_SAMPLES) {
                val sorted = heartRates.sorted()
                heartRateFloor = percentileOfSorted(sorted, 0.05f)
                heartRateCeiling = maxOf(percentileOfSorted(sorted, 0.92f), heartRateFloor + 8f)
            }
        }
        activityCounts += activityCount
        if (activityCounts.size >= MIN_ACTIVITY_SAMPLES) {
            val sorted = activityCounts.sorted()
            // The quietest twentieth of the night is a motionless wrist: this device's noise
            // floor. Subtracting it is what makes every scale below device-independent.
            activityFloor = percentileOfSorted(sorted, 0.05f)
            val p75 = percentileOfSorted(sorted, 0.75f)
            activityScale = (p75 - activityFloor).coerceAtLeast(MIN_ACTIVITY_SCALE)
        }
        if (hrvProxy > 0f) {
            hrvValues += hrvProxy
            if (hrvValues.size >= MIN_HR_SAMPLES) {
                heartRateVariabilityScale = percentileOfSorted(hrvValues.sorted(), 0.85f).coerceAtLeast(5f)
            }
        }
    }

    /** Heart rate mapped to 0 (personal nocturnal minimum) .. 1 (quiet wake). */
    fun normalizeHeartRate(heartRate: Float): Float {
        if (heartRate <= 0f) return NEUTRAL
        val span = (heartRateCeiling - heartRateFloor).coerceAtLeast(4f)
        return ((heartRate - heartRateFloor) / span).coerceIn(-0.5f, 2f)
    }

    /** Activity mapped so 0 is motionless and 1 is a typical light-sleep movement. */
    fun normalizeActivity(activityCount: Float): Float =
        ((activityCount - activityFloor) / activityScale).coerceIn(0f, 60f)

    /**
     * Movement is enormously skewed — a still wrist and a thrashing one differ by three orders
     * of magnitude — so the stage model sees it in log space, where the stages separate on a
     * scale a Gaussian can actually describe.
     */
    fun logActivity(activityCount: Float): Float = ln(1f + normalizeActivity(activityCount))

    /** Seconds of movement in the epoch, likewise compressed. */
    fun logMovingSeconds(movingSeconds: Float): Float = ln(1f + movingSeconds.coerceAtLeast(0f))

    /**
     * Cole-Kripke expects ActiGraph counts per minute, where the sleep/wake boundary sits near
     * four counts. Two physical quantities drive the mapping: how hard the wrist moved
     * ([EpochFeatures.activityCount], in milli-g seconds above the noise floor) and how long it
     * kept moving ([EpochFeatures.movingSeconds]). Duration is what really separates being
     * awake from a sleeping fidget — a sleeper twitches for a fraction of a second, someone
     * awake moves for seconds at a time — so it carries equal weight here.
     *
     * Both units are absolute rather than percentiles of the night, because a percentile-based
     * threshold on a night that is ninety per cent sleep would declare the quietest movements
     * of light sleep to be wakefulness by construction.
     */
    fun toActiGraphCounts(activityCount: Float, movingSeconds: Float): Float {
        val intensity = (activityCount - activityFloor).coerceAtLeast(0f) / WAKE_PIM_UNIT
        val duration = movingSeconds.coerceAtLeast(0f) / WAKE_MOVING_SECONDS_UNIT
        return ((intensity + duration) * ACTIGRAPH_WAKE_COUNTS).coerceIn(0f, 400f)
    }

    fun normalizeHrv(hrvProxy: Float): Float {
        if (hrvProxy <= 0f) return NEUTRAL
        return (hrvProxy / heartRateVariabilityScale).coerceIn(0f, 3f)
    }

    val hasHeartRate: Boolean get() = heartRates.size >= MIN_HR_SAMPLES

    private fun percentileOfSorted(sorted: List<Float>, fraction: Float): Float {
        if (sorted.isEmpty()) return 0f
        val position = (fraction * (sorted.size - 1)).coerceIn(0f, (sorted.size - 1).toFloat())
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        val weight = position - low
        return sorted[low] * (1f - weight) + sorted[high] * weight
    }

    companion object {
        private const val MIN_HR_SAMPLES = 20
        private const val MIN_ACTIVITY_SAMPLES = 20

        /** A movement smaller than this, in milli-g seconds, is not a meaningful unit. */
        private const val MIN_ACTIVITY_SCALE = 25f
        private const val NEUTRAL = 0.5f

        /** Movement intensity, in milli-g seconds above the floor, typical of being awake. */
        private const val WAKE_PIM_UNIT = 120f

        /** Seconds of continuous movement in an epoch typical of being awake. */
        private const val WAKE_MOVING_SECONDS_UNIT = 1.2f

        /**
         * Half of Cole-Kripke's ~4.3 counts/minute sleep threshold, because two 30 s epochs are
         * summed to make the minute the algorithm scores.
         */
        private const val ACTIGRAPH_WAKE_COUNTS = 2.2f
    }
}
