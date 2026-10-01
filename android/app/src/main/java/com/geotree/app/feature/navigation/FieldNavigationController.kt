package com.geotree.app.feature.navigation

import com.geotree.app.core.location.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Field guidance to one tree: turns GPS fixes into distance, bearing, speed, ETA and arrival.
 * It does not own a location stream; the map's ViewModel feeds it fixes from the
 * navigation-profile stream and stops that stream when guidance ends.
 *
 * Distance and the drawn line come from [routeProvider]; bearing and arrival always use the
 * direct distance to the tree, so they stay meaningful when a road provider is added later.
 */
class FieldNavigationController(
    private val routeProvider: RouteProvider,
    private val config: NavigationConfig = NavigationConfig.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<FieldNavigationState?>(null)
    val state: StateFlow<FieldNavigationState?> = _state.asStateFlow()

    private val speedEstimator = SpeedEstimator(config)
    private var nextSessionId = 0L
    private var lastFixReceivedAt: Long? = null

    val isActive: Boolean get() = _state.value != null

    /**
     * Starts (or switches) guidance. A destination change is a new session: arrival is re-armed.
     * [currentFix] seeds the session only if it is fresh; otherwise the next streamed fix does.
     */
    suspend fun start(destination: NavigationDestination, currentFix: GpsFix?, following: Boolean = false) {
        speedEstimator.reset()
        lastFixReceivedAt = null
        _state.value = FieldNavigationState(sessionId = ++nextSessionId, destination = destination, isFollowingLocation = following)
        val fresh = currentFix?.takeIf { clock() - it.capturedAt <= config.maxStartFixAgeMillis }
        if (fresh != null) onLocation(fresh)
    }

    fun stop() {
        _state.value = null
        speedEstimator.reset()
        lastFixReceivedAt = null
    }

    /**
     * Drops a measured speed (and its ETA) once no fix has arrived for [NavigationConfig.maxSpeedAgeMillis],
     * e.g. GPS lost under trees or the map was left. Called periodically by the UI while guidance shows.
     */
    fun expireStaleSpeed() {
        val receivedAt = lastFixReceivedAt ?: return
        if (clock() - receivedAt <= config.maxSpeedAgeMillis) return
        speedEstimator.reset()
        _state.update { current ->
            if (current == null || current.speed == SpeedReading.Unavailable) return@update current
            val route = current.route
            val (eta, source) = route?.let { estimateArrival(it, SpeedReading.Unavailable) } ?: (null to EtaSource.UNAVAILABLE)
            current.copy(speed = SpeedReading.Unavailable, etaSeconds = eta, etaSource = source)
        }
    }

    fun setFollowing(following: Boolean) = _state.update { it?.copy(isFollowingLocation = following) }

    fun acknowledgeArrival() = _state.update { it?.copy(arrivalAlertPending = false) }

    suspend fun onLocation(fix: GpsFix) {
        val session = _state.value ?: return
        val here = GeoPoint(fix.latitude, fix.longitude)
        val target = session.destination.point
        val route = routeProvider.route(here, target)
        val receivedAt = clock()
        val speed = speedEstimator.update(fix, receivedAt)
        val directDistance = NavigationMath.distanceMeters(here, target)
        val (eta, etaSource) = estimateArrival(route, speed)
        val arrivedNow = directDistance <= config.arrivalRadiusMeters

        _state.update { current ->
            // Ignore a fix computed for a session that was stopped or replaced meanwhile.
            if (current == null || current.sessionId != session.sessionId) return@update current
            lastFixReceivedAt = receivedAt
            current.copy(
                currentLatitude = fix.latitude,
                currentLongitude = fix.longitude,
                distanceRemainingMeters = route.distanceMeters,
                bearingDegrees = if (directDistance > 0.0) NavigationMath.bearingDegrees(here, target) else current.bearingDegrees,
                speed = speed,
                etaSeconds = eta,
                etaSource = etaSource,
                gpsAccuracy = fix.accuracyMeters,
                route = route,
                arrived = current.arrived || arrivedNow,
                arrivalAlertPending = current.arrivalAlertPending || (arrivedNow && !current.arrived),
            )
        }
    }

    private fun estimateArrival(route: RouteResult, speed: SpeedReading): Pair<Long?, EtaSource> {
        if (speed is SpeedReading.Moving) {
            NavigationMath.etaSeconds(route.distanceMeters, speed.metersPerSecond)?.let { return it to EtaSource.CURRENT_SPEED }
        }
        val fallback = route.estimatedDurationSeconds ?: return null to EtaSource.UNAVAILABLE
        return fallback to EtaSource.WALKING_ESTIMATE
    }
}
