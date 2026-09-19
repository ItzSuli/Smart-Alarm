package com.smartalarm.core

import com.smartalarm.core.model.SleepStage
import org.junit.Test

/** Prints the raw feature distributions per true stage, to calibrate thresholds against. */
class CalibrationDump {
    @Test
    fun activityDistributionByStage() {
        val night = SyntheticNight(cycleMinutes = 90f, cycleCount = 6, totalMinutes = 600)
        val epochs = night.generateEpochs()
        val truth = night.trueStages

        val all = epochs.map { it.activityCount }.sorted()
        fun pct(list: List<Float>, f: Float) = list[(f * (list.size - 1)).toInt()]
        println("=== ACTIVITY (all epochs) ===")
        listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.9f, 0.95f, 0.97f, 0.99f).forEach {
            println("  p${(it * 100).toInt()} = %.1f".format(pct(all, it)))
        }

        listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP).forEach { stage ->
            val values = epochs.indices.filter { truth[it] == stage }.map { epochs[it].activityCount }.sorted()
            val moving = epochs.indices.filter { truth[it] == stage }.map { epochs[it].movingSeconds }.sorted()
            val hr = epochs.indices.filter { truth[it] == stage }
                .map { epochs[it].heartRate }.filter { it > 0 }.sorted()
            val hrv = epochs.indices.filter { truth[it] == stage }
                .map { epochs[it].hrvProxyMs }.filter { it > 0 }.sorted()
            if (values.isEmpty()) return@forEach
            println("=== $stage n=${values.size} ===")
            println("  activity p10=%.1f p50=%.1f p90=%.1f".format(pct(values, .1f), pct(values, .5f), pct(values, .9f)))
            println("  moving   p10=%.2f p50=%.2f p90=%.2f".format(pct(moving, .1f), pct(moving, .5f), pct(moving, .9f)))
            if (hr.isNotEmpty()) println("  hr       p10=%.1f p50=%.1f p90=%.1f".format(pct(hr, .1f), pct(hr, .5f), pct(hr, .9f)))
            if (hrv.isNotEmpty()) println("  hrv      p10=%.1f p50=%.1f p90=%.1f".format(pct(hrv, .1f), pct(hrv, .5f), pct(hrv, .9f)))
        }
    }
}
