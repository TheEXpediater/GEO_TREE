package com.geotree.app.feature.navigation

import kotlin.math.ceil

/**
 * DIRECT is a straight great-circle guidance line (works fully offline).
 * ROAD is reserved for a future road/path-following provider.
 */
enum class RouteType { DIRECT, ROAD }

/**
 * A guidance route. [estimatedDurationSeconds] is the provider's own estimate (for DIRECT,
 * a walking estimate at [NavigationConfig.walkingSpeedMps]); null when it cannot estimate.
 */
data class RouteResult(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long?,
    val routeType: RouteType,
)

/**
 * Computes guidance from the user's position to a destination. The navigation UI and controller
 * depend only on this interface, so a future OfflineRoadRouteProvider or ServerRoadRouteProvider
 * can replace [DirectRouteProvider] without touching Dashboard, Map or the navigation panel.
 */
fun interface RouteProvider {
    suspend fun route(from: GeoPoint, to: GeoPoint): RouteResult
}

/** Direct field guidance: the geodesic line from current position to the tree. No roads, no network. */
class DirectRouteProvider(private val config: NavigationConfig = NavigationConfig.Default) : RouteProvider {
    override suspend fun route(from: GeoPoint, to: GeoPoint): RouteResult {
        val distance = NavigationMath.distanceMeters(from, to)
        // One vertex per ~250 m keeps long lines geodesic; short field lines are just two points.
        val segments = ceil(distance / SEGMENT_METERS).toInt().coerceIn(1, MAX_SEGMENTS)
        return RouteResult(
            geometry = NavigationMath.geodesicPath(from, to, segments),
            distanceMeters = distance,
            estimatedDurationSeconds = NavigationMath.etaSeconds(distance, config.walkingSpeedMps),
            routeType = RouteType.DIRECT,
        )
    }

    private companion object {
        const val SEGMENT_METERS = 250.0
        const val MAX_SEGMENTS = 64
    }
}
