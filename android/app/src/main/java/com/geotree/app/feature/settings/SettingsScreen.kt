package com.geotree.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.geotree.app.BuildConfig
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.network.BackendConnectionManager
import com.geotree.app.core.network.BackendStatus
import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionState
import com.geotree.app.core.session.sessionState
import com.geotree.app.core.sync.SyncActivity
import com.geotree.app.core.sync.SyncScheduler
import com.geotree.app.core.sync.authRequired
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.data.repository.SyncCounts
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.dashboard.coverageText
import com.geotree.app.feature.dashboard.display
import com.geotree.app.feature.dashboard.offlineMapStatusLabel
import com.geotree.app.feature.locator.map.Basemap
import com.geotree.app.feature.locator.map.MapSourceSettings
import com.geotree.app.feature.locator.map.MapSourceType
import com.geotree.app.feature.locator.map.OfflineMapRegion
import com.geotree.app.feature.locator.map.OfflineMapState
import com.geotree.app.feature.locator.map.resolveBasemap
import com.geotree.app.feature.locator.syncIndicator
import com.geotree.app.feature.navigation.NavigationConfig
import com.geotree.app.geoViewModel
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Settings has exactly three sections: Synchronization, Map and Account. The server address is a
 * build setting (the Render API in client builds), not something to edit here; classifier and
 * user administration live in the web admin.
 */
class SettingsViewModel(
    repository: TreeRepository,
    unsyncedScans: Flow<Int>,
    session: Flow<Session?>,
    backendUrl: Flow<String>,
    lastSyncAt: Flow<Long?>,
    deviceOnline: Flow<Boolean>,
    basemap: Flow<Basemap>,
    val syncActivity: StateFlow<SyncActivity>,
    val offlineMap: StateFlow<OfflineMapState>,
    private val backendConnection: BackendConnectionManager,
    private val mapSourceSettings: MapSourceSettings,
    private val syncScheduler: SyncScheduler,
    private val authRepository: AuthRepository,
    private val rescanMaps: () -> Unit,
) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val session = session.state(null)
    val backendUrl = backendUrl.state("")
    val backendStatus: StateFlow<BackendStatus> = backendConnection.status
    val counts = repository.observeSyncCounts().state(SyncCounts())
    val unsyncedScans = unsyncedScans.state(0)
    val lastSyncAt = lastSyncAt.state(null)
    val deviceOnline = deviceOnline.state<Boolean?>(null)
    val basemap = basemap.state(resolveBasemap(MapSourceType.OFFLINE_PSAU, null, "Preparing offline map…"))

    fun refreshBackend() {
        viewModelScope.launch { backendConnection.refreshStatus() }
    }

    /** Fills in the account role for sessions saved before roles existed (shown under Account). */
    fun refreshProfile() {
        viewModelScope.launch { authRepository.refreshProfile() }
    }

    fun syncNow() {
        syncScheduler.requestSync(replace = true)
        refreshBackend()
    }

    fun setMapSource(type: MapSourceType) {
        viewModelScope.launch { mapSourceSettings.setRequested(type) }
    }

    fun rescanOfflineMaps() = rescanMaps()

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            syncScheduler.cancelAll()
            authRepository.logout()
            onDone()
        }
    }
}

@Composable
fun SettingsScreen(onSignedOut: () -> Unit, onSignInToSync: () -> Unit, onOpenMap: () -> Unit) {
    val viewModel = geoViewModel { c, _ ->
        SettingsViewModel(
            repository = c.treeRepository,
            unsyncedScans = c.leafAssessmentRepository.observeUnsyncedCount(),
            session = c.sessionStore.session,
            backendUrl = c.backendConfig.baseUrl,
            lastSyncAt = c.syncPreferences.lastSuccessfulSyncAt,
            deviceOnline = c.networkMonitor.isConnected,
            basemap = c.basemap(),
            syncActivity = c.syncStatusTracker.state,
            offlineMap = c.offlineMapInstaller.state,
            backendConnection = c.backendConnection,
            mapSourceSettings = c.mapSourceSettings,
            syncScheduler = c.syncScheduler,
            authRepository = c.authRepository,
            rescanMaps = c::rescanOfflineMaps,
        )
    }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val url by viewModel.backendUrl.collectAsStateWithLifecycle()
    val status by viewModel.backendStatus.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val unsyncedScans by viewModel.unsyncedScans.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val online by viewModel.deviceOnline.collectAsStateWithLifecycle()
    val activity by viewModel.syncActivity.collectAsStateWithLifecycle()
    val basemap by viewModel.basemap.collectAsStateWithLifecycle()
    val offlineMap by viewModel.offlineMap.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackend()
        viewModel.refreshProfile()
        onPauseOrDispose { }
    }

    val current = session
    val state = sessionState(current, System.currentTimeMillis())
    val needsSignIn = authRequired(state, activity)

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("settings"),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

        SettingsCard("Synchronization", tag = "settings_section_sync") {
            val indicator = syncIndicator(counts, activity, needsSignIn)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                StatusPill(indicator.label, indicator.tone, Modifier.testTag("settings_sync_status"))
            }
            val server = status.display()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Server · " + (url.toHttpUrlOrNull()?.host ?: url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("settings_server_host"),
                )
                StatusPill(server.text, server.tone, Modifier.testTag("settings_backend_status"))
            }
            KeyValue("Last successful sync", lastSync?.let { Formatters.dateTime(it) } ?: "Never on this device")
            KeyValue("Trees waiting", "${counts.pending + counts.failed} (pending ${counts.pending}, failed ${counts.failed})")
            KeyValue("Leaf scans waiting", unsyncedScans.toString())
            Text(
                syncDetailText(activity, online, needsSignIn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_sync_detail"),
            )
            OutlinedButton(
                onClick = viewModel::syncNow,
                enabled = !activity.running,
                modifier = Modifier.fillMaxWidth().testTag("settings_sync_now"),
            ) { Text(if (activity.running) "Syncing…" else "Sync Now") }
        }

        SettingsCard("Map", tag = "settings_section_map") {
            FilledTonalButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth().testTag("settings_open_map")) { Text("Open Map") }
            Text("Map source", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                MapSourceOption(
                    title = "Offline PSAU Map",
                    subtitle = "Works with no internet inside the field area below.",
                    selected = basemap.requested == MapSourceType.OFFLINE_PSAU,
                    onSelect = { viewModel.setMapSource(MapSourceType.OFFLINE_PSAU) },
                    tag = "map_source_offline",
                )
                MapSourceOption(
                    title = "Online Map",
                    subtitle = "OpenStreetMap over the internet. Uses data; not an offline map.",
                    selected = basemap.requested == MapSourceType.ONLINE_DEV,
                    onSelect = { viewModel.setMapSource(MapSourceType.ONLINE_DEV) },
                    tag = "map_source_online",
                )
            }
            OfflineMapDetails(offlineMap)
            if (basemap.packageName != null && basemap.packageName != (offlineMap as? OfflineMapState.Ready)?.file?.name) {
                Text(basemap.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("basemap_status"))
            }
            val guidance = NavigationConfig.Default
            KeyValue("Field guidance", "Direct line to tree (not road routing)")
            KeyValue("Arrival radius", String.format(Locale.US, "%.0f m", guidance.arrivalRadiusMeters))
            KeyValue("Walking estimate pace", String.format(Locale.US, "%.1f km/h", guidance.walkingSpeedMps * 3.6))
            Text(
                "Tree markers, GPS and field guidance work with or without a map. Map data © OpenStreetMap contributors (ODbL).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = viewModel::rescanOfflineMaps) { Text("Rescan for side-loaded map packages") }
        }

        SettingsCard("Account", tag = "settings_section_account") {
            KeyValue("Signed in as", current?.email ?: "—")
            current?.roleLabel?.let { KeyValue("Role", it) }
            KeyValue(
                "Session",
                when {
                    current == null -> "—"
                    state == SessionState.EXPIRED -> "Expired · sync paused"
                    current.expiresAtMillis > 0 -> "Valid until ${Formatters.shortDateTime(current.expiresAtMillis)}"
                    else -> "Valid"
                },
            )
            if (needsSignIn) {
                Button(onClick = onSignInToSync, modifier = Modifier.fillMaxWidth().testTag("settings_signin_to_sync")) { Text("Sign In to Sync") }
            }
            Text(
                "Sign-in lasts 7 days and works offline. Signing out is only needed to switch accounts; trees on this device are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth().testTag("settings_sign_out")) { Text("Sign Out") }
            KeyValue("App version", BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug build)" else "")
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Trees saved on this device stay here. Unsynced trees will sync after you sign in again.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut(onSignedOut) }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, tag: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.6f, fill = false),
        )
    }
}

@Composable
private fun MapSourceOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit, tag: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp)
            .testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OfflineMapDetails(state: OfflineMapState) {
    val label = offlineMapStatusLabel(state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Offline map", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        StatusPill(label.text, label.tone, Modifier.testTag("offline_map_status"))
    }
    if (state is OfflineMapState.Missing) Text(state.reason, style = MaterialTheme.typography.bodySmall)
    if (state is OfflineMapState.Failed) Text(state.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    KeyValue("Region", OfflineMapRegion.NAME)
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text("Coverage", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(coverageText(OfflineMapRegion.bounds), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("offline_map_coverage"))
    }
    KeyValue("Zoom levels", "${OfflineMapRegion.MIN_ZOOM}–${OfflineMapRegion.MAX_ZOOM}")
    if (state is OfflineMapState.Ready) {
        KeyValue("Package", String.format(Locale.US, "%.1f MB", state.info.bytes / 1_000_000.0))
        KeyValue("Version", state.info.version)
    }
}
