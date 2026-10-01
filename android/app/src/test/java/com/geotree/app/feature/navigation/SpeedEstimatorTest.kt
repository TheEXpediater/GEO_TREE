package com.geotree.app.feature.navigation

import com.geotree.app.testing.gpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {
    private val estimator = SpeedEstimator(NavigationConfig(speedSmoothing = 0.5))

    @Test
    fun `no reported speed is unavailable, never zero`() {
        assertEquals(SpeedReading.Unavailable, estimator.update(gpsFix(speedMps = null)))
    }

    @Test
    fun `slow readings count as stationary`() {
        assertEquals(SpeedReading.Stationary, estimator.update(gpsFix(speedMps = 0f)))
        assertEquals(SpeedReading.Stationary, estimator.update(gpsFix(speedMps = 0.3f)))
    }

    @Test
    fun `walking readings are lightly smoothed`() {
        assertEquals(SpeedReading.Moving(1.0), estimator.update(gpsFix(speedMps = 1.0f)))
        assertEquals(SpeedReading.Moving(1.5), estimator.update(gpsFix(speedMps = 2.0f)))
    }

    @Test
    fun `an unrealistic spike is ignored`() {
        estimator.update(gpsFix(speedMps = 1.2f))
        val reading = estimator.update(gpsFix(speedMps = 80f))
        assertEquals(SpeedReading.Moving(1.2), roundTo(reading))
        // A spike with no history is simply unavailable.
        assertEquals(SpeedReading.Unavailable, SpeedEstimator().update(gpsFix(speedMps = 80f)))
    }

    @Test
    fun `low confidence readings are unavailable`() {
        assertEquals(SpeedReading.Unavailable, estimator.update(gpsFix(speedMps = 1.4f, accuracy = 80f)))
        assertEquals(SpeedReading.Unavailable, estimator.update(gpsFix(speedMps = 1.4f, speedAccuracyMps = 6f)))
        assertEquals(SpeedReading.Moving(1.4), roundTo(estimator.update(gpsFix(speedMps = 1.4f, speedAccuracyMps = 0.5f))))
    }

    private fun roundTo(reading: SpeedReading): SpeedReading =
        if (reading is SpeedReading.Moving) SpeedReading.Moving(Math.round(reading.metersPerSecond * 1000) / 1000.0) else reading

    @Test
    fun `repeated spikes do not keep showing an old speed`() {
        estimator.update(gpsFix(speedMps = 1.2f))
        assertEquals(SpeedReading.Moving(1.2), roundTo(estimator.update(gpsFix(speedMps = 80f))))
        assertEquals(SpeedReading.Unavailable, estimator.update(gpsFix(speedMps = 90f)))
    }

    @Test
    fun `a long gap between readings starts over instead of blending an old speed`() {
        val t0 = 1_790_000_000_000L
        estimator.update(gpsFix(speedMps = 3.0f), receivedAtMillis = t0)
        val afterGap = estimator.update(gpsFix(speedMps = 1.0f), receivedAtMillis = t0 + NavigationConfig.Default.maxSpeedAgeMillis + 1)
        assertEquals("no blending with the 3 m/s from before the gap", SpeedReading.Moving(1.0), afterGap)

        val fresh = SpeedEstimator(NavigationConfig(speedSmoothing = 0.5))
        fresh.update(gpsFix(speedMps = 3.0f), receivedAtMillis = t0)
        assertEquals(SpeedReading.Moving(2.0), fresh.update(gpsFix(speedMps = 1.0f), receivedAtMillis = t0 + 2_500))
    }

    @Test
    fun `hysteresis keeps a walking user moving through brief slow readings`() {
        assertEquals(SpeedReading.Stationary, estimator.update(gpsFix(speedMps = 0.4f))) // below 0.5: not yet moving
        assertTrue(estimator.update(gpsFix(speedMps = 0.6f)) is SpeedReading.Moving) // starts moving at 0.5
        assertTrue("0.4 m/s keeps moving (exit threshold 0.3)", estimator.update(gpsFix(speedMps = 0.4f)) is SpeedReading.Moving)
        assertEquals(SpeedReading.Stationary, estimator.update(gpsFix(speedMps = 0.2f)))
        assertEquals(SpeedReading.Stationary, estimator.update(gpsFix(speedMps = 0.4f))) // must reach 0.5 again
    }

    @Test
    fun `NaN and negative readings are unavailable`() {
        assertEquals(SpeedReading.Unavailable, estimator.update(gpsFix(speedMps = Float.NaN)))
        assertEquals(SpeedReading.Unavailable, estimator.update(gpsFix(speedMps = -1f)))
        assertEquals(SpeedReading.Unavailable, estimator.update(gpsFix(speedMps = Float.POSITIVE_INFINITY)))
    }
}
