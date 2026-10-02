package com.geotree.app.feature.locator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.location.GpsFix
import com.geotree.app.core.orientation.DeclinationSource
import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionState
import com.geotree.app.core.session.sessionState
import com.geotree.app.core.sync.authRequired
import com.geotree.app.core.orientation.HeadingProcessor
import com.geotree.app.core.orientation.HeadingSource
import com.geotree.app.core.orientation.HeadingState
import com.geotree.app.core.orientation.degreesOrNull
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TrackingState { PERMISSION_REQUIRED, LOCATION_DISABLED, SEARCHING, TRACKING }

data class SyncIndicator(val label: String, val tone: PillTone)

/**
 * Pure mapping from Room counts + worker activity to the sync chip. [authRequired] is true while
 * the stored session is expired: field work continues, sync waits for a new sign-in.
 */
fun syncIndicator(counts: SyncCounts, activity: SyncActivity, authRequired: Boolean = false): SyncIndicator {
    val waiting = counts.pending + counts.failed
    return when {
        activity.running || counts.syncing > 0 -> SyncIndicator("Syncing…", PillTone.Neutral)
        authRequired -> SyncIndicator("Sign in to sync", PillTone.Warning)
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

/**
 * How the camera relates to the user.
 * - FREE: the user controls the map (any pan/zoom/rotate gesture returns here).
 * - FOLLOW_LOCATION: the map follows GPS, north up (My Location button).
 * - HEADING_UP: the map follows GPS and rotates with the phone compass (compass control).
 */
enum class MapFollowMode { FREE, FOLLOW_LOCATION, HEADING_UP }

/** Camera target while in [MapFollowMode.HEADING_UP]: the user's position, map bearing = phone heading. */
data class HeadingUpCamera(val latitude: Double, val longitude: Double, val bearing: Double)

/** A short one-off message for the map's snackbar; [nonce] makes repeats distinct. */
data class MapMessage(val text: String, val nonce: Long)

class LocatorViewModel(
    private val repository: TreeRepository,
    private val locationSource: LocationSource,
    private val syncTrigger: SyncTrigger,
    syncTracker: SyncStatusTracker,
    private val navigation: FieldNavigationController,
    basemapSource: Flow<Basemap>,
    private val headingSource: HeadingSource = HeadingSource.None,
    private val declination: DeclinationSource = DeclinationSource.None,
    session: Flow<Session?> = flowOf(null),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Markers are driven only by Room. */
    val trees: StateFlow<List<TreeEntity>> = repository.observeTrees()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncIndicator: StateFlow<SyncIndicator> = combine(repository.observeSyncCounts(), syncTracker.state, session) { counts, activity, s ->
        val state = if (s == null) SessionState.NONE else sessionState(s, clock())
        syncIndicator(counts, activity, authRequired(state, activity))
    }
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

    /** Explicit follow mode. Any map gesture returns to FREE; nothing turns following on by itself. */
    private val _mapMode = MutableStateFlow(MapFollowMode.FREE)
    val mapMode: StateFlow<MapFollowMode> = _mapMode.asStateFlow()

    /** Phone compass heading; measured only while guidance or Heading Up needs it and the map is visible. */
    private val _heading = MutableStateFlow<HeadingState>(if (headingSource.isSupported) HeadingState.Inactive else HeadingState.Unsupported)
    val heading: StateFlow<HeadingState> = _heading.asStateFlow()

    /** Guidance panel size. One navigation session drives both; collapsing changes only the UI. */
    private val _panelExpanded = MutableStateFlow(true)
    val panelExpanded: StateFlow<Boolean> = _panelExpanded.asStateFlow()

    private val _message = MutableStateFlow<MapMessage?>(null)
    val message: StateFlow<MapMessage?> = _message.asStateFlow()

    /** Non-null only in Heading Up with both a GPS fix and a compass heading. */
    val headingUpCamera: StateFlow<HeadingUpCamera?> = combine(_mapMode, _heading, _currentFix) { mode, heading, fix ->
        val degrees = heading.degreesOrNull
        if (mode == MapFollowMode.HEADING_UP && degrees != null && fix != null) HeadingUpCamera(fix.latitude, fix.longitude, degrees) else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    private var headingJob: Job? = null
    private val headingProcessor = HeadingProcessor()
    private var declinationCache: Pair<String, Double?>? = null
    private var commandNonce = 0L
    private var messageNonce = 0L
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
        updateHeadingSubscription()
    }

    /**
     * Compass sensors run only while the Map is visible AND guidance or Heading Up needs them.
     * Cancelling the collector unregisters the sensor listener, so leaving the Map never leaks it.
     */
    private fun updateHeadingSubscription() {
        val wanted = trackingJob?.isActive == true && headingSource.isSupported &&
            (navigation.isActive || _mapMode.value == MapFollowMode.HEADING_UP)
        if (wanted) {
            if (headingJob?.isActive == true) return
            headingProcessor.reset()
            headingJob = viewModelScope.launch {
                headingSource.readings().collect { raw -> _heading.value = headingProcessor.process(raw, currentDeclination()) }
            }
        } else {
            headingJob?.cancel()
            headingJob = null
            _heading.value = if (headingSource.isSupported) HeadingState.Inactive else HeadingState.Unsupported
        }
    }

    /** Magnetic → true north at the user's GPS position (cached per ~5 km cell and day). Null without a fix. */
    private fun currentDeclination(): Double? {
        val fix = _currentFix.value ?: return null
        val key = "${(fix.latitude * 20).toInt()}:${(fix.longitude * 20).toInt()}:${fix.capturedAt / 86_400_000L}"
        declinationCache?.let { (k, v) -> if (k == key) return v }
        val value = declination.declinationDegrees(fix.latitude, fix.longitude, fix.altitudeMeters ?: 0.0, fix.capturedAt)
        declinationCache = key to value
        return value
    }

    /**
     * Releases GPS. Called when the Map leaves the screen or the app goes to the background.
     * The last fix is dropped too: when the map returns it must not draw an old position as current.
     */
    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        updateHeadingSubscription()
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
            _mapMode.value == MapFollowMode.FOLLOW_LOCATION -> center(fix.latitude, fix.longitude, zoom = null, durationMillis = 500)
            // HEADING_UP: the camera follows through [headingUpCamera]; no command per fix.
            _mapMode.value == MapFollowMode.HEADING_UP -> Unit
            // Centre once on the first fresh fix, unless the user or a command already moved the map.
            // (The opening camera may come from a stale cached location, so it is not enough on its own.)
            !centeredOnFirstFix && !cameraMoved -> {
                centeredOnFirstFix = true
                center(fix.latitude, fix.longitude, MapZoom.OVERVIEW)
            }
        }
    }

    /**
     * My Location: centre at street/field zoom and follow (north up). In Heading Up it re-centres
     * and stays in Heading Up.
     */
    fun centerOnCurrentLocation() {
        if (_mapMode.value != MapFollowMode.HEADING_UP) setMode(MapFollowMode.FOLLOW_LOCATION)
        val fix = _currentFix.value
        if (fix == null) {
            centerOnNextFix = true
            startTracking()
        } else if (_mapMode.value == MapFollowMode.FOLLOW_LOCATION) {
            center(fix.latitude, fix.longitude, MapZoom.MY_LOCATION, bearing = 0.0)
        } else {
            center(fix.latitude, fix.longitude, MapZoom.MY_LOCATION)
        }
    }

    /** Compass control: Heading Up ⇄ North Up (following). Heading Up needs a phone compass. */
    fun toggleHeadingUp() {
        if (_mapMode.value == MapFollowMode.HEADING_UP) {
            setMode(MapFollowMode.FOLLOW_LOCATION)
            _currentFix.value?.let { center(it.latitude, it.longitude, zoom = null, bearing = 0.0, durationMillis = 400) }
            return
        }
        if (!headingSource.isSupported) {
            _message.value = MapMessage("This phone has no compass sensor, so Heading Up is not available.", ++messageNonce)
            return
        }
        cameraMoved = true
        setMode(MapFollowMode.HEADING_UP)
        if (_currentFix.value == null) startTracking()
    }

    private fun setMode(mode: MapFollowMode) {
        _mapMode.value = mode
        navigation.setFollowing(mode != MapFollowMode.FREE)
        updateHeadingSubscription()
    }

    /** A finger moved, zoomed or rotated the map: back to FREE so the camera never fights the user. */
    fun onUserGesture() {
        cameraMoved = true
        if (_mapMode.value != MapFollowMode.FREE) setMode(MapFollowMode.FREE)
    }

    fun setPanelExpanded(expanded: Boolean) {
        _panelExpanded.value = expanded
    }

    fun togglePanel() = setPanelExpanded(!_panelExpanded.value)

    /** Marker tap: select and bring the tree to field-level zoom. */
    fun selectTree(id: String?) {
        _selectedTreeId.value = id
        if (id != null) focusTree(id)
    }

    fun focusTree(id: String) {
        val tree = trees.value.firstOrNull { it.id == id } ?: return
        _selectedTreeId.value = id
        if (_mapMode.value != MapFollowMode.FREE) setMode(MapFollowMode.FREE)
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
            navigation.start(destination, _currentFix.value, following = _mapMode.value != MapFollowMode.FREE)
            _selectedTreeId.value = null
            _panelExpanded.value = true
            startTracking() // restarts the stream with LocationUpdateProfile.NAVIGATION (and the compass)
            val fix = _currentFix.value
            if (fix == null) {
                if (_mapMode.value == MapFollowMode.FREE) center(tree.latitude, tree.longitude, MapZoom.TREE)
            } else if (_mapMode.value == MapFollowMode.FREE) {
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
        updateHeadingSubscription()
    }

    fun acknowledgeArrival() = navigation.acknowledgeArrival()

    /** Called every couple of seconds while the guidance panel is visible: hides a speed that went stale. */
    fun onNavigationTick() = navigation.expireStaleSpeed()

    fun syncNow() = syncTrigger.requestSync(replace = true)

    private fun center(latitude: Double, longitude: Double, zoom: Double?, durationMillis: Int = 700, bearing: Double? = null) {
        cameraMoved = true
        _cameraCommand.value = CameraCommand.Center(latitude, longitude, zoom, durationMillis, ++commandNonce, bearing)
    }

    override fun onCleared() {
        stopTracking()
        navigation.stop()
    }
}
