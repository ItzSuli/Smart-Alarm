package com.smartalarm.core.sleep

import com.smartalarm.core.model.EPOCH_MILLIS
import com.smartalarm.core.model.EpochFeatures
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streams raw accelerometer and heart-rate samples in, emits one [EpochFeatures] per 30 s.
 *
 * The accelerometer path mirrors what research-grade actigraphs do: band-pass the acceleration
 * to the 0.25-3 Hz band where human movement lives, then summarise each epoch by how much
 * energy was in that band. Gravity and sensor noise both fall outside the band, so a perfectly
 * still wrist scores ~0 no matter which way it is pointing.
 *
 * Everything here is allocation-free per sample and safe to run for ten hours on a watch.
 */
class EpochFeatureBuilder(
    private val epochMillis: Long = EPOCH_MILLIS,
    /** Band-pass lower corner; removes gravity and slow posture drift. */
    highPassTauSeconds: Float = 0.64f,
    /** Band-pass upper corner; removes sensor noise. */
    lowPassTauSeconds: Float = 0.08f,
    /** Tilt smoothing for the van Hees wrist-angle estimate. */
    angleTauSeconds: Float = 5.0f,
) {
    private val hpTau = highPassTauSeconds
    private val lpTau = lowPassTauSeconds
    private val angleTau = angleTauSeconds

    // Band-pass state (two cascaded exponential filters form the pass band).
    private var lpValue = 0f
    private var hpValue = 0f
    private var filtersPrimed = false

    // Smoothed gravity vector, used for the wrist tilt angle.
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gravityPrimed = false

    // Per-epoch accumulators.
    private var epochIndex = 0
    private var epochStartMillis = 0L
    private var started = false
    private var lastSampleMillis = 0L

    private var pim = 0f
    private var zeroCrossings = 0
    private var peak = 0f
    private var movingSeconds = 0f
    private var lastSign = 0
    private var angleSum = 0.0
    private var angleSamples = 0
    private var previousEpochAngle = Float.NaN

    private val hr = HeartRateAggregator()
    private var offWrist = false
    private var batteryPercent = -1

    /** Emitted epochs waiting to be collected by [drain]. */
    private val pending = ArrayList<EpochFeatures>(4)

    /**
     * Feed one accelerometer sample.
     *
     * @param timestampMillis wall-clock time of the sample; batched sensor bursts must pass the
     *   real per-sample time, not the delivery time, or the epoch boundaries smear.
     * @param x,y,z acceleration in m/s^2, exactly as Android reports it.
     */
    fun addAccelSample(timestampMillis: Long, x: Float, y: Float, z: Float) {
        if (!started) beginEpoch(timestampMillis)
        rollEpochsUpTo(timestampMillis)

        val dtSeconds = if (lastSampleMillis == 0L) {
            DEFAULT_DT
        } else {
            ((timestampMillis - lastSampleMillis) / 1000f).coerceIn(MIN_DT, MAX_DT)
        }
        lastSampleMillis = timestampMillis

        val magnitudeG = sqrt(x * x + y * y + z * z) / GRAVITY

        if (!filtersPrimed) {
            lpValue = magnitudeG
            hpValue = magnitudeG
            filtersPrimed = true
        }
        // Low-pass the raw magnitude, then subtract a slower low-pass of it: a band-pass.
        val lpAlpha = alphaFor(dtSeconds, lpTau)
        lpValue += lpAlpha * (magnitudeG - lpValue)
        val hpAlpha = alphaFor(dtSeconds, hpTau)
        hpValue += hpAlpha * (lpValue - hpValue)
        val band = lpValue - hpValue

        val magnitude = abs(band)
        // PIM: integrate the rectified band-passed signal, expressed in milli-g seconds.
        pim += magnitude * dtSeconds * 1000f
        if (magnitude > peak) peak = magnitude
        if (magnitude > MOVEMENT_THRESHOLD_G) movingSeconds += dtSeconds

        // ZCM: count band crossings that clear a dead-band, so noise does not inflate the count.
        val sign = when {
            band > ZERO_CROSS_DEADBAND_G -> 1
            band < -ZERO_CROSS_DEADBAND_G -> -1
            else -> 0
        }
        if (sign != 0) {
            if (lastSign != 0 && sign != lastSign) zeroCrossings++
            lastSign = sign
        }

        // Wrist tilt: the angle of the smoothed gravity vector out of the x/y plane.
        if (!gravityPrimed) {
            gx = x; gy = y; gz = z
            gravityPrimed = true
        }
        val gAlpha = alphaFor(dtSeconds, angleTau)
        gx += gAlpha * (x - gx)
        gy += gAlpha * (y - gy)
        gz += gAlpha * (z - gz)
        val horizontal = sqrt(gx * gx + gy * gy)
        val angleDegrees = atan2(gz, horizontal) * RAD_TO_DEG
        angleSum += angleDegrees
        angleSamples++
    }

    /** Feed one heart-rate sample in bpm. Values <= 0 are ignored as "no contact". */
    fun addHeartRateSample(timestampMillis: Long, bpm: Float) {
        if (!started) beginEpoch(timestampMillis)
        rollEpochsUpTo(timestampMillis)
        hr.add(bpm)
    }

    /** Record that the watch currently reports no skin contact for this epoch. */
    fun markOffWrist(offWrist: Boolean) {
        if (offWrist) this.offWrist = true
    }

    fun setBatteryPercent(percent: Int) {
        batteryPercent = percent
    }

    /**
     * Close every epoch that has fully elapsed by [nowMillis] and return them.
     * Call this from a timer as well as from the sensor callbacks, so an epoch still closes
     * when the sensor goes quiet (a completely still wrist can starve the callback).
     */
    fun drain(nowMillis: Long): List<EpochFeatures> {
        if (!started) return emptyList()
        rollEpochsUpTo(nowMillis)
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList(pending)
        pending.clear()
        return out
    }

    /** Force the in-flight epoch to close, for end-of-session flushing. */
    fun flush(nowMillis: Long): List<EpochFeatures> {
        if (!started) return emptyList()
        rollEpochsUpTo(nowMillis)
        if (nowMillis > epochStartMillis) closeEpoch(nowMillis - epochStartMillis)
        val out = ArrayList(pending)
        pending.clear()
        started = false
        return out
    }

    private fun beginEpoch(timestampMillis: Long) {
        started = true
        epochStartMillis = timestampMillis
        lastSampleMillis = 0L
        resetAccumulators()
    }

    private fun rollEpochsUpTo(nowMillis: Long) {
        // Emit an epoch for every boundary crossed, including empty ones: a sensor gap is real
        // data about the night and should show as a gap, not be silently closed up.
        var emitted = 0
        while (nowMillis - epochStartMillis >= epochMillis && emitted < MAX_EPOCHS_PER_ROLL) {
            closeEpoch(epochMillis)
            emitted++
        }
        if (nowMillis - epochStartMillis >= epochMillis) {
            // Still behind after emitting a sane number of epochs: the clock jumped rather than
            // the sensors stalling. Skip forward instead of grinding out hours of empty epochs.
            epochStartMillis = nowMillis
            resetAccumulators()
        }
    }

    private fun closeEpoch(durationMillis: Long) {
        val epochAngle = if (angleSamples > 0) (angleSum / angleSamples).toFloat() else previousEpochAngle
        val angleChange = if (previousEpochAngle.isNaN() || epochAngle.isNaN()) {
            0f
        } else {
            abs(epochAngle - previousEpochAngle)
        }
        val hrStats = hr.snapshot()

        pending += EpochFeatures(
            index = epochIndex,
            startMillis = epochStartMillis,
            durationMillis = durationMillis,
            activityCount = pim,
            zeroCrossings = zeroCrossings,
            peakAcceleration = peak,
            movingSeconds = movingSeconds,
            wristAngle = if (epochAngle.isNaN()) 0f else epochAngle,
            wristAngleChange = angleChange,
            heartRate = hrStats.mean,
            heartRateMin = hrStats.min,
            heartRateMax = hrStats.max,
            heartRateStdDev = hrStats.stdDev,
            hrvProxyMs = hrStats.rmssd,
            hrSampleCount = hrStats.count,
            offWrist = offWrist,
            batteryPercent = batteryPercent,
        )

        if (!epochAngle.isNaN()) previousEpochAngle = epochAngle
        epochIndex++
        epochStartMillis += durationMillis
        resetAccumulators()
    }

    private fun resetAccumulators() {
        pim = 0f
        zeroCrossings = 0
        peak = 0f
        movingSeconds = 0f
        lastSign = 0
        angleSum = 0.0
        angleSamples = 0
        offWrist = false
        hr.reset()
    }

    private fun alphaFor(dtSeconds: Float, tauSeconds: Float): Float =
        1f - exp(-dtSeconds / tauSeconds)

    companion object {
        const val GRAVITY = 9.80665f
        private const val RAD_TO_DEG = 57.29578f
        private const val DEFAULT_DT = 0.02f
        private const val MIN_DT = 0.001f
        private const val MAX_DT = 0.2f

        /** Band-passed magnitude above which the wrist counts as moving, in g. */
        const val MOVEMENT_THRESHOLD_G = 0.02f
        private const val ZERO_CROSS_DEADBAND_G = 0.008f

        /** Two hours of gap filling; past that a clock jump is the likelier explanation. */
        private const val MAX_EPOCHS_PER_ROLL = 240
    }
}

/** Per-epoch heart-rate statistics. */
data class HeartRateStats(
    val mean: Float,
    val min: Float,
    val max: Float,
    val stdDev: Float,
    /** RMSSD over inter-beat intervals derived from successive bpm readings, in ms. */
    val rmssd: Float,
    val count: Int,
)

/**
 * Accumulates heart-rate samples for one epoch.
 *
 * The Galaxy Watch 4 exposes averaged bpm to third-party apps rather than raw R-R intervals,
 * so the variability figure here is a *proxy*: we convert each bpm reading back to an
 * inter-beat interval and take the RMS of successive differences. It tracks the same autonomic
 * swings that real HRV does, at lower resolution, which is all the stage model needs it for.
 */
class HeartRateAggregator {
    private var count = 0
    private var sum = 0.0
    private var sumSquares = 0.0
    private var minimum = Float.MAX_VALUE
    private var maximum = 0f
    private var previousInterval = Float.NaN
    private var successiveSquares = 0.0
    private var successiveCount = 0

    fun add(bpm: Float) {
        if (bpm < MIN_PLAUSIBLE_BPM || bpm > MAX_PLAUSIBLE_BPM) return
        count++
        sum += bpm
        sumSquares += bpm.toDouble() * bpm
        minimum = min(minimum, bpm)
        maximum = max(maximum, bpm)

        val intervalMs = 60_000f / bpm
        if (!previousInterval.isNaN()) {
            val delta = (intervalMs - previousInterval).toDouble()
            successiveSquares += delta * delta
            successiveCount++
        }
        previousInterval = intervalMs
    }

    fun snapshot(): HeartRateStats {
        if (count == 0) {
            return HeartRateStats(EpochFeatures.NO_HR, EpochFeatures.NO_HR, EpochFeatures.NO_HR, 0f, 0f, 0)
        }
        val mean = (sum / count).toFloat()
        val variance = (sumSquares / count - (sum / count) * (sum / count)).coerceAtLeast(0.0)
        val rmssd = if (successiveCount > 0) sqrt(successiveSquares / successiveCount).toFloat() else 0f
        return HeartRateStats(
            mean = mean,
            min = minimum,
            max = maximum,
            stdDev = sqrt(variance).toFloat(),
            rmssd = rmssd,
            count = count,
        )
    }

    fun reset() {
        count = 0
        sum = 0.0
        sumSquares = 0.0
        minimum = Float.MAX_VALUE
        maximum = 0f
        successiveSquares = 0.0
        successiveCount = 0
        // previousInterval deliberately survives the reset so RMSSD spans the epoch boundary.
    }

    companion object {
        private const val MIN_PLAUSIBLE_BPM = 25f
        private const val MAX_PLAUSIBLE_BPM = 220f
    }
}
