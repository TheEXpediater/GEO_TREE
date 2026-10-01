package com.geotree.app.feature.navigation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRouteProviderTest {
    private val provider = DirectRouteProvider(NavigationConfig(walkingSpeedMps = 1.4))

    @Test
    fun `short field route is a two-point direct line with a walking estimate`() = runTest {
        val from = GeoPoint(15.2167, 120.6600)
        val to = GeoPoint(15.2171, 120.6604)

        val route = provider.route(from, to)

        assertEquals(RouteType.DIRECT, route.routeType)
        assertEquals(listOf(from, to), route.geometry)
        assertEquals(NavigationMath.distanceMeters(from, to), route.distanceMeters, 1e-9)
        assertEquals(NavigationMath.etaSeconds(route.distanceMeters, 1.4), route.estimatedDurationSeconds)
    }

    @Test
    fun `long route is densified along the geodesic`() = runTest {
        val from = GeoPoint(15.2167, 120.6600)
        val to = GeoPoint(15.1449, 120.5887) // ~11 km

        val route = provider.route(from, to)

        assertTrue("expected intermediate vertices, got ${route.geometry.size}", route.geometry.size > 10)
        assertEquals(from, route.geometry.first())
        assertEquals(to, route.geometry.last())
        val along = route.geometry.zipWithNext { a, b -> NavigationMath.distanceMeters(a, b) }.sum()
        assertEquals(route.distanceMeters, along, 0.01)
    }

    @Test
    fun `same point gives a zero-length route`() = runTest {
        val here = GeoPoint(15.2167, 120.66)
        val route = provider.route(here, here)
        assertEquals(0.0, route.distanceMeters, 1e-9)
        assertEquals(0L, route.estimatedDurationSeconds)
        assertEquals(2, route.geometry.size)
    }
}
