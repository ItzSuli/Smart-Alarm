package com.smartalarm.core

import com.smartalarm.core.model.AlarmPlan
import com.smartalarm.core.model.SleepStage
import com.smartalarm.core.model.WakeMode
import com.smartalarm.core.sleep.SleepSessionEngine
import org.junit.Test

/** Not an assertion suite: a readable dump used to tune the model against synthetic nights. */
class DiagnosticRun {

    @Test
    fun dumpNight() {
        val night = SyntheticNight(cycleMinutes = 90f, cycleCount = 6, totalMinutes = 600)
        val epochs = night.generateEpochs()
        val plan = AlarmPlan(cycles = 6, wakeMode = WakeMode.BOTH)
        val engine = SleepSessionEngine("diag", plan, startedAtMillis = epochs.first().startMillis)

        epochs.forEach { engine.onEpoch(it) }

        val predicted = engine.stagedEpochs.map { it.stage }
        val truth = night.trueStages

        println("=== SYNTHETIC NIGHT DIAGNOSTIC ===")
        println("epochs=${epochs.size} truth=${truth.size}")
        println("true onset=${(night.trueSleepOnsetMillis - epochs.first().startMillis) / 60000} min")
        println("detected onset=${engine.sleepOnsetMillis?.let { (it - epochs.first().startMillis) / 60000 }} min")
        println("true cycle ends (min): " +
            night.trueCycleEndMillis.map { (it - epochs.first().startMillis) / 60000 })
        println("detected cycles: " + engine.cycles.map {
            "#${it.index} ${(it.startMillis - epochs.first().startMillis) / 60000}-" +
                "${(it.endMillis - epochs.first().startMillis) / 60000}min " +
                "(${it.durationMinutes.toInt()}m, ${it.boundaryReason}) " +
                "D${it.deepMinutes.toInt()} L${it.lightMinutes.toInt()} R${it.remMinutes.toInt()}"
        })

        println("-- confusion (rows = truth, cols = predicted) --")
        val order = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
        print("truth\\pred".padEnd(12))
        order.forEach { print(it.name.padStart(8)) }
        println()
        var correct = 0
        for (t in order) {
            print(t.name.padEnd(12))
            for (p in order) {
                val n = truth.indices.count {
                    it < predicted.size && truth[it] == t && predicted[it] == p
                }
                if (t == p) correct += n
                print(n.toString().padStart(8))
            }
            println()
        }
        val scored = minOf(truth.size, predicted.size)
        println("agreement=%.1f%%".format(100.0 * correct / scored))

        println("-- hypnogram (truth / predicted), 5 min per char --")
        println("T: " + compress(truth))
        println("P: " + compress(predicted))

        val status = engine.currentStatus(epochs.last().endMillis)
        println("projected wake = ${(status.projectedWakeMillis - epochs.first().startMillis) / 60000} min")
        println("alarm fired at = ${engine.alarmFiredMillis?.let { (it - epochs.first().startMillis) / 60000 }} min")
        println("window = ${(status.windowStartMillis - epochs.first().startMillis) / 60000}" +
            "..${(status.windowEndMillis - epochs.first().startMillis) / 60000} min")
        println("decision = ${engine.lastDecision.reason} score=${engine.lastDecision.score}")
        println("stage minutes: light=${status.lightMinutes} deep=${status.deepMinutes} " +
            "rem=${status.remMinutes} awake=${status.awakeMinutes}")
    }

    private fun compress(stages: List<SleepStage>): String {
        val perChar = 10
        val sb = StringBuilder()
        var index = 0
        while (index < stages.size) {
            val slice = stages.subList(index, minOf(index + perChar, stages.size))
            val dominant = slice.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            sb.append(
                when (dominant) {
                    SleepStage.AWAKE -> 'W'
                    SleepStage.REM -> 'R'
                    SleepStage.LIGHT -> '-'
                    SleepStage.DEEP -> 'D'
                    else -> '?'
                }
            )
            index += perChar
        }
        return sb.toString()
    }
}

/** Variant dumps used while tuning cycle detection for non-standard sleepers. */
class DiagnosticVariants {
    private fun dump(label: String, night: SyntheticNight, cycles: Int) {
        val epochs = night.generateEpochs()
        val engine = SleepSessionEngine(
            label, AlarmPlan(cycles = cycles), startedAtMillis = epochs.first().startMillis,
        )
        epochs.forEach { engine.onEpoch(it) }
        val t0 = epochs.first().startMillis
        println("### $label")
        println("  onset=${engine.sleepOnsetMillis?.let { (it - t0) / 60000 }}")
        println("  true cycle ends=${night.trueCycleEndMillis.map { (it - t0) / 60000 }}")
        println("  detected=" + engine.cycles.joinToString { 
            "#${it.index} ${(it.startMillis - t0) / 60000}-${(it.endMillis - t0) / 60000}(${it.durationMinutes.toInt()}m,${it.boundaryReason},R${it.remMinutes.toInt()})"
        })
        println("  base=${engine.cycles.let { c -> if (c.isEmpty()) 0f else c.map { it.durationMinutes }.average() }}")
        println("  fired=${engine.alarmFiredMillis?.let { (it - t0) / 60000 }} projected=${(engine.projectedWakeMillis(epochs.last().endMillis) - t0) / 60000}")
        val truth = night.trueStages
        val pred = engine.stagedEpochs.map { it.stage }
        val n = minOf(truth.size, pred.size)
        println("  agreement=%.1f%%".format(100.0 * (0 until n).count { truth[it] == pred[it] } / n))
        println("  T: " + compressStages(truth))
        println("  P: " + compressStages(pred))
    }

    @Test
    fun shortCycleSleeper() {
        dump("short-75min", SyntheticNight(seed = 11L, cycleMinutes = 75f, cycleCount = 7, totalMinutes = 620), 5)
    }

    @Test
    fun longCycleSleeper() {
        dump("long-108min", SyntheticNight(seed = 11L, cycleMinutes = 108f, cycleCount = 5, totalMinutes = 620), 5)
    }

    @Test
    fun noHeartRate() {
        dump("no-hr", SyntheticNight(heartRateAvailable = false, cycleCount = 5, totalMinutes = 540), 5)
    }
}

internal fun compressStages(stages: List<SleepStage>): String {
    val sb = StringBuilder()
    var i = 0
    while (i < stages.size) {
        val slice = stages.subList(i, minOf(i + 10, stages.size))
        sb.append(
            when (slice.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key) {
                SleepStage.AWAKE -> 'W'; SleepStage.REM -> 'R'
                SleepStage.LIGHT -> '-'; SleepStage.DEEP -> 'D'; else -> '?'
            }
        )
        i += 10
    }
    return sb.toString()
}

/** Dump for the sleeper who never stirs. */
class DiagnosticImmovable {
    @Test
    fun immovable() {
        val epochs = ImmovableSleeper(totalMinutes = 480).epochs()
        val engine = SleepSessionEngine("imm", AlarmPlan(cycles = 4), startedAtMillis = epochs.first().startMillis)
        epochs.forEach { engine.onEpoch(it) }
        val t0 = epochs.first().startMillis
        println("### immovable")
        println("  onset=${engine.sleepOnsetMillis?.let { (it - t0) / 60000 }}")
        println("  detected=" + engine.cycles.joinToString { "#${it.index} ${(it.startMillis - t0) / 60000}-${(it.endMillis - t0) / 60000}(${it.boundaryReason})" })
        println("  fired=${engine.alarmFiredMillis?.let { (it - t0) / 60000 }} projected=${(engine.projectedWakeMillis(epochs.last().endMillis) - t0) / 60000}")
        println("  window=${engine.lastDecision.windowStartMillis.let { (it - t0) / 60000 }}..${engine.lastDecision.windowEndMillis.let { (it - t0) / 60000 }}")
        println("  P: " + compressStages(engine.stagedEpochs.map { it.stage }))
    }
}
