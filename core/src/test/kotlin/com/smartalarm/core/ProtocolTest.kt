package com.smartalarm.core

import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.SleepProfile
import com.smartalarm.core.model.WakeMode
import com.smartalarm.core.protocol.AlarmEvent
import com.smartalarm.core.protocol.EpochBatch
import com.smartalarm.core.protocol.EventSource
import com.smartalarm.core.protocol.SessionRequest
import com.smartalarm.core.protocol.WearJson
import com.smartalarm.core.protocol.WearPaths
import com.smartalarm.core.sleep.SleepSessionEngine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone and the watch are separately installed APKs that only ever meet over a serialised
 * wire, so a field that fails to round-trip is a bug neither app's own tests would catch.
 */
class ProtocolTest {

    @Test
    fun `session request survives the wire`() {
        val request = SessionRequest(
            sessionId = "abc-123",
            plan = AlarmPlan(
                cycles = 6,
                wakeMode = WakeMode.BOTH,
                windowBeforeMinutes = 25,
                windowAfterMinutes = 10,
                snoozeMinutes = 7,
                vibrationIntensity = 2,
            ),
            profile = SleepProfile(cycleMinutes = 84f, cycleSamples = 12, restingHeartRate = 48f),
            startAtMillis = 1_700_000_000_000L,
            issuedAtMillis = 1_700_000_001_000L,
        )
        val decoded = WearJson.decodeSessionRequest(WearJson.encodeSessionRequest(request))
        assertEquals(request, decoded)
    }

    @Test
    fun `epoch batches survive the wire`() {
        val epochs = (0 until 40).map { index ->
            EpochFeatures(
                index = index,
                startMillis = 1_700_000_000_000L + index * 30_000L,
                activityCount = 12.5f * index,
                zeroCrossings = index,
                peakAcceleration = 0.05f,
                movingSeconds = 1.25f,
                wristAngle = -12.5f,
                wristAngleChange = 3.25f,
                heartRate = 56.5f,
                heartRateMin = 54f,
                heartRateMax = 60f,
                heartRateStdDev = 1.5f,
                hrvProxyMs = 42.5f,
                hrSampleCount = 3,
                batteryPercent = 74,
            )
        }
        val batch = EpochBatch("abc-123", 7, epochs)
        val decoded = WearJson.decodeEpochBatch(WearJson.encodeEpochBatch(batch))
        assertEquals(batch, decoded)
    }

    @Test
    fun `alarm events survive the wire`() {
        val event = AlarmEvent(
            sessionId = "abc-123",
            atMillis = 1_700_000_000_000L,
            source = EventSource.WATCH,
            reason = "REM period just ended",
            wakeQualityPercent = 88,
        )
        assertEquals(event, WearJson.decodeAlarmEvent(WearJson.encodeAlarmEvent(event)))
    }

    @Test
    fun `a whole night's summary survives the wire`() {
        val night = SyntheticNight(cycleCount = 5, totalMinutes = 480)
        val epochs = night.generateEpochs()
        val engine = SleepSessionEngine(
            "abc-123", AlarmPlan(cycles = 5), startedAtMillis = epochs.first().startMillis,
        )
        epochs.forEach { engine.onEpoch(it) }
        val summary = engine.summary(epochs.last().endMillis)

        val decoded = WearJson.decodeSummary(WearJson.encodeSummary(summary))
        assertEquals(summary, decoded)
        assertTrue("hypnogram lost in transit", decoded.hypnogram.size == summary.hypnogram.size)
    }

    @Test
    fun `an older peer ignores fields it does not know`() {
        // Forward compatibility: a watch on an older build must not choke on a newer phone.
        val json = """
            {"cycles":4,"wakeMode":"BOTH","somethingFromTheFuture":{"nested":true}}
        """.trimIndent()
        val plan = WearJson.instance.decodeFromString(AlarmPlan.serializer(), json)
        assertEquals(4, plan.cycles)
        assertEquals(WakeMode.BOTH, plan.wakeMode)
    }

    @Test
    fun `epoch batch paths are distinct per sequence`() {
        val paths = (0L until 5L).map { WearPaths.epochBatchPath(it) }
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.all { it.startsWith(WearPaths.EPOCH_BATCH) })
    }

    @Test
    fun `cycle count is validated at the boundary`() {
        Json.Default // keeps the import honest
        listOf(1, 7).forEach { AlarmPlan(cycles = it) }
        listOf(0, 8, -1).forEach { cycles ->
            runCatching { AlarmPlan(cycles = cycles) }
                .onSuccess { error("AlarmPlan accepted $cycles cycles") }
        }
    }
}
