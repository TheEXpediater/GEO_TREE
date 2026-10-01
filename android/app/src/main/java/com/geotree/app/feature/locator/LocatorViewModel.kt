package com.geotree.app.feature.locator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.location.GpsFix
import com.geotree.app.core.location.LocationPermission
import com.geotree.app.core.location.LocationSource
import com.geotree.app.core.location.LocationUpdateProfile
import com.geotree.app.core.sync.SyncActivity
import com.geotree.app.core.sync.SyncOutcome
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.core.sync.SyncTrigger
import com.geotree.app.data.repository.SyncCounts
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.locator.map.Basemap
import com.geotree.app.feature.locator.map.CameraCommand
import com.geotree.app.feature.locator.map.MapCamera
import com.geotree.app.feature.locator.map.MapCoverage
import com.geotree.app.feature.locator.map.MapSourceType
import com.geotree.app.feature.locator.map.OfflineMapRegion
import com.geotree.app.feature.locator.map.mapCoverage
import com.geotree.app.feature.locator.map.resolveBasemap
import com.geotree.app.feature.navigation.FieldNavigationController
import com.geotree.app.feature.navigation.FieldNavigationState
import com.geotree.app.feature.navigation.GeoPoint
import com.geotree.app.feature.navigation.NavigationDestination
import com.geotree.app.feature.navigation.NavigationMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TrackingState { PERMISSION_REQUIRED, LOCATION_DISABLED, SEARCHING, TRACKING }

data class SyncIndicator(val label: String, val tone: PillTone)

/** Pure mapping from Room counts + worker activity to the locator's sync chip. */
fun syncIndicator(counts: SyncCounts, activity: SyncActivity): SyncIndicator {
    val waiting = counts.pending + counts.failed
    return when {
        activity.running || counts.syncing > 0 -> SyncIndicator("Syncing…", PillTone.Neutral)
        waiting > 0 && activity.lastOutcome == SyncOutcome.AuthExpired -> SyncIndicator("Sign in to sync", PillTone.Error)
        waiting > 0 && activity.lastOutcome is SyncOutcome.BackendUnavailable -> SyncIndicator("Offline · $waiting pending", PillTone.Warning)
        counts.failed > 0 -> SyncIndicator("${counts.failed} failed", PillTone.Error)
        counts.pending > 0 -> SyncIndicator("${counts.pending} pending", PillTone.Amber)
        counts.total == 0 -> SyncIndicator("No trees yet", PillTone.Neutral)
        else -> SyncIndicator("All synced", PillTone.Good)
    }
}

/** Field-level zoom policy, kept in one place. Tuned on the Medium Phone emulator. */
object MapZoom {
    /** Opening the map before GPS: the PSAU deployment area. */
    const val AREA_OVERVIEW = 15.0
    /** Opening the map at the current location. */
    const val OVERVIEW = 15.5
    /** My Location button. */
    const val MY_LOCATION = 17.0
    /** Selecting or focusing a tree; a closer zoom the user chose is kept up to [TREE_MAX]. */
    const val TREE = 17.5
    const val TREE_MAX = 18.0
    /** Upper bound when framing user + destination at navigation start. */
    const val NAVIGATION_MAX = 17.5
    /** Lowest zoom while the offline field map is shown: one level above the whole package. */
    const val OFFLINE_MIN = (OfflineMapRegion.MIN_ZOOM - 1).toDouble()
    /** Before any fix or saved camera: the configured deployment area, never a country or world view. */
    val NO_LOCATION = MapCamera(OfflineMapRegion.DEFAULT_CENTER_LATITUDE, OfflineMapRegion.DEFAULT_CENTER_LONGITUDE, AREA_OVERVIEW)
}

class LocatorViewModel(
    private val repository: TreeRepository,
    private val locationSource: LocationSource,
    private val syncTrigger: SyncTrigger,
    syncTracker: SyncStatusTracker,
    private val navigation: FieldNavigationController,
    basemapSource: Flow<Basemap>,
) : ViewModel() {

    /** Markers are driven only by Room. */
    val trees: StateFlow<List<TreeEntity>> = repository.observeTrees()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncIndicator: StateFlow<SyncIndicator> = combine(repository.observeSyncCounts(), syncTracker.state, ::syncIndicator)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncIndicator("…", PillTone.Neutral))

    val basemap: StateFlow<Basemap> = basemapSource
        .stateIn(viewModelScope, SharingStarted.Eagerly, resolveBasemap(MapSourceType.OFFLINE_PSAU, null, "Preparing offline map…"))

    private val _currentFix = MutableStateFlow(locationSource.lastFix.value)
    val currentFix: StateFlow<GpsFix?> = _currentFix.asStateFlow()

    private val _tracking = MutableStateFlow(TrackingState.SEARCHING)
    val tracking: StateFlow<TrackingState> = _tracking.asStateFlow()

    /** The stream currently running, or null when GPS updates are stopped. */
    private val _activeProfile = MutableStateFlow<LocationUpdateProfile?>(null)
    val activeProfile: StateFlow<LocationUpdateProfile?> = _activeProfile.asStateFlow()

    private val _selectedTreeId = MutableStateFlow<String?>(null)
    val selectedTreeId: StateFlow<String?> = _selectedTreeId.asStateFlow()

    private val _cameraCommand = MutableStateFlow<CameraCommand?>(null)
    val cameraCommand: StateFlow<CameraCommand?> = _cameraCommand.asStateFlow()

    /** Explicit Follow Location mode. Any map gesture turns it off; nothing turns it on by itself. */
    private val _following = MutableStateFlow(false)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    val navigationState: StateFlow<FieldNavigationState?> = navigation.state

    /** Distance from the current fix to the selected tree, for the bottom sheet. */
    val selectedTreeDistanceMeters: StateFlow<Double?> = combine(_selectedTreeId, trees, _currentFix) { id, list, fix ->
        val tree = list.firstOrNull { it.id == id } ?: return@combine null
        fix ?: return@combine null
        NavigationMath.distanceMeters(GeoPoint(fix.latitude, fix.longitude), GeoPoint(tree.latitude, tree.longitude))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the user's position is on the offline field map (GPS itself is unaffected). */
    val coverage: StateFlow<MapCoverage> = combine(basemap, _currentFix, ::mapCoverage)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapCoverage.NOT_APPLICABLE)

    /** Last camera the user left the map at, restored when returning to the Map tab. */
    var lastCamera: MapCamera? = null
        private set

    private var trackingJob: Job? = null
    private var commandNonce = 0L
    private var centeredOnFirstFix = false
    /** The user or a command has moved the camera; the first-fix centring must not override that. */
    private var cameraMoved = false
    private var centerOnNextFix = false

    /** Where the map opens: last camera → current GPS → the PSAU deployment area. */
    fun initialCamera(): MapCamera {
        lastCamera?.let { return it }
        _currentFix.value?.let { return MapCamera(it.latitude, it.longitude, MapZoom.OVERVIEW) }
        return MapZoom.NO_LOCATION
    }

    fun startTracking() {
        val profile = if (navigation.isActive) LocationUpdateProfile.NAVIGATION else LocationUpdateProfile.LOCATOR
        when {
            locationSource.permission() == LocationPermission.NONE -> _tracking.value = TrackingState.PERMISSION_REQUIRED
            !locationSource.isLocationEnabled() -> _tracking.value = TrackingState.LOCATION_DISABLED
            trackingJob?.isActive == true && _activeProfile.value == profile -> Unit
            else -> {
                trackingJob?.cancel()
                if (_currentFix.value == null) _tracking.value = TrackingState.SEARCHING
                _activeProfile.value = profile
                trackingJob = viewModelScope.launch {
                    try {
                        locationSource.updates(profile)
                            .catch { _tracking.value = TrackingState.PERMISSION_REQUIRED }
                            .collect(::onFix)
                    } finally {
                        if (_activeProfile.value == profile) _activeProfile.value = null
                    }
                }
            }
        }
    }

    /**
     * Releases GPS. Called when the Map leaves the screen or the app goes to the background.
     * The last fix is dropped too: when the map returns it must not draw an old position as current.
     */
    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _activeProfile.value = null
        _currentFix.value = null
        if (_tracking.value == TrackingState.TRACKING) _tracking.value = TrackingState.SEARCHING
    }

    private suspend fun onFix(fix: GpsFix) {
        _currentFix.value = fix
        _tracking.value = TrackingState.TRACKING
        navigation.onLocation(fix)
        when {
            centerOnNextFix -> {
                centerOnNextFix = false
                centeredOnFirstFix = true
                center(fix.latitude, fix.longitude, MapZoom.MY_LOCATION)
            }
            _following.value -> center(fix.latitude, fix.longitude, zoom = null, durationMillis = 500)
            // Centre once on the first fresh fix, unless the user or a command already moved the map.
            // (The opening camera may come from a stale cached location, so it is not enough on its own.)
            !centeredOnFirstFix && !cameraMoved -> {
                centeredOnFirstFix = true
                center(fix.latitude, fix.longitude, MapZoom.OVERVIEW)
            }
        }
    }

    fun centerOnCurrentLocation() {
        val fix = _currentFix.value
        if (fix == null) {
            centerOnNextFix = true
            startTracking()
        } else {
            center(fix.latitude, fix.longitude, MapZoom.MY_LOCATION)
        }
    }

    fun toggleFollowLocation() = setFollowing(!_following.value)

    fun setFollowing(follow: Boolean) {
        _following.value = follow
        navigation.setFollowing(follow)
        if (!follow) return
        val fix = _currentFix.value
        if (fix == null) {
            startTracking()
        } else {
            val zoom = lastCamera?.zoom?.takeIf { it >= MapZoom.OVERVIEW } ?: MapZoom.MY_LOCATION
            center(fix.latitude, fix.longitude, zoom)
        }
    }

    /** A finger moved the map: stop following so the camera never fights the user. */
    fun onUserGesture() {
        cameraMoved = true
        if (_following.value) setFollowing(false)
    }

    /** Marker tap: select and bring the tree to field-level zoom. */
    fun selectTree(id: String?) {
        _selectedTreeId.value = id
        if (id != null) focusTree(id)
    }

    fun focusTree(id: String) {
        val tree = trees.value.firstOrNull { it.id == id } ?: return
        _selectedTreeId.value = id
        if (_following.value) setFollowing(false)
        val zoom = maxOf(lastCamera?.zoom ?: 0.0, MapZoom.TREE).coerceAtMost(MapZoom.TREE_MAX)
        center(tree.latitude, tree.longitude, zoom)
    }

    fun onCameraIdle(camera: MapCamera) {
        lastCamera = camera
    }

    /** Starts field guidance to [treeId], switching GPS to the navigation update rate. */
    fun startNavigation(treeId: String) {
        val tree = trees.value.firstOrNull { it.id == treeId } ?: return
        val destination = NavigationDestination(tree.id, tree.treeCode, tree.latitude, tree.longitude)
        viewModelScope.launch {
            navigation.start(destination, _currentFix.value, following = _following.value)
            _selectedTreeId.value = null
            startTracking() // restarts the stream with LocationUpdateProfile.NAVIGATION
            val fix = _currentFix.value
            if (fix == null) {
                center(tree.latitude, tree.longitude, MapZoom.TREE)
            } else if (!_following.value) {
                cameraMoved = true
                _cameraCommand.value = CameraCommand.Fit(
                    points = listOf(GeoPoint(fix.latitude, fix.longitude), destination.point),
                    maxZoom = MapZoom.NAVIGATION_MAX,
                    nonce = ++commandNonce,
                )
            }
        }
    }

    /** Ends guidance and drops back to the slower locator update rate. */
    fun stopNavigation() {
        navigation.stop()
        if (trackingJob?.isActive == true) startTracking()
    }

    fun acknowledgeArrival() = navigation.acknowledgeArrival()

    /** Called every couple of seconds while the guidance panel is visible: hides a speed that went stale. */
    fun onNavigationTick() = navigation.expireStaleSpeed()

    fun syncNow() = syncTrigger.requestSync(replace = true)

    private fun center(latitude: Double, longitude: Double, zoom: Double?, durationMillis: Int = 700) {
        cameraMoved = true
        _cameraCommand.value = CameraCommand.Center(latitude, longitude, zoom, durationMillis, ++commandNonce)
    }

    override fun onCleared() {
        stopTracking()
        navigation.stop()
    }
}
