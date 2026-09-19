package com.smartalarm.core

import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.sleep.SmartWakeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Targeted tests of the wake decision, independent of staging. */
class SmartWakeEngineTest {

    private val target = 1_700_000_000_000L + 9 * 3_600_000L
    private val plan = AlarmPlan(cycles = 6, windowBeforeMinutes = 30, windowAfterMinutes = 15)

    private fun minutesFromTarget(minutes: Int) = target + minutes * 60_000L

    @Test
    fun `never fires before the window opens, however good the moment`() {
        val engine = SmartWakeEngine(plan)
        val now = minutesFromTarget(-45)
        val decision = engine.evaluate(
            nowMillis = now,
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.AWAKE, 10, now - 300_000L),
            completedCycles = 6,
            currentCycleHasRem = true,
        )
        assertFalse("fired 45 minutes early", decision.shouldWake)
        assertEquals(minutesFromTarget(-30), decision.windowStartMillis)
        assertEquals(minutesFromTarget(15), decision.windowEndMillis)
    }

    @Test
    fun `holds through deep sleep early in the window`() {
        val engine = SmartWakeEngine(plan)
        val now = minutesFromTarget(-28)
        val decision = engine.evaluate(
            nowMillis = now,
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.DEEP, 20, now - 600_000L),
            completedCycles = 5,
            currentCycleHasRem = false,
        )
        assertFalse("woke someone out of deep sleep with half an hour to spare", decision.shouldWake)
        assertTrue("bar should still be high this early", decision.threshold > 0.6f)
    }

    @Test
    fun `takes light sleep early in the window`() {
        val engine = SmartWakeEngine(plan)
        val now = minutesFromTarget(-25)
        val decision = engine.evaluate(
            nowMillis = now,
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.DEEP, 10, now - 900_000L) +
                stagedRun(SleepStage.LIGHT, 10, now - 300_000L, startIndex = 10),
            completedCycles = 6,
            currentCycleHasRem = true,
        )
        assertTrue(
            "passed up light sleep after six cycles (score=${decision.score} bar=${decision.threshold})",
            decision.shouldWake,
        )
    }

    @Test
    fun `fires at the deadline even in deep sleep`() {
        val engine = SmartWakeEngine(plan)
        val now = minutesFromTarget(15)
        val decision = engine.evaluate(
            nowMillis = now,
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.DEEP, 40, now - 1_200_000L),
            completedCycles = 4,
            currentCycleHasRem = false,
        )
        assertTrue("missed its own deadline", decision.shouldWake)
        assertEquals("Deadline reached", decision.reason)
    }

    @Test
    fun `the bar falls monotonically across the window`() {
        val engine = SmartWakeEngine(plan)
        var previous = 1f
        for (offset in -30..14) {
            val decision = engine.evaluate(
                nowMillis = minutesFromTarget(offset),
                targetMillis = target,
                recentEpochs = stagedRun(SleepStage.DEEP, 20, minutesFromTarget(offset) - 600_000L),
                completedCycles = 5,
                currentCycleHasRem = false,
            )
            assertTrue(
                "bar rose from $previous to ${decision.threshold} at offset $offset",
                decision.threshold <= previous + 1e-4f,
            )
            previous = decision.threshold
        }
        assertTrue("bar never reached zero by the deadline", previous < 0.05f)
    }

    @Test
    fun `does not cut a REM period short`() {
        val engine = SmartWakeEngine(plan)
        val now = minutesFromTarget(-20)
        val midRem = engine.evaluate(
            nowMillis = now,
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.LIGHT, 10, now - 900_000L) +
                stagedRun(SleepStage.REM, 6, now - 180_000L, startIndex = 10),
            completedCycles = 6,
            currentCycleHasRem = true,
        )
        assertFalse("interrupted a REM period three minutes in", midRem.shouldWake)

        val justAfterRem = engine.evaluate(
            nowMillis = now,
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.REM, 16, now - 960_000L) +
                stagedRun(SleepStage.LIGHT, 4, now - 120_000L, startIndex = 16),
            completedCycles = 6,
            currentCycleHasRem = true,
        )
        assertTrue(
            "passed up the moment a REM period ended (score=${justAfterRem.score})",
            justAfterRem.score > midRem.score,
        )
    }

    @Test
    fun `smart wake off means wake exactly on time`() {
        val engine = SmartWakeEngine(plan.copy(smartWakeEnabled = false))
        val early = engine.evaluate(
            nowMillis = minutesFromTarget(-10),
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.AWAKE, 10, minutesFromTarget(-15)),
            completedCycles = 6,
            currentCycleHasRem = true,
        )
        assertFalse("fired early with smart wake switched off", early.shouldWake)

        val onTime = engine.evaluate(
            nowMillis = target,
            targetMillis = target,
            recentEpochs = stagedRun(SleepStage.DEEP, 10, minutesFromTarget(-5)),
            completedCycles = 6,
            currentCycleHasRem = true,
        )
        assertTrue("did not fire at the exact time", onTime.shouldWake)
    }
}
