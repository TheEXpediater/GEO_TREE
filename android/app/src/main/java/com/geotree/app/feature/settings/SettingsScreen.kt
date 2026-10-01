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
import com.geotree.app.core.network.BackendReachability
import com.geotree.app.core.network.BackendStatus
import com.geotree.app.core.sync.SyncScheduler
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.data.repository.SyncCounts
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.dashboard.coverageText
import com.geotree.app.feature.dashboard.display
import com.geotree.app.feature.dashboard.offlineMapStatusLabel
import com.geotree.app.feature.locator.map.OfflineMapRegion
import com.geotree.app.feature.locator.map.Basemap
import com.geotree.app.feature.locator.map.MapSourceSettings
import com.geotree.app.feature.locator.map.MapSourceType
import com.geotree.app.feature.locator.map.OfflineMapState
import com.geotree.app.feature.locator.map.resolveBasemap
import com.geotree.app.feature.login.ServerSettingsDialog
import com.geotree.app.feature.navigation.NavigationConfig
import com.geotree.app.geoViewModel
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    repository: TreeRepository,
    email: Flow<String?>,
    backendUrl: Flow<String>,
    lastSyncAt: Flow<Long?>,
    basemap: Flow<Basemap>,
    val offlineMap: StateFlow<OfflineMapState>,
    private val backendConnection: BackendConnectionManager,
    private val mapSourceSettings: MapSourceSettings,
    private val syncScheduler: SyncScheduler,
    private val authRepository: AuthRepository,
    private val rescanMaps: () -> Unit,
) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val email = email.state(null)
    val backendUrl = backendUrl.state("")
    val backendStatus: StateFlow<BackendStatus> = backendConnection.status
    val counts = repository.observeSyncCounts().state(SyncCounts())
    val lastSyncAt = lastSyncAt.state(null)
    val basemap = basemap.state(resolveBasemap(MapSourceType.OFFLINE_PSAU, null, "Preparing offline map…"))

    fun refreshBackend() {
        viewModelScope.launch { backendConnection.refreshStatus() }
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
fun SettingsScreen(onSignedOut: () -> Unit) {
    val viewModel = geoViewModel { c, _ ->
        SettingsViewModel(
            repository = c.treeRepository,
            email = c.sessionStore.session.map { it?.email },
            backendUrl = c.backendConfig.baseUrl,
            lastSyncAt = c.syncPreferences.lastSuccessfulSyncAt,
            basemap = c.basemap(),
            offlineMap = c.offlineMapInstaller.state,
            backendConnection = c.backendConnection,
            mapSourceSettings = c.mapSourceSettings,
            syncScheduler = c.syncScheduler,
            authRepository = c.authRepository,
            rescanMaps = c::rescanOfflineMaps,
        )
    }
    val email by viewModel.email.collectAsStateWithLifecycle()
    val url by viewModel.backendUrl.collectAsStateWithLifecycle()
    val status by viewModel.backendStatus.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val basemap by viewModel.basemap.collectAsStateWithLifecycle()
    val offlineMap by viewModel.offlineMap.collectAsStateWithLifecycle()
    var showServer by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackend()
        onPauseOrDispose { }
    }

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

        SettingsCard("Server") {
            val label = status.display()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Backend", modifier = Modifier.weight(1f))
                StatusPill(label.text, label.tone, Modifier.testTag("settings_backend_status"))
            }
            Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("settings_backend_url"))
            status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "Emulator: http://10.0.2.2:8000 · Phone: http://<laptop-LAN-IP>:8000 on the same Wi-Fi. The app cannot start Docker on your laptop; run run.bat there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::refreshBackend,
                    enabled = status.reachability != BackendReachability.CHECKING,
                    modifier = Modifier.weight(1f).testTag("settings_test_connection"),
                ) { Text("Test Connection") }
                OutlinedButton(onClick = { showServer = true }, modifier = Modifier.weight(1f).testTag("settings_change_server")) { Text("Change Server") }
            }
        }

        SettingsCard("Synchronization") {
            KeyValue("Last successful sync", lastSync?.let { Formatters.dateTime(it) } ?: "Never on this device")
            KeyValue("Waiting to sync", "${counts.pending + counts.failed} (pending ${counts.pending}, failed ${counts.failed})")
            OutlinedButton(onClick = viewModel::syncNow, modifier = Modifier.fillMaxWidth().testTag("settings_sync_now")) { Text("Sync Now") }
        }

        SettingsCard("Map") {
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
            Text(
                "Tree markers, GPS and field guidance work with or without a map. Map data © OpenStreetMap contributors (ODbL).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = viewModel::rescanOfflineMaps) { Text("Rescan for side-loaded map packages") }
        }

        SettingsCard("Field guidance") {
            val config = NavigationConfig.Default
            KeyValue("Mode", "Direct line to tree (not road routing)")
            KeyValue("Arrival radius", String.format(Locale.US, "%.0f m", config.arrivalRadiusMeters))
            KeyValue("Walking estimate pace", String.format(Locale.US, "%.1f km/h", config.walkingSpeedMps * 3.6))
        }

        SettingsCard("Account") {
            KeyValue("Signed in as", email ?: "—")
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth().testTag("settings_sign_out")) { Text("Sign Out") }
        }

        Text(
            "GEO Tree ${BuildConfig.VERSION_NAME}" + if (BuildConfig.DEBUG) " · debug build" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showServer) {
        ServerSettingsDialog(onDismiss = { showServer = false }, onSaved = viewModel::syncNow)
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
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
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
