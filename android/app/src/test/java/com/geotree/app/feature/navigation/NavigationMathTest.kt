package com.geotree.app.feature.navigation

import kotlin.math.cos
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationMathTest {
    private val magalang = GeoPoint(15.2167, 120.6600)

    @Test
    fun `distance uses the sphere, not degrees as x-y`() {
        // One degree of longitude on the equator: pi * R / 180.
        assertEquals(111_195.08, NavigationMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 0.05)
        // At 60°N a degree of longitude is half as long; Cartesian degrees would say it is the same.
        assertEquals(55_597.5, NavigationMath.distanceMeters(GeoPoint(60.0, 0.0), GeoPoint(60.0, 1.0)), 50.0)
    }

    @Test
    fun `field-scale distance matches a local flat-earth check`() {
        val other = GeoPoint(15.2171, 120.6604)
        val metersPerDegree = Math.PI * NavigationMath.EARTH_RADIUS_METERS / 180.0
        val north = (other.latitude - magalang.latitude) * metersPerDegree
        val east = (other.longitude - magalang.longitude) * metersPerDegree * cos(Math.toRadians(15.2169))
        assertEquals(hypot(north, east), NavigationMath.distanceMeters(magalang, other), 0.05)
        assertEquals(0.0, NavigationMath.distanceMeters(magalang, magalang), 1e-9)
    }

    @Test
    fun `bearing follows compass convention`() {
        val origin = GeoPoint(0.0, 0.0)
        assertEquals(0.0, NavigationMath.bearingDegrees(origin, GeoPoint(0.01, 0.0)), 1e-6)
        assertEquals(90.0, NavigationMath.bearingDegrees(origin, GeoPoint(0.0, 0.01)), 1e-6)
        assertEquals(180.0, NavigationMath.bearingDegrees(origin, GeoPoint(-0.01, 0.0)), 1e-6)
        assertEquals(270.0, NavigationMath.bearingDegrees(origin, GeoPoint(0.0, -0.01)), 1e-6)
        assertEquals(45.0, NavigationMath.bearingDegrees(origin, GeoPoint(0.001, 0.001)), 0.01)
        // Field example: the second README test point is north-east of the first.
        assertEquals("NE", NavigationMath.cardinal(NavigationMath.bearingDegrees(magalang, GeoPoint(15.2171, 120.6604))))
    }

    @Test
    fun `cardinal directions use 45 degree sectors centred on each direction`() {
        val cases = mapOf(
            0.0 to "N", 22.4 to "N", 22.5 to "NE", 45.0 to "NE", 67.4 to "NE", 67.5 to "E", 90.0 to "E",
            135.0 to "SE", 180.0 to "S", 225.0 to "SW", 270.0 to "W", 315.0 to "NW", 337.4 to "NW",
            337.5 to "N", 359.9 to "N", 360.0 to "N", -45.0 to "NW", 720.0 to "N",
        )
        cases.forEach { (bearing, expected) -> assertEquals("bearing $bearing", expected, NavigationMath.cardinal(bearing)) }
    }

    @Test
    fun `speed converts from meters per second to km per hour`() {
        assertEquals(3.6, NavigationMath.metersPerSecondToKmh(1.0), 1e-9)
        assertEquals(4.7, NavigationMath.metersPerSecondToKmh(1.30556), 0.001)
        assertEquals("4.7 km/h", NavigationFormat.speedKmh(1.30556))
        assertEquals("0.0 km/h", NavigationFormat.speedKmh(0.0))
    }

    @Test
    fun `eta is distance over speed and never divides by zero`() {
        assertEquals(306L, NavigationMath.etaSeconds(428.0, 1.4)) // 305.7 s rounds up
        assertEquals(0L, NavigationMath.etaSeconds(0.0, 1.4))
        assertNull(NavigationMath.etaSeconds(428.0, 0.0))
        assertNull(NavigationMath.etaSeconds(428.0, -1.0))
        assertNull(NavigationMath.etaSeconds(428.0, null))
        assertNull(NavigationMath.etaSeconds(428.0, Double.NaN))
        assertNull(NavigationMath.etaSeconds(Double.NaN, 1.4))
    }

    @Test
    fun `distance formatting switches to kilometres at 1 km`() {
        assertEquals("428 m", NavigationFormat.distance(428.4))
        assertEquals("0 m", NavigationFormat.distance(0.2))
        assertEquals("999 m", NavigationFormat.distance(999.4))
        assertEquals("1.0 km", NavigationFormat.distance(999.6))
        assertEquals("1.4 km", NavigationFormat.distance(1_400.0))
        assertEquals("12.3 km", NavigationFormat.distance(12_345.0))
    }

    @Test
    fun `duration formatting`() {
        assertEquals("Less than 1 min", NavigationFormat.duration(0))
        assertEquals("Less than 1 min", NavigationFormat.duration(59))
        assertEquals("1 min", NavigationFormat.duration(60))
        assertEquals("6 min", NavigationFormat.duration(306))
        assertEquals("59 min", NavigationFormat.duration(3_540))
        assertEquals("1 hr", NavigationFormat.duration(3_600))
        assertEquals("1 hr 12 min", NavigationFormat.duration(4_320))
    }

    @Test
    fun `geodesic path keeps endpoints and lies on the great circle`() {
        val from = GeoPoint(15.0, 120.0)
        val to = GeoPoint(16.0, 121.5)
        val path = NavigationMath.geodesicPath(from, to, segments = 10)
        assertEquals(11, path.size)
        assertEquals(from, path.first())
        assertEquals(to, path.last())
        val total = NavigationMath.distanceMeters(from, to)
        val summed = path.zipWithNext { a, b -> NavigationMath.distanceMeters(a, b) }.sum()
        assertEquals(total, summed, total * 1e-9)
        path.zipWithNext { a, b -> assertEquals(total / 10, NavigationMath.distanceMeters(a, b), 0.01) }
    }
}
