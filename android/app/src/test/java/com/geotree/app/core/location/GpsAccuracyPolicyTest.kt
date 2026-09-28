package com.geotree.app.core.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsAccuracyPolicyTest {
    private val policy = GpsAccuracyPolicy.Default

    @Test
    fun `accuracy up to 5 m is GOOD`() {
        assertEquals(GpsQuality.GOOD, policy.classify(0f))
        assertEquals(GpsQuality.GOOD, policy.classify(3.9f))
        assertEquals(GpsQuality.GOOD, policy.classify(5f))
    }

    @Test
    fun `accuracy above 5 m up to 10 m is ACCEPTABLE`() {
        assertEquals(GpsQuality.ACCEPTABLE, policy.classify(5.01f))
        assertEquals(GpsQuality.ACCEPTABLE, policy.classify(10f))
    }

    @Test
    fun `accuracy above 10 m is LOW`() {
        assertEquals(GpsQuality.LOW, policy.classify(10.01f))
        assertEquals(GpsQuality.LOW, policy.classify(1500f))
    }

    @Test
    fun `thresholds are configurable`() {
        val strict = GpsAccuracyPolicy(goodMaxMeters = 2f, acceptableMaxMeters = 4f)
        assertEquals(GpsQuality.ACCEPTABLE, strict.classify(3f))
        assertEquals(GpsQuality.LOW, strict.classify(5f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inconsistent thresholds are rejected`() {
        GpsAccuracyPolicy(goodMaxMeters = 12f, acceptableMaxMeters = 10f)
    }
}
