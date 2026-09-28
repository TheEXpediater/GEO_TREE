package com.geotree.app.feature.locator

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geotree.app.R
import com.geotree.app.core.design.CoordinateTextStyle
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoTreeLogo
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.design.label
import com.geotree.app.core.design.tone
import com.geotree.app.core.location.GpsFix
import com.geotree.app.feature.locator.map.TreeMap
import com.geotree.app.feature.login.ServerSettingsDialog
import com.geotree.app.geoViewModel
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocatorScreen(
    focusTreeId: StateFlow<String?>,
    savedTreeCode: StateFlow<String?>,
    onFocusConsumed: () -> Unit,
    onTagTree: () -> Unit,
    onOpenTree: (String) -> Unit,
    onSignedOut: () -> Unit,
) {
    val viewModel = geoViewModel { c, _ ->
        LocatorViewModel(c.treeRepository, c.locationClient, c.syncScheduler, c.syncStatusTracker, c.authRepository)
    }
    val trees by viewModel.trees.collectAsStateWithLifecycle()
    val sync by viewModel.syncIndicator.collectAsStateWithLifecycle()
    val fix by viewModel.currentFix.collectAsStateWithLifecycle()
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedTreeId.collectAsStateWithLifecycle()
    val cameraCommand by viewModel.cameraCommand.collectAsStateWithLifecycle()
    val focusId by focusTreeId.collectAsStateWithLifecycle()
    val savedCode by savedTreeCode.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showList by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.startTracking()
    }
    val requestLocation = {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LifecycleStartEffect(Unit) {
        viewModel.startTracking()
        onStopOrDispose { viewModel.stopTracking() }
    }
    LaunchedEffect(tracking) {
        if (tracking == TrackingState.PERMISSION_REQUIRED) requestLocation()
    }
    // Handed back from Tag Tree / Tree Detail. Wait until Room has emitted the tree.
    LaunchedEffect(focusId, trees) {
        val id = focusId ?: return@LaunchedEffect
        if (trees.none { it.id == id }) return@LaunchedEffect
        viewModel.focusTree(id)
        val code = savedCode
        onFocusConsumed()
        if (code != null) snackbar.showSnackbar("$code saved on this device · sync pending")
    }

    Box(Modifier.fillMaxSize()) {
        TreeMap(
            trees = trees,
            selectedTreeId = selectedId,
            currentFix = fix,
            cameraCommand = cameraCommand,
            initialCamera = viewModel.lastCamera,
            topInset = 150.dp,
            bottomInset = 24.dp,
            onTreeClick = viewModel::selectTree,
            onMapClick = { viewModel.selectTree(null) },
            onCameraIdle = viewModel::onCameraIdle,
            modifier = Modifier.fillMaxSize(),
        )

        LocatorTopBar(
            trackingState = tracking,
            fix = fix,
            sync = sync,
            onShowList = { showList = true },
            onSyncNow = viewModel::syncNow,
            onServerSettings = { showServer = true },
            onSignOut = { confirmSignOut = true },
            onRequestLocation = requestLocation,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
        ) {
            SmallFloatingActionButton(
                onClick = viewModel::centerOnCurrentLocation,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("my_location"),
            ) { Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Center on my location") }
            ExtendedFloatingActionButton(
                onClick = onTagTree,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Tag Tree", fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("tag_tree_fab"),
            )
        }

        Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 16.dp, bottom = 40.dp, end = 160.dp)) {
            if (trees.isEmpty()) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                    Text(
                        "No trees tagged yet.\nTap Tag Tree to register one.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp).testTag("empty_state"),
                    )
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 140.dp))
    }

    trees.firstOrNull { it.id == selectedId }?.let { tree ->
        TreeSummarySheet(
            tree = tree,
            onDismiss = { viewModel.selectTree(null) },
            onViewDetails = { onOpenTree(tree.id) },
        )
    }
    if (showList) {
        TreeListSheet(
            trees = trees,
            onDismiss = { showList = false },
            onSelect = { id ->
                showList = false
                viewModel.focusTree(id)
            },
        )
    }
    if (showServer) ServerSettingsDialog(onDismiss = { showServer = false }, onSaved = viewModel::syncNow)
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
private fun LocatorTopBar(
    trackingState: TrackingState,
    fix: GpsFix?,
    sync: SyncIndicator,
    onShowList: () -> Unit,
    onSyncNow: () -> Unit,
    onServerSettings: () -> Unit,
    onSignOut: () -> Unit,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        modifier = modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GeoTreeLogo(size = 34.dp)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("GEO Tree", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        fix?.let { Formatters.coordinatePair(it.latitude, it.longitude) } ?: "Field Locator",
                        style = if (fix != null) CoordinateTextStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize) else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("locator_coordinates"),
                    )
                }
                IconButton(onClick = onShowList) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "List tagged trees") }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("locator_menu")) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sync now") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
                            onClick = { menuOpen = false; onSyncNow() },
                            modifier = Modifier.testTag("menu_sync_now"),
                        )
                        DropdownMenuItem(text = { Text("Server settings") }, onClick = { menuOpen = false; onServerSettings() })
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; onSignOut() })
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                when (trackingState) {
                    TrackingState.PERMISSION_REQUIRED -> TextButton(onClick = onRequestLocation) { Text("Allow location") }
                    TrackingState.LOCATION_DISABLED -> StatusPill("Location off", PillTone.Error)
                    TrackingState.SEARCHING -> StatusPill("GPS searching", PillTone.Amber, Modifier.testTag("gps_chip"))
                    TrackingState.TRACKING -> StatusPill("GPS live", PillTone.Good, Modifier.testTag("gps_chip"))
                }
                fix?.let {
                    StatusPill(
                        "${Formatters.accuracy(it.accuracyMeters)} · ${it.quality.label()}",
                        it.quality.tone(),
                        Modifier.testTag("accuracy_chip"),
                        description = "GPS accuracy ${Formatters.accuracy(it.accuracyMeters)}, ${it.quality.label()}",
                    )
                }
                StatusPill(sync.label, sync.tone, Modifier.testTag("sync_chip"), description = "Sync status: ${sync.label}")
            }
        }
    }
}
