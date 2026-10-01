package com.geotree.app.feature.navigation

/** The tree being guided to. Coordinates are copied from Room when navigation starts. */
data class NavigationDestination(
    val treeId: String,
    val treeCode: String,
    val latitude: Double,
    val longitude: Double,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

/** Where the displayed ETA comes from. Only CURRENT_SPEED uses the user's measured speed. */
enum class EtaSource { CURRENT_SPEED, WALKING_ESTIMATE, UNAVAILABLE }

/**
 * One field-guidance session. Values are null until the first GPS fix of the session arrives.
 * [arrived] latches once per session; [arrivalAlertPending] is true only until the user acknowledges it.
 */
data class FieldNavigationState(
    val sessionId: Long,
    val destination: NavigationDestination,
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val distanceRemainingMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speed: SpeedReading = SpeedReading.Unavailable,
    val etaSeconds: Long? = null,
    val etaSource: EtaSource = EtaSource.UNAVAILABLE,
    val gpsAccuracy: Float? = null,
    val isFollowingLocation: Boolean = false,
    val route: RouteResult? = null,
    val arrived: Boolean = false,
    val arrivalAlertPending: Boolean = false,
) {
    val destinationLatitude: Double get() = destination.latitude
    val destinationLongitude: Double get() = destination.longitude

    /** Measured speed only: 0 when stationary, null when unavailable. Never the walking-speed fallback. */
    val currentSpeedMps: Double?
        get() = when (val s = speed) {
            is SpeedReading.Moving -> s.metersPerSecond
            SpeedReading.Stationary -> 0.0
            SpeedReading.Unavailable -> null
        }

    val hasFix: Boolean get() = currentLatitude != null && currentLongitude != null

    val cardinal: String? get() = bearingDegrees?.let(NavigationMath::cardinal)
}
