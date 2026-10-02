package com.geotree.app.feature.navigation

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position. Coordinates are data here, never identity. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Field-guidance tuning in one place, so it can be adjusted after field trials.
 *
 * - [arrivalRadiusMeters]: "Tree reached" once remaining distance is at or below this.
 * - [walkingSpeedMps]: only for the clearly labelled *walking estimate*; never shown as the user's speed.
 * - [movingSpeedMps]: a reading must reach this to count as moving (GPS jitter while standing still stays below).
 * - [stationarySpeedMps]: once moving, the user counts as stationary again only below this (hysteresis).
 * - [maxSpeedAgeMillis]: a measured speed older than this is no longer shown (GPS stopped delivering fixes).
 * - [maxPlausibleSpeedMps]: readings above this are treated as GPS spikes and ignored.
 * - [maxSpeedAccuracyMps] / [maxFixAccuracyForSpeedMeters]: readings worse than this are unreliable.
 * - [speedSmoothing]: weight of the newest reading in the exponential moving average (1 = no smoothing).
 * - [alignmentToleranceDegrees]: the phone counts as "facing the tree" within ± this of the bearing;
 *   also the half-width of the grey guide corridor on the navigation compass.
 * - [maxStartFixAgeMillis]: a fix older than this is not used to seed a new session (it could be from
 *   somewhere else entirely and would fake distance or arrival); guidance waits for a fresh fix instead.
 */
data class NavigationConfig(
    val arrivalRadiusMeters: Double = 10.0,
    val walkingSpeedMps: Double = 1.4,
    val movingSpeedMps: Double = 0.5,
    val stationarySpeedMps: Double = 0.3,
    val maxSpeedAgeMillis: Long = 8_000,
    val maxPlausibleSpeedMps: Double = 30.0,
    val maxSpeedAccuracyMps: Double = 3.0,
    val maxFixAccuracyForSpeedMeters: Double = 50.0,
    val speedSmoothing: Double = 0.5,
    val maxStartFixAgeMillis: Long = 15_000,
    val alignmentToleranceDegrees: Double = 12.0,
) {
    companion object {
        val Default = NavigationConfig()
    }
}

/** Pure geodesy and formatting used by field guidance. Spherical Earth (haversine), not Cartesian x/y. */
object NavigationMath {
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Great-circle distance in meters. Accurate to well under 0.5 % at field distances. */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial great-circle bearing from [from] to [to], degrees clockwise from true north in [0, 360). */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** 8-point compass label; each sector is 45° wide and centred on its direction. */
    fun cardinal(bearingDegrees: Double): String {
        val normalized = ((bearingDegrees % 360.0) + 360.0) % 360.0
        return CARDINALS[((normalized + 22.5) / 45.0).toInt() % 8]
    }

    fun metersPerSecondToKmh(mps: Double): Double = mps * 3.6

    /** Seconds to cover [distanceMeters] at [speedMps]; null for zero, negative or non-finite speed. */
    fun etaSeconds(distanceMeters: Double, speedMps: Double?): Long? {
        if (speedMps == null || !speedMps.isFinite() || speedMps <= 0.0) return null
        if (!distanceMeters.isFinite() || distanceMeters < 0.0) return null
        return ceil(distanceMeters / speedMps).toLong()
    }

    /**
     * Points along the great circle from [from] to [to], both endpoints included.
     * Uses spherical linear interpolation, so long lines stay geodesic rather than straight in degrees.
     */
    fun geodesicPath(from: GeoPoint, to: GeoPoint, segments: Int): List<GeoPoint> {
        require(segments >= 1) { "segments must be >= 1" }
        val angular = distanceMeters(from, to) / EARTH_RADIUS_METERS
        if (angular < 1e-9) return listOf(from, to)
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)
        val sinD = sin(angular)
        return (0..segments).map { i ->
            when (i) {
                0 -> from
                segments -> to
                else -> {
                    val f = i.toDouble() / segments
                    val a = sin((1 - f) * angular) / sinD
                    val b = sin(f * angular) / sinD
                    val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
                    val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
                    val z = a * sin(lat1) + b * sin(lat2)
                    GeoPoint(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
                }
            }
        }
    }
}

/** User-facing text for guidance values. */
object NavigationFormat {
    /** "428 m" below 1 km, "1.4 km" from 1 km. */
    fun distance(meters: Double): String {
        val rounded = meters.roundToLong()
        return if (rounded < 1_000) "$rounded m" else String.format(Locale.US, "%.1f km", meters / 1_000.0)
    }

    fun speedKmh(mps: Double): String = String.format(Locale.US, "%.1f km/h", NavigationMath.metersPerSecondToKmh(mps))

    /** "Less than 1 min", "6 min", "1 hr 12 min". Minutes round up so arrival is not understated. */
    fun duration(seconds: Long): String {
        if (seconds < 60) return "Less than 1 min"
        val minutes = ceil(seconds / 60.0).toLong()
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours hr" else "$hours hr $rest min"
    }
}
