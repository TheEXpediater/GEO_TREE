package com.geotree.app.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geotree.app.R
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.GeoTreeLogo
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.design.TreeImage
import com.geotree.app.core.design.label
import com.geotree.app.core.design.tone
import com.geotree.app.geoViewModel

@Composable
fun DashboardScreen(onTagTree: () -> Unit, onOpenMap: () -> Unit, onOpenTree: (String) -> Unit) {
    val viewModel = geoViewModel { c, _ ->
        DashboardViewModel(
            repository = c.treeRepository,
            locationSource = c.locationClient,
            syncTrigger = c.syncScheduler,
            syncTracker = c.syncStatusTracker,
            backendStatus = c.backendConnection.status,
            backendUrl = c.backendConfig.baseUrl,
            lastSyncAt = c.syncPreferences.lastSuccessfulSyncAt,
            deviceOnline = c.networkMonitor.isConnected,
            offlineMap = c.offlineMapInstaller.state,
            refreshBackend = { c.backendConnection.refreshStatus() },
            refreshLastKnownFix = { c.locationClient.refreshLastKnown() },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().testTag("dashboard"),
    ) {
        item { DashboardHeader(state) }
        item { StatisticsGrid(state) }
        pendingMessage(state.waitingToSync)?.let { message ->
            item { PendingBanner(message, state.syncRunning, onSyncNow = viewModel::syncNow) }
        }
        item { QuickActions(onTagTree = onTagTree, onOpenMap = onOpenMap, onSyncNow = viewModel::syncNow, syncRunning = state.syncRunning) }
        item { FieldStatusCard(state) }
        item { SectionTitle("Recent trees") }
        if (state.recentTrees.isEmpty()) {
            item {
                Text(
                    "No trees registered on this device yet. Tap Tag New Tree to add the first one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("dashboard_empty"),
                )
            }
        } else {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    state.recentTrees.forEachIndexed { index, tree ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        RecentTreeRow(tree, onClick = { onOpenTree(tree.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(state: DashboardUiState) {
    Box(Modifier.fillMaxWidth()) {
        SurveyContours(Modifier.matchParentSize())
        Column(Modifier.statusBarsPadding().padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GeoTreeLogo(size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("GEO Tree", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("FIELD SURVEY DASHBOARD", style = MaterialTheme.typography.labelMedium, color = GeoColors.Clay, letterSpacing = 2.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                val gps = state.gps.display()
                val network = networkDisplay(state.deviceOnline)
                val backend = state.backend.display()
                StatusPill(gps.text, gps.tone, Modifier.testTag("dash_gps_chip"))
                StatusPill(network.text, network.tone, Modifier.testTag("dash_network_chip"), description = "Device network ${network.text}")
                StatusPill("Backend ${backend.text}", backend.tone, Modifier.testTag("dash_backend_chip"))
                StatusPill(state.sync.label, state.sync.tone, Modifier.testTag("dash_sync_chip"), description = "Sync status: ${state.sync.label}")
                val map = offlineMapStatusLabel(state.offlineMap)
                StatusPill("Offline map ${map.text.lowercase()}", map.tone, Modifier.testTag("dash_map_chip"))
            }
        }
    }
}

@Composable
private fun StatisticsGrid(state: DashboardUiState) {
    val stats = listOf(
        Triple("TOTAL TREES", state.counts.total, MaterialTheme.colorScheme.primary),
        Triple("SYNCED", state.counts.synced, GeoColors.Success),
        Triple("PENDING", state.counts.pending + state.counts.syncing, GeoColors.GpsAmber),
        Triple("FAILED", state.counts.failed, GeoColors.Error),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value, accent) -> StatCard(label, value, accent, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value" },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Spacer(Modifier.width(4.dp).height(40.dp).background(accent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("stat_${label.lowercase().replace(' ', '_')}"),
                )
            }
        }
    }
}

@Composable
private fun PendingBanner(message: String, syncRunning: Boolean, onSyncNow: () -> Unit) {
    Surface(color = GeoColors.GpsAmberLight, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("pending_banner")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.titleSmall, color = Color(0xFF6B4300))
                Text(
                    "Saved on this device. Field work can continue offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B4300),
                )
            }
            OutlinedButton(onClick = onSyncNow, enabled = !syncRunning) { Text(if (syncRunning) "Syncing…" else "Sync Now") }
        }
    }
}

@Composable
private fun QuickActions(onTagTree: () -> Unit, onOpenMap: () -> Unit, onSyncNow: () -> Unit, syncRunning: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onTagTree,
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth().testTag("dash_tag_tree"),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Tag New Tree", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onOpenMap, modifier = Modifier.weight(1f).testTag("dash_open_map")) {
                Icon(painterResource(R.drawable.ic_map), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Map")
            }
            OutlinedButton(
                onClick = onSyncNow,
                enabled = !syncRunning,
                colors = ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.weight(1f).testTag("dash_sync_now"),
            ) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (syncRunning) "Syncing…" else "Sync Now")
            }
        }
    }
}

@Composable
private fun FieldStatusCard(state: DashboardUiState) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("field_status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Field status", style = MaterialTheme.typography.titleMedium)
            val gps = state.gps.display()
            StatusRow("GPS", gps.text, gps.tone, tag = "status_gps")
            val fix = state.lastFix
            StatusRow(
                "Last known accuracy",
                fix?.let { "${Formatters.accuracy(it.accuracyMeters)} · ${it.quality.label()} · ${Formatters.relative(it.capturedAt)}" } ?: "No fix yet",
                fix?.quality?.tone() ?: PillTone.Neutral,
                tag = "status_accuracy",
            )
            val backend = state.backend.display()
            StatusRow("Backend", backend.text, backend.tone, tag = "status_backend")
            Text(
                state.backendUrl + (state.backend.detail?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StatusRow(
                "Last synchronization",
                state.lastSyncAt?.let { Formatters.relative(it) } ?: "Never on this device",
                if (state.lastSyncAt != null) PillTone.Good else PillTone.Neutral,
                tag = "status_last_sync",
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, tone: PillTone, tag: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        StatusPill(value, tone, Modifier.testTag(tag))
    }
}

@Composable
private fun RecentTreeRow(tree: TreeEntity, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${tree.treeCode}", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("recent_${tree.treeCode}"),
    ) {
        TreeImage(tree, Modifier.size(52.dp).clip(MaterialTheme.shapes.medium))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tree.treeCode, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                Formatters.shortDateTime(tree.locationCapturedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(tree.syncStatus.label(), tree.syncStatus.tone())
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

/** Faint survey contour arcs behind the header: a restrained cartographic cue. */
@Composable
private fun SurveyContours(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    Canvas(modifier.clipToBounds()) {
        val origin = Offset(size.width * 0.95f, size.height * 0.1f)
        for (i in 1..6) drawCircle(color, radius = i * 38.dp.toPx(), center = origin, style = Stroke(width = 1.5.dp.toPx()))
    }
}
