package com.geotree.app.feature.locator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.location.GpsFix
import com.geotree.app.core.location.LocationClient
import com.geotree.app.core.location.LocationPermission
import com.geotree.app.core.sync.SyncActivity
import com.geotree.app.core.sync.SyncOutcome
import com.geotree.app.core.sync.SyncScheduler
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.data.repository.SyncCounts
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.locator.map.MapCamera
import kotlinx.coroutines.Job
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

class LocatorViewModel(
    private val repository: TreeRepository,
    private val locationClient: LocationClient,
    private val syncScheduler: SyncScheduler,
    syncTracker: SyncStatusTracker,
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** Markers are driven only by Room. */
    val trees: StateFlow<List<TreeEntity>> = repository.observeTrees()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncIndicator: StateFlow<SyncIndicator> = combine(repository.observeSyncCounts(), syncTracker.state, ::syncIndicator)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncIndicator("…", PillTone.Neutral))

    private val _currentFix = MutableStateFlow<GpsFix?>(null)
    val currentFix: StateFlow<GpsFix?> = _currentFix.asStateFlow()

    private val _tracking = MutableStateFlow(TrackingState.SEARCHING)
    val tracking: StateFlow<TrackingState> = _tracking.asStateFlow()

    private val _selectedTreeId = MutableStateFlow<String?>(null)
    val selectedTreeId: StateFlow<String?> = _selectedTreeId.asStateFlow()

    private val _cameraCommand = MutableStateFlow<MapCamera?>(null)
    val cameraCommand: StateFlow<MapCamera?> = _cameraCommand.asStateFlow()

    /** Last camera the user left the map at, restored when returning from other screens. */
    var lastCamera: MapCamera? = null
        private set

    private var trackingJob: Job? = null
    private var commandNonce = 0L
    private var centeredOnFirstFix = false

    fun startTracking() {
        when {
            locationClient.permission() == LocationPermission.NONE -> _tracking.value = TrackingState.PERMISSION_REQUIRED
            !locationClient.isLocationEnabled() -> _tracking.value = TrackingState.LOCATION_DISABLED
            trackingJob?.isActive == true -> Unit
            else -> {
                _tracking.value = TrackingState.SEARCHING
                trackingJob = viewModelScope.launch {
                    locationClient.updates()
                        .catch { _tracking.value = TrackingState.PERMISSION_REQUIRED }
                        .collect { fix ->
                            _currentFix.value = fix
                            _tracking.value = TrackingState.TRACKING
                            // Center once per screen visit-lifetime, unless a tree focus already moved the camera.
                            if (!centeredOnFirstFix && _cameraCommand.value == null) {
                                centeredOnFirstFix = true
                                moveCamera(fix.latitude, fix.longitude, 17.0)
                            }
                        }
                }
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
    }

    fun centerOnCurrentLocation() {
        val fix = _currentFix.value
        if (fix == null) startTracking() else moveCamera(fix.latitude, fix.longitude, 18.0)
    }

    fun selectTree(id: String?) {
        _selectedTreeId.value = id
    }

    fun focusTree(id: String) {
        val tree = trees.value.firstOrNull { it.id == id } ?: return
        _selectedTreeId.value = id
        moveCamera(tree.latitude, tree.longitude, 18.0)
    }

    fun onCameraIdle(camera: MapCamera) {
        lastCamera = camera
    }

    fun syncNow() = syncScheduler.requestSync(replace = true)

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            syncScheduler.cancelAll()
            authRepository.logout()
            onDone()
        }
    }

    private fun moveCamera(latitude: Double, longitude: Double, zoom: Double) {
        _cameraCommand.value = MapCamera(latitude, longitude, zoom, ++commandNonce)
    }
}
