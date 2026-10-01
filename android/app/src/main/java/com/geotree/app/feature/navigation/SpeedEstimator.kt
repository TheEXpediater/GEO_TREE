package com.geotree.app.feature.navigation

import com.geotree.app.core.location.GpsFix

/** What we can honestly say about the user's current speed. */
sealed interface SpeedReading {
    /** Android reported no speed, the reading failed the plausibility checks, or it is too old. */
    data object Unavailable : SpeedReading
    /** A trustworthy reading below the moving threshold. */
    data object Stationary : SpeedReading
    data class Moving(val metersPerSecond: Double) : SpeedReading
}

/**
 * Turns raw Android speed readings into a displayable speed.
 *
 * - Only `Location.hasSpeed()` readings are used ([GpsFix.speedMps] is null otherwise).
 * - Negative, non-finite and low-confidence readings are dropped.
 * - One implausible spike may reuse the previous estimate; a second spike in a row means the
 *   speed is unavailable, so an old value is never repeated indefinitely.
 * - A gap longer than [NavigationConfig.maxSpeedAgeMillis] since the last accepted reading
 *   starts over instead of blending in an old speed.
 * - Light exponential smoothing, and hysteresis between moving and stationary so the ETA does
 *   not flicker between "measured" and "walking estimate" around the threshold.
 */
class SpeedEstimator(private val config: NavigationConfig = NavigationConfig.Default) {
    private var smoothed: Double? = null
    private var moving = false
    private var lastAcceptedAt: Long? = null
    private var consecutiveSpikes = 0

    fun reset() {
        smoothed = null
        moving = false
        lastAcceptedAt = null
        consecutiveSpikes = 0
    }

    /** [receivedAtMillis] is when the fix reached the app (device clock), used for gap detection. */
    fun update(fix: GpsFix, receivedAtMillis: Long = fix.capturedAt): SpeedReading {
        lastAcceptedAt?.let { if (receivedAtMillis - it > config.maxSpeedAgeMillis) reset() }

        val raw = fix.speedMps?.toDouble()
        val unreliable = raw == null || !raw.isFinite() || raw < 0.0 ||
            fix.accuracyMeters > config.maxFixAccuracyForSpeedMeters ||
            (fix.speedAccuracyMps != null && fix.speedAccuracyMps > config.maxSpeedAccuracyMps)
        if (unreliable) {
            reset()
            return SpeedReading.Unavailable
        }
        val speed = raw!!
        if (speed > config.maxPlausibleSpeedMps) {
            consecutiveSpikes++
            val previous = smoothed
            if (consecutiveSpikes > 1 || previous == null) {
                reset()
                return SpeedReading.Unavailable
            }
            return classify(previous)
        }
        consecutiveSpikes = 0
        lastAcceptedAt = receivedAtMillis

        val threshold = if (moving) config.stationarySpeedMps else config.movingSpeedMps
        if (speed < threshold) {
            smoothed = 0.0
            moving = false
            return SpeedReading.Stationary
        }
        val previous = smoothed
        val next = if (previous == null || previous == 0.0) speed else previous + config.speedSmoothing * (speed - previous)
        smoothed = next
        moving = true
        return SpeedReading.Moving(next)
    }

    private fun classify(speed: Double): SpeedReading =
        if (moving && speed >= config.stationarySpeedMps) SpeedReading.Moving(speed) else SpeedReading.Stationary
}
