package com.smartalarm.core

import com.smartalarm.core.model.CheckIds
import com.smartalarm.core.model.CheckResult
import com.smartalarm.core.model.CheckStatus
import com.smartalarm.core.model.SelfTestRequest
import com.smartalarm.core.model.SelfTestResult
import com.smartalarm.core.model.SystemCheckReport
import com.smartalarm.core.model.WakeMode
import com.smartalarm.core.protocol.WearJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCheckTest {

    private fun check(id: String, status: CheckStatus) = CheckResult(id, id, status)

    @Test
    fun `a clean run reports ready`() {
        val report = SystemCheckReport(
            requestId = "r", startedAtMillis = 0L,
            phoneChecks = listOf(check(CheckIds.NOTIFICATIONS, CheckStatus.PASS)),
            watchChecks = listOf(check(CheckIds.HEART_RATE, CheckStatus.PASS)),
            confirmations = listOf(check(CheckIds.FELT_BUZZ, CheckStatus.PASS)),
        )
        assertTrue(report.readyToSleep)
        assertEquals("Everything works", report.verdict)
    }

    @Test
    fun `any failure blocks ready, wherever it came from`() {
        listOf(
            SystemCheckReport("r", 0L, phoneChecks = listOf(check("a", CheckStatus.FAIL))),
            SystemCheckReport("r", 0L, watchChecks = listOf(check("b", CheckStatus.FAIL))),
            // A person saying "no, I did not feel the buzz" is as much a failure as a dead
            // sensor, and has to count the same.
            SystemCheckReport("r", 0L, confirmations = listOf(check(CheckIds.FELT_BUZZ, CheckStatus.FAIL))),
        ).forEach { report ->
            assertFalse("a failure in ${report.failures.first().id} still reported ready", report.readyToSleep)
            assertTrue(report.verdict.contains("problem"))
        }
    }

    @Test
    fun `warnings are called out rather than hidden`() {
        val report = SystemCheckReport(
            requestId = "r", startedAtMillis = 0L,
            phoneChecks = listOf(
                check(CheckIds.NOTIFICATIONS, CheckStatus.PASS),
                check(CheckIds.BATTERY_OPTIMISATION, CheckStatus.WARN),
            ),
        )
        // A warning does not block the alarm, so it stays "ready" — but the verdict must not
        // say everything works when something is worth reading.
        assertTrue(report.readyToSleep)
        assertTrue(report.verdict, report.verdict.contains("1 thing"))
    }

    @Test
    fun `a run with nothing in it is not a pass`() {
        val empty = SystemCheckReport("r", 0L)
        assertFalse(empty.readyToSleep)
        assertEquals("Not run yet", empty.verdict)
    }

    @Test
    fun `a run still in flight is never ready`() {
        val running = SystemCheckReport(
            "r", 0L, running = true,
            phoneChecks = listOf(check(CheckIds.NOTIFICATIONS, CheckStatus.PASS)),
        )
        assertFalse(running.readyToSleep)
        assertEquals("Checking…", running.verdict)
    }

    @Test
    fun `plural wording holds up`() {
        val two = SystemCheckReport(
            "r", 0L,
            phoneChecks = listOf(check("a", CheckStatus.FAIL), check("b", CheckStatus.FAIL)),
        )
        assertTrue(two.verdict, two.verdict.contains("2 problems"))

        val one = SystemCheckReport("r", 0L, phoneChecks = listOf(check("a", CheckStatus.FAIL)))
        assertTrue(one.verdict, one.verdict.contains("1 problem") && !one.verdict.contains("problems"))
    }

    @Test
    fun `self test messages survive the wire`() {
        val request = SelfTestRequest(
            requestId = "abc",
            issuedAtMillis = 1_700_000_000_000L,
            sensorSeconds = 30,
            testAlarm = true,
            wakeMode = WakeMode.BOTH,
            vibrationIntensity = 2,
        )
        assertEquals(request, WearJson.decodeSelfTestRequest(WearJson.encodeSelfTestRequest(request)))

        val result = SelfTestResult(
            requestId = "abc",
            completedAtMillis = 1_700_000_030_000L,
            checks = listOf(
                CheckResult(CheckIds.ACCELEROMETER, "Motion sensor", CheckStatus.PASS, "600 samples"),
                CheckResult(
                    CheckIds.HEART_RATE, "Heart rate", CheckStatus.FAIL,
                    "No reading in the whole test.", "Wear the watch snugly.",
                ),
            ),
            accelSamples = 600,
            effectiveAccelHz = 20f,
            heartRateSamples = 0,
            epochsProduced = 1,
            activityCount = 48.5f,
            watchBatteryPercent = 74,
            vibrated = true,
        )
        assertEquals(result, WearJson.decodeSelfTestResult(WearJson.encodeSelfTestResult(result)))
    }

    @Test
    fun `every failing check offers a way out`() {
        // A red line with no remedy is a dead end for the person reading it at bedtime.
        val failures = listOf(
            CheckResult(CheckIds.HEART_RATE, "Heart rate", CheckStatus.FAIL, "No reading.", "Wear it snugly."),
            CheckResult(CheckIds.EXACT_ALARM, "Exact alarms", CheckStatus.FAIL, "Not allowed.", "Settings → …"),
        )
        failures.forEach {
            assertTrue("${it.id} has no remedy", it.remedy.isNotBlank())
        }
    }
}
