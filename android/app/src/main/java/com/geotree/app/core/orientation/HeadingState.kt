package com.geotree.app.core.orientation

import kotlin.math.atan2
import kotlin.math.hypot

/** How much to trust the phone compass right now (Android sensor accuracy status). */
enum class HeadingAccuracy { HIGH, MEDIUM, LOW, UNRELIABLE }

/** TRUE when magnetic declination was applied, MAGNETIC when it could not be (no GPS fix yet). */
enum class NorthReference { TRUE, MAGNETIC }

/** One raw sensor reading: degrees clockwise from magnetic north, not yet smoothed. */
data class RawHeading(val magneticDegrees: Double, val accuracy: HeadingAccuracy)

/** The phone's heading as the UI sees it. Never invented: no sensor, no heading. */
sealed interface HeadingState {
    /** The device has no usable rotation-vector or magnetometer sensors. */
    data object Unsupported : HeadingState

    /** Sensors are off (only measured while guidance or Heading Up needs it). */
    data object Inactive : HeadingState

    data class Available(
        val degrees: Double,
        val reference: NorthReference,
        val accuracy: HeadingAccuracy,
    ) : HeadingState
}

val HeadingState.degreesOrNull: Double? get() = (this as? HeadingState.Available)?.degrees

/** Angle helpers that respect the 359° → 0° wrap. */
object AngleMath {
    /** Any angle → [0, 360). */
    fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

    /** Shortest signed turn from [from] to [to], in (-180, 180]. Positive = clockwise. */
    fun signedDelta(from: Double, to: Double): Double {
        val d = normalize(to - from)
        return if (d > 180.0) d - 360.0 else d
    }

    /** Moves [fraction] of the shortest way from [from] towards [to]; 359° and 1° meet at 0°, not 180°. */
    fun interpolate(from: Double, to: Double, fraction: Double): Double = normalize(from + signedDelta(from, to) * fraction)
}

/**
 * Light circular exponential smoothing. [alpha] is the weight of each new reading: with Android's
 * UI sensor rate (~15–60 Hz) 0.3 settles within a fraction of a second, so the arrow follows a
 * physical turn without visible lag while hand tremor is damped. Large turns are never averaged
 * through the wrong side of the circle.
 */
class HeadingSmoother(private val alpha: Double = 0.3) {
    private var current: Double? = null

    fun reset() {
        current = null
    }

    fun update(degrees: Double): Double {
        val next = current?.let { AngleMath.interpolate(it, degrees, alpha) } ?: AngleMath.normalize(degrees)
        current = next
        return next
    }
}

/** Turns raw magnetic readings into a smoothed, true-north heading when declination is known. */
class HeadingProcessor(private val smoother: HeadingSmoother = HeadingSmoother()) {
    fun reset() = smoother.reset()

    /**
     * [declinationDegrees] is east-positive magnetic declination at the user's position
     * (true = magnetic + declination). Null keeps the magnetic heading and says so.
     */
    fun process(raw: RawHeading, declinationDegrees: Double?): HeadingState.Available {
        val corrected = if (declinationDegrees != null) raw.magneticDegrees + declinationDegrees else raw.magneticDegrees
        return HeadingState.Available(
            degrees = smoother.update(corrected),
            reference = if (declinationDegrees != null) NorthReference.TRUE else NorthReference.MAGNETIC,
            accuracy = raw.accuracy,
        )
    }
}

/**
 * Heading from an Android rotation matrix (row-major 3×3; world axes East, North, Up), degrees
 * clockwise from (magnetic) north.
 *
 * Uses the direction the top edge of the phone points when it is held flat, and the direction the
 * back camera looks when it is held upright, whichever lies closer to horizontal. That keeps the
 * arrow stable in both natural field grips instead of spinning near vertical. Returns null when
 * neither axis is usable.
 */
fun headingFromRotationMatrix(r: FloatArray): Double? {
    require(r.size >= 9) { "rotation matrix must have 9 elements" }
    val topEast = r[1].toDouble()
    val topNorth = r[4].toDouble()
    val cameraEast = -r[2].toDouble()
    val cameraNorth = -r[5].toDouble()
    val topHorizontal = hypot(topEast, topNorth)
    val cameraHorizontal = hypot(cameraEast, cameraNorth)
    if (maxOf(topHorizontal, cameraHorizontal) < MIN_HORIZONTAL) return null
    val (east, north) = if (topHorizontal >= cameraHorizontal) topEast to topNorth else cameraEast to cameraNorth
    return AngleMath.normalize(Math.toDegrees(atan2(east, north)))
}

private const val MIN_HORIZONTAL = 0.2
