package com.smartalarm.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.sleep.EpochFeatureBuilder

/**
 * Runs the watch's accelerometer and heart-rate sensors through the night and emits one
 * [EpochFeatures] every thirty seconds.
 *
 * Two details matter for an all-night recording:
 *
 * **Batching.** Both sensors are registered with a maximum report latency, so the sensor hub
 * buffers samples in hardware and hands them over in one burst instead of interrupting the main
 * processor twenty times a second. On the Galaxy Watch 4 this is the difference between a
 * tracked night costing a fifth of the battery and costing half of it.
 *
 * **Timestamps.** Sensor events are stamped against [SystemClock.elapsedRealtimeNanos], not wall
 * clock, and a batch arrives long after the samples in it were taken. Using the delivery time
 * would smear every batch into one epoch, so each sample is converted back to the wall-clock
 * instant it was actually recorded.
 */
class SensorCollector(
    context: Context,
    private val accelerometerHz: Int = DEFAULT_ACCELEROMETER_HZ,
    private val batchSeconds: Int = DEFAULT_BATCH_SECONDS,
    private val onEpochs: (List<EpochFeatures>) -> Unit,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val builder = EpochFeatureBuilder()

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val heartRate: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    /** Wall-clock time corresponding to elapsed-realtime zero; refreshed on each batch. */
    @Volatile
    private var bootWallClockMillis: Long = wallClockOfBoot()

    @Volatile
    var heartRateSensorPresent: Boolean = heartRate != null
        private set

    @Volatile
    var lastHeartRate: Float = EpochFeatures.NO_HR
        private set

    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val timestampMillis = wallClockFor(event.timestamp)
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    builder.addAccelSample(
                        timestampMillis,
                        event.values[0],
                        event.values[1],
                        event.values[2],
                    )
                }

                Sensor.TYPE_HEART_RATE -> {
                    val bpm = event.values.firstOrNull() ?: return
                    // accuracy NO_CONTACT means the watch is off the wrist, not that the heart
                    // stopped; UNRELIABLE readings are still worth having for a trend.
                    if (event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) {
                        builder.markOffWrist(true)
                        return
                    }
                    if (bpm > 0f) {
                        lastHeartRate = bpm
                        builder.addHeartRateSample(timestampMillis, bpm)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_HEART_RATE &&
                accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT
            ) {
                builder.markOffWrist(true)
            }
        }
    }

    fun start(): Boolean {
        if (running) return true
        val accel = accelerometer ?: run {
            Log.e(TAG, "no accelerometer on this device; cannot track sleep")
            return false
        }
        bootWallClockMillis = wallClockOfBoot()

        val samplingPeriodUs = 1_000_000 / accelerometerHz
        val batchUs = batchSeconds * 1_000_000

        val accelOk = sensorManager.registerListener(listener, accel, samplingPeriodUs, batchUs)
        if (!accelOk) {
            Log.e(TAG, "accelerometer refused registration")
            return false
        }

        heartRate?.let { sensor ->
            val ok = sensorManager.registerListener(
                listener, sensor, HEART_RATE_PERIOD_US, batchUs,
            )
            heartRateSensorPresent = ok
            if (!ok) Log.w(TAG, "heart rate sensor refused registration; staging will be coarse")
        }

        running = true
        return true
    }

    /**
     * Close any epoch whose thirty seconds have elapsed and hand it on. Called from a timer so
     * epochs keep coming even when a perfectly still wrist stops generating callbacks.
     */
    fun pump(nowMillis: Long = System.currentTimeMillis()) {
        val epochs = builder.drain(nowMillis)
        if (epochs.isNotEmpty()) onEpochs(epochs)
    }

    fun setBatteryPercent(percent: Int) = builder.setBatteryPercent(percent)

    /** Stop the sensors and flush the partial epoch in progress. */
    fun stop(nowMillis: Long = System.currentTimeMillis()) {
        if (!running) return
        running = false
        sensorManager.unregisterListener(listener)
        val remaining = builder.flush(nowMillis)
        if (remaining.isNotEmpty()) onEpochs(remaining)
    }

    /**
     * Ask the sensor hub to hand over whatever it is holding. Worth doing just before the wake
     * window opens, so the decision is made on the freshest data rather than on a batch that is
     * up to [batchSeconds] old.
     */
    fun flushBatch() {
        if (running) sensorManager.flush(listener)
    }

    private fun wallClockFor(eventTimestampNanos: Long): Long =
        bootWallClockMillis + eventTimestampNanos / 1_000_000L

    private fun wallClockOfBoot(): Long =
        System.currentTimeMillis() - SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "SensorCollector"

        /** Comfortably above the 3 Hz top of the movement band we filter for. */
        const val DEFAULT_ACCELEROMETER_HZ = 20

        /** How long the sensor hub may buffer before waking the processor. */
        const val DEFAULT_BATCH_SECONDS = 30

        private const val HEART_RATE_PERIOD_US = 10_000_000
    }
}
