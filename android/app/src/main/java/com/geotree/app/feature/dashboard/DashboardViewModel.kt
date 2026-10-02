package com.geotree.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.location.LocationSource
import com.geotree.app.core.network.BackendStatus
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.core.sync.SyncTrigger
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionState
import com.geotree.app.core.session.sessionState
import com.geotree.app.core.sync.authRequired
import com.geotree.app.feature.locator.map.OfflineMapState
import com.geotree.app.feature.locator.syncIndicator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dashboard data is local: counts and recent trees from Room, GPS from the device, sync history
 * from DataStore. The backend check only feeds a status label; nothing here waits for the API.
 */
class DashboardViewModel(
    repository: TreeRepository,
    private val locationSource: LocationSource,
    private val syncTrigger: SyncTrigger,
    syncTracker: SyncStatusTracker,
    backendStatus: StateFlow<BackendStatus>,
    backendUrl: Flow<String>,
    lastSyncAt: Flow<Long?>,
    deviceOnline: Flow<Boolean>,
    offlineMap: Flow<OfflineMapState> = flowOf(OfflineMapState.Checking),
    session: Flow<Session?> = flowOf(null),
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshBackend: suspend () -> Unit,
    private val refreshLastKnownFix: suspend () -> Unit = {},
) : ViewModel() {

    private val gps = MutableStateFlow(gpsAvailability(locationSource.permission(), locationSource.isLocationEnabled()))

    /** Re-read on every resume so a session that expires while the app is open is noticed. */
    private val now = MutableStateFlow(clock())
    private val sessionState = combine(session, now) { s, t -> if (s == null) SessionState.NONE else sessionState(s, t) }

    private val local = combine(repository.observeTrees(), repository.observeSyncCounts(), syncTracker.state, sessionState) { trees, counts, activity, s ->
        val needsSignIn = authRequired(s, activity)
        DashboardUiState(
            counts = counts,
            recentTrees = recentTrees(trees),
            sync = syncIndicator(counts, activity, needsSignIn),
            syncRunning = activity.running,
            session = s,
            authRequired = needsSignIn,
        )
    }

    private val device = combine(gps, locationSource.lastFix, deviceOnline.map<Boolean, Boolean?> { it }.onStart { emit(null) }) { g, fix, online ->
        Triple(g, fix, online)
    }

    private val server = combine(backendStatus, backendUrl, lastSyncAt) { status, url, lastSync -> Triple(status, url, lastSync) }

    val state: StateFlow<DashboardUiState> = combine(local, device, server, offlineMap) { base, (g, fix, online), (status, url, lastSync), map ->
        base.copy(gps = g, lastFix = fix, deviceOnline = online, backend = status, backendUrl = url, lastSyncAt = lastSync, offlineMap = map)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        // Re-check the backend after each sync run so the status reflects what sync just saw.
        viewModelScope.launch {
            syncTracker.state.map { it.lastFinishedAt }.distinctUntilChanged().drop(1).collect { refreshBackend() }
        }
    }

    /** Called whenever the Dashboard becomes visible: permissions or services may have changed. */
    fun refresh() {
        now.value = clock()
        gps.value = gpsAvailability(locationSource.permission(), locationSource.isLocationEnabled())
        viewModelScope.launch { refreshLastKnownFix() }
        viewModelScope.launch { refreshBackend() }
    }

    /** Manual sync. Never blocks field work: WorkManager runs it when the network allows. */
    fun syncNow() {
        syncTrigger.requestSync(replace = true)
        viewModelScope.launch { refreshBackend() }
    }
}
