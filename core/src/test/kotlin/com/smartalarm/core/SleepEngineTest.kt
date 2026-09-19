package com.smartalarm.core

import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.model.WakeMode
import com.smartalarm.core.sleep.SleepSessionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end tests against synthetic nights with a known ground-truth hypnogram.
 *
 * These drive the real pipeline from raw 20 Hz accelerometer samples through to the wake
 * decision, so a regression anywhere — filters, scoring, staging, cycle detection — shows up
 * here.
 */
class SleepEngineTest {

    private fun runNight(
        night: SyntheticNight,
        plan: AlarmPlan = AlarmPlan(cycles = 6, wakeMode = WakeMode.BOTH),
    ): Pair<SleepSessionEngine, List<com.smartalarm.core.model.EpochFeatures>> {
        val epochs = night.generateEpochs()
        val engine = SleepSessionEngine("test", plan, startedAtMillis = epochs.first().startMillis)
        epochs.forEach { engine.onEpoch(it) }
        return engine to epochs
    }

    @Test
    fun `detects sleep onset close to the truth`() {
        val night = SyntheticNight(sleepLatencyMinutes = 12)
        val (engine, epochs) = runNight(night)
        val onset = engine.sleepOnsetMillis
        assertNotNull("sleep onset was never detected", onset)

        val detectedMinutes = (onset!! - epochs.first().startMillis) / 60_000
        val trueMinutes = (night.trueSleepOnsetMillis - epochs.first().startMillis) / 60_000
        // The persistent-sleep criterion needs 10 consecutive sleep minutes before it commits,
        // so detection legitimately trails the truth; it must not run ahead of it.
        assertTrue(
            "onset $detectedMinutes min vs truth $trueMinutes min",
            detectedMinutes >= trueMinutes - 2 && detectedMinutes <= trueMinutes + 15,
        )
    }

    @Test
    fun `stage agreement with ground truth stays high`() {
        val night = SyntheticNight()
        val (engine, _) = runNight(night)
        val predicted = engine.stagedEpochs.map { it.stage }
        val truth = night.trueStages
        val scored = minOf(predicted.size, truth.size)
        val correct = (0 until scored).count { predicted[it] == truth[it] }
        val agreement = correct.toDouble() / scored
        assertTrue("stage agreement was %.1f%%".format(agreement * 100), agreement >= 0.80)
    }

    @Test
    fun `wake and deep sleep are both recovered`() {
        val night = SyntheticNight()
        val (engine, _) = runNight(night)
        val predicted = engine.stagedEpochs.map { it.stage }
        val truth = night.trueStages

        fun recall(stage: SleepStage): Double {
            val actual = truth.indices.filter { truth[it] == stage }
            if (actual.isEmpty()) return 1.0
            return actual.count { it < predicted.size && predicted[it] == stage }.toDouble() / actual.size
        }
        assertTrue("AWAKE recall %.2f".format(recall(SleepStage.AWAKE)), recall(SleepStage.AWAKE) >= 0.70)
        assertTrue("DEEP recall %.2f".format(recall(SleepStage.DEEP)), recall(SleepStage.DEEP) >= 0.65)
        assertTrue("REM recall %.2f".format(recall(SleepStage.REM)), recall(SleepStage.REM) >= 0.65)
    }

    @Test
    fun `finds the right number of cycles and puts the boundaries near the truth`() {
        val night = SyntheticNight(cycleCount = 6, cycleMinutes = 90f)
        val (engine, _) = runNight(night)
        val detected = engine.cycles

        assertTrue("detected ${detected.size} cycles, expected about 6", detected.size in 5..7)

        // Every detected boundary should land near a real one.
        detected.take(5).forEach { cycle ->
            val nearest = night.trueCycleEndMillis.minByOrNull { abs(it - cycle.endMillis) }
            assertNotNull(nearest)
            val errorMinutes = abs(nearest!! - cycle.endMillis) / 60_000.0
            assertTrue(
                "cycle ${cycle.index} boundary is $errorMinutes min from any true boundary",
                errorMinutes <= 15,
            )
        }
    }

    @Test
    fun `cycle length is learned rather than assumed to be ninety minutes`() {
        val shortSleeper = SyntheticNight(seed = 11L, cycleMinutes = 75f, cycleCount = 7, totalMinutes = 620)
        val longSleeper = SyntheticNight(seed = 11L, cycleMinutes = 108f, cycleCount = 5, totalMinutes = 620)

        val (shortEngine, _) = runNight(shortSleeper)
        val (longEngine, _) = runNight(longSleeper)

        val shortMean = shortEngine.cycles.map { it.durationMinutes }.average()
        val longMean = longEngine.cycles.map { it.durationMinutes }.average()

        assertTrue("short sleeper measured %.0f min cycles".format(shortMean), shortMean < 88)
        assertTrue("long sleeper measured %.0f min cycles".format(longMean), longMean > 95)
        assertTrue(
            "the two sleepers were not told apart (%.0f vs %.0f)".format(shortMean, longMean),
            longMean - shortMean > 12,
        )
    }

    @Test
    fun `projected wake time tracks the sleeper rather than a fixed multiple of ninety`() {
        val plan = AlarmPlan(cycles = 5)
        val shortSleeper = SyntheticNight(seed = 7L, cycleMinutes = 75f, cycleCount = 7, totalMinutes = 620)
        val (engine, epochs) = runNight(shortSleeper, plan)

        // Five 75-minute cycles is a little over six hours, well short of the 7.5 hours a
        // fixed 90-minute assumption would have produced.
        val projectedMinutes = (engine.projectedWakeMillis(epochs.last().endMillis) -
            epochs.first().startMillis) / 60_000.0
        assertTrue(
            "projected wake at $projectedMinutes min looks like a 90-minute assumption",
            projectedMinutes < 430,
        )
        assertTrue("projected wake at $projectedMinutes min is implausibly early", projectedMinutes > 330)
    }

    @Test
    fun `alarm fires inside the window and not out of deep sleep`() {
        val night = SyntheticNight(cycleCount = 6)
        val plan = AlarmPlan(cycles = 6, windowBeforeMinutes = 30, windowAfterMinutes = 15)
        val (engine, _) = runNight(night, plan)

        val firedAt = engine.alarmFiredMillis
        assertNotNull("the alarm never fired", firedAt)
        val decision = engine.firedDecision!!

        assertTrue(
            "fired before the window opened",
            firedAt!! >= decision.windowStartMillis - 30_000,
        )
        assertTrue("fired after the deadline", firedAt <= decision.windowEndMillis + 30_000)

        val stageAtWake = engine.stagedEpochs.lastOrNull { it.features.startMillis <= firedAt }?.stage
        assertTrue(
            "woken out of $stageAtWake, which is the worst stage to be woken from",
            stageAtWake != SleepStage.DEEP,
        )
    }

    @Test
    fun `the deadline is honoured even when the sleeper never surfaces`() {
        // A sleeper who is in deep sleep across the whole window: there is no good moment, and
        // the alarm still has to go off.
        val night = ImmovableSleeper(totalMinutes = 480)
        val epochs = night.epochs()
        val plan = AlarmPlan(cycles = 4, windowBeforeMinutes = 30, windowAfterMinutes = 15)
        val engine = SleepSessionEngine("deadline", plan, startedAtMillis = epochs.first().startMillis)
        epochs.forEach { engine.onEpoch(it) }

        val firedAt = engine.alarmFiredMillis
        assertNotNull("the alarm never fired for a sleeper who never stirred", firedAt)
        val decision = engine.firedDecision!!
        assertTrue("fired past its own deadline", firedAt!! <= decision.windowEndMillis + 60_000)
    }

    @Test
    fun `a watch with no heart rate sensor still tracks sleep and wakes the sleeper`() {
        val night = SyntheticNight(heartRateAvailable = false, cycleCount = 5, totalMinutes = 540)
        val plan = AlarmPlan(cycles = 5)
        val (engine, _) = runNight(night, plan)

        assertNotNull("onset not found without heart rate", engine.sleepOnsetMillis)
        assertNotNull("alarm never fired without heart rate", engine.alarmFiredMillis)

        val predicted = engine.stagedEpochs.map { it.stage }
        val truth = night.trueStages
        val asleepCorrect = truth.indices.count {
            it < predicted.size && truth[it].isAsleep == predicted[it].isAsleep
        }
        // Stage detail collapses without cardiac data, but sleep versus wake must survive.
        assertTrue(
            "sleep/wake agreement fell to %.2f without heart rate"
                .format(asleepCorrect.toDouble() / truth.size),
            asleepCorrect.toDouble() / truth.size >= 0.85,
        )
    }

    @Test
    fun `fewer requested cycles means an earlier alarm`() {
        val night = SyntheticNight(cycleCount = 7, totalMinutes = 660)
        val epochs = night.generateEpochs()

        fun wakeMinutesFor(cycles: Int): Long {
            val engine = SleepSessionEngine(
                "c$cycles", AlarmPlan(cycles = cycles), startedAtMillis = epochs.first().startMillis,
            )
            epochs.forEach { engine.onEpoch(it) }
            val fired = engine.alarmFiredMillis ?: epochs.last().endMillis
            return (fired - epochs.first().startMillis) / 60_000
        }

        val three = wakeMinutesFor(3)
        val five = wakeMinutesFor(5)
        val seven = wakeMinutesFor(7)

        assertTrue("3 cycles woke at $three min, 5 cycles at $five min", three < five)
        assertTrue("5 cycles woke at $five min, 7 cycles at $seven min", five < seven)
        // Three cycles is about four and a half hours.
        assertTrue("3 cycles woke at $three min, nowhere near 4.5 hours", three in 200..320)
    }

    @Test
    fun `the night's summary adds up`() {
        val night = SyntheticNight()
        val (engine, epochs) = runNight(night)
        val summary = engine.summary(epochs.last().endMillis)

        val stageTotal = summary.lightMinutes + summary.deepMinutes +
            summary.remMinutes + summary.awakeMinutes
        assertEquals(
            "stage minutes do not add up to the recording length",
            summary.timeInBedMinutes.toDouble(), stageTotal.toDouble(), 2.0,
        )
        assertEquals(
            "asleep minutes do not match the individual stages",
            (summary.lightMinutes + summary.deepMinutes + summary.remMinutes).toDouble(),
            summary.asleepMinutes.toDouble(), 0.01,
        )
        assertTrue("sleep efficiency out of range", summary.sleepEfficiency in 0f..100f)
        assertTrue("hypnogram is empty", summary.hypnogram.isNotEmpty())
        assertTrue("no cycles recorded in the summary", summary.cycles.isNotEmpty())
    }

    @Test
    fun `tonight's measurements feed back into the sleeper's profile`() {
        val night = SyntheticNight(seed = 3L, cycleMinutes = 78f, cycleCount = 7, totalMinutes = 620)
        val (engine, _) = runNight(night)
        val updated = engine.updatedProfile(com.smartalarm.core.model.SleepProfile())

        assertTrue("profile did not record the night", updated.nightsRecorded == 1)
        assertTrue("no cycles folded into the profile", updated.cycleSamples >= 4)
        assertTrue(
            "profile still thinks this sleeper runs 90-minute cycles (%.0f)".format(updated.cycleMinutes),
            updated.cycleMinutes < 88f,
        )
        assertTrue("resting heart rate not learned", updated.restingHeartRate > 30f)
    }
}
