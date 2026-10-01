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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geotree.app.R
import com.geotree.app.appContainer
import com.geotree.app.core.design.CoordinateTextStyle
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoTreeLogo
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.design.label
import com.geotree.app.core.design.tone
import com.geotree.app.core.location.GpsFix
import com.geotree.app.feature.locator.map.ActiveBasemap
import com.geotree.app.feature.locator.map.Basemap
import com.geotree.app.feature.locator.map.MapCoverage
import com.geotree.app.feature.locator.map.TreeMap
import com.geotree.app.feature.navigation.ArrivalDialog
import com.geotree.app.feature.navigation.FieldNavigationController
import com.geotree.app.feature.navigation.FieldNavigationState
import com.geotree.app.feature.navigation.NavigationPanel
import com.geotree.app.geoViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * The Map tab. [focusTreeId] / [navigateTreeId] are one-shot requests handed over from
 * Tag Tree, Tree Detail or the Dashboard; they are consumed once Room has emitted the tree.
 */
@Composable
fun LocatorScreen(
    focusTreeId: StateFlow<String?>,
    savedTreeCode: StateFlow<String?>,
    navigateTreeId: StateFlow<String?>,
    onFocusConsumed: () -> Unit,
    onNavigateConsumed: () -> Unit,
    onTagTree: () -> Unit,
    onOpenTree: (String) -> Unit,
) {
    val viewModel = geoViewModel { c, _ ->
        LocatorViewModel(
            repository = c.treeRepository,
            locationSource = c.locationClient,
            syncTrigger = c.syncScheduler,
            syncTracker = c.syncStatusTracker,
            navigation = FieldNavigationController(c.routeProvider, c.navigationConfig),
            basemapSource = c.basemap(),
        )
    }
    val trees by viewModel.trees.collectAsStateWithLifecycle()
    val sync by viewModel.syncIndicator.collectAsStateWithLifecycle()
    val fix by viewModel.currentFix.collectAsStateWithLifecycle()
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedTreeId.collectAsStateWithLifecycle()
    val selectedDistance by viewModel.selectedTreeDistanceMeters.collectAsStateWithLifecycle()
    val cameraCommand by viewModel.cameraCommand.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val navigation by viewModel.navigationState.collectAsStateWithLifecycle()
    val basemap by viewModel.basemap.collectAsStateWithLifecycle()
    val coverage by viewModel.coverage.collectAsStateWithLifecycle()
    val focusId by focusTreeId.collectAsStateWithLifecycle()
    val savedCode by savedTreeCode.collectAsStateWithLifecycle()
    val navigateId by navigateTreeId.collectAsStateWithLifecycle()
    val config = LocalContext.current.appContainer.navigationConfig

    val snackbar = remember { SnackbarHostState() }
    var showList by remember { mutableStateOf(false) }
    var bottomOverlayPx by remember { mutableIntStateOf(0) }
    var panelPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val initialCamera = remember { viewModel.initialCamera() }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.startTracking()
    }
    val requestLocation = {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // GPS runs only while the Map is visible; leaving the tab or backgrounding the app releases it.
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
    // While guidance shows, re-check every 2 s so a measured speed disappears when GPS fixes stop.
    val navigating = navigation != null
    LaunchedEffect(navigating) {
        while (navigating) {
            delay(2_000)
            viewModel.onNavigationTick()
        }
    }
    LaunchedEffect(navigateId, trees) {
        val id = navigateId ?: return@LaunchedEffect
        if (trees.none { it.id == id }) return@LaunchedEffect
        onNavigateConsumed()
        viewModel.startNavigation(id)
    }

    Box(Modifier.fillMaxSize()) {
        TreeMap(
            styleJson = basemap.styleJson,
            trees = trees,
            selectedTreeId = selectedId,
            destinationTreeId = navigation?.destination?.treeId,
            route = navigation?.route?.geometry,
            currentFix = fix,
            cameraCommand = cameraCommand,
            initialCamera = initialCamera,
            minZoom = if (basemap.hasOfflineTiles) MapZoom.OFFLINE_MIN else 0.0,
            topInset = 150.dp,
            // Ornaments (logo, attribution) sit above the guidance panel; the FABs are on the right.
            bottomInset = with(density) { (if (navigation != null) panelPx else 0).toDp() } + 16.dp,
            fitBottomInset = with(density) { bottomOverlayPx.toDp() },
            onTreeClick = viewModel::selectTree,
            onMapClick = { viewModel.selectTree(null) },
            onUserGesture = viewModel::onUserGesture,
            onCameraIdle = viewModel::onCameraIdle,
            modifier = Modifier.fillMaxSize(),
        )

        LocatorTopBar(
            trackingState = tracking,
            fix = fix,
            sync = sync,
            basemap = basemap,
            navigation = navigation,
            onShowList = { showList = true },
            onSyncNow = viewModel::syncNow,
            onRequestLocation = requestLocation,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (coverage == MapCoverage.OUTSIDE) {
            // Left-aligned so the compass at top-right stays visible.
            CoverageNotice(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, end = 84.dp, top = 112.dp))
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .onSizeChanged { bottomOverlayPx = it.height },
        ) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    if (trees.isEmpty() && navigation == null) {
                        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                            Text(
                                "No trees tagged yet.\nTap Tag Tree to register one.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp).testTag("empty_state"),
                            )
                        }
                    }
                }
                MapControls(
                    following = following,
                    navigating = navigation != null,
                    onToggleFollow = viewModel::toggleFollowLocation,
                    onMyLocation = viewModel::centerOnCurrentLocation,
                    onTagTree = onTagTree,
                )
            }
            navigation?.let {
                NavigationPanel(it, onStop = viewModel::stopNavigation, config = config, modifier = Modifier.onSizeChanged { size -> panelPx = size.height })
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 160.dp))
    }

    trees.firstOrNull { it.id == selectedId }?.let { tree ->
        TreeSummarySheet(
            tree = tree,
            distanceMeters = selectedDistance,
            isDestination = navigation?.destination?.treeId == tree.id,
            onDismiss = { viewModel.selectTree(null) },
            onViewDetails = { onOpenTree(tree.id) },
            onNavigate = {
                if (tracking == TrackingState.PERMISSION_REQUIRED) requestLocation()
                viewModel.startNavigation(tree.id)
            },
        )
    }
    navigation?.takeIf { it.arrivalAlertPending }?.let { nav ->
        ArrivalDialog(
            treeCode = nav.destination.treeCode,
            radiusMeters = config.arrivalRadiusMeters,
            onStop = { viewModel.acknowledgeArrival(); viewModel.stopNavigation() },
            onContinue = viewModel::acknowledgeArrival,
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
}

@Composable
private fun MapControls(
    following: Boolean,
    navigating: Boolean,
    onToggleFollow: () -> Unit,
    onMyLocation: () -> Unit,
    onTagTree: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallFloatingActionButton(
            onClick = onToggleFollow,
            containerColor = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (following) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("follow_location").semantics {
                stateDescription = if (following) "Following your location" else "Not following"
            },
        ) { Icon(painterResource(R.drawable.ic_navigation), contentDescription = if (following) "Stop following location" else "Follow my location") }
        SmallFloatingActionButton(
            onClick = onMyLocation,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("my_location"),
        ) { Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Center on my location") }
        if (navigating) {
            FloatingActionButton(
                onClick = onTagTree,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("tag_tree_fab"),
            ) { Icon(Icons.Default.Add, contentDescription = "Tag Tree") }
        } else {
            ExtendedFloatingActionButton(
                onClick = onTagTree,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Tag Tree", fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("tag_tree_fab"),
            )
        }
    }
}

@Composable
private fun LocatorTopBar(
    trackingState: TrackingState,
    fix: GpsFix?,
    sync: SyncIndicator,
    basemap: Basemap,
    navigation: FieldNavigationState?,
    onShowList: () -> Unit,
    onSyncNow: () -> Unit,
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
                    Text("Field Locator", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        fix?.let { Formatters.coordinatePair(it.latitude, it.longitude) } ?: "Waiting for GPS",
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
                navigation?.let {
                    StatusPill("Guiding to ${it.destination.treeCode}", PillTone.Neutral, Modifier.testTag("nav_chip"))
                }
                StatusPill(sync.label, sync.tone, Modifier.testTag("sync_chip"), description = "Sync status: ${sync.label}")
                when (basemap.active) {
                    ActiveBasemap.OFFLINE_PACKAGE -> StatusPill("Offline map", PillTone.Good, Modifier.testTag("basemap_chip"), description = basemap.status)
                    ActiveBasemap.ONLINE_DEV -> StatusPill("Online map", PillTone.Neutral, Modifier.testTag("basemap_chip"), description = basemap.status)
                    ActiveBasemap.BACKGROUND_ONLY -> StatusPill("No offline map", PillTone.Warning, Modifier.testTag("basemap_chip"), description = basemap.status)
                }
            }
        }
    }
}

/** Shown when GPS puts the user outside the packaged map. GPS, records and guidance are unaffected. */
@Composable
private fun CoverageNotice(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 2.dp,
        modifier = modifier.testTag("coverage_notice"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Outside offline field map", style = MaterialTheme.typography.labelLarge)
            Text(
                "GPS, saved trees and field guidance still work here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
