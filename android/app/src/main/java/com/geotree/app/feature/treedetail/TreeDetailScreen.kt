package com.geotree.app.feature.treedetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.geotree.app.R
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.design.TreeImage
import com.geotree.app.core.design.label
import com.geotree.app.core.design.tone
import com.geotree.app.core.sync.SyncScheduler
import com.geotree.app.data.repository.RenameResult
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.tagtree.CoordinateRow
import com.geotree.app.feature.tagtree.UppercaseTransformation
import com.geotree.app.geoViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TreeLoad {
    data object Loading : TreeLoad
    data object Missing : TreeLoad
    data class Loaded(val tree: TreeEntity) : TreeLoad
}

class TreeDetailViewModel(
    private val repository: TreeRepository,
    private val syncScheduler: SyncScheduler,
    private val treeId: String,
) : ViewModel() {
    val tree: StateFlow<TreeLoad> = repository.observeTree(treeId)
        .map { if (it == null) TreeLoad.Missing else TreeLoad.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TreeLoad.Loading)

    private val _renameError = MutableStateFlow<String?>(null)
    val renameError: StateFlow<String?> = _renameError.asStateFlow()

    fun retrySync() = syncScheduler.requestSync(replace = true)

    fun rename(code: String, onDone: () -> Unit) {
        viewModelScope.launch {
            when (repository.renameTreeCode(treeId, code)) {
                RenameResult.Renamed -> {
                    _renameError.value = null
                    syncScheduler.requestSync(replace = true)
                    onDone()
                }
                RenameResult.Duplicate -> _renameError.value = "That Tree Code is already registered on this device."
                RenameResult.Invalid -> _renameError.value = "Use letters, numbers and dashes, e.g. GEO-TAM-003."
                RenameResult.NotFound -> _renameError.value = "Tree no longer exists."
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeDetailScreen(treeId: String, onBack: () -> Unit, onCenterOnMap: (String) -> Unit, onNavigate: (String) -> Unit) {
    val viewModel = geoViewModel(key = "tree_$treeId") { c, _ -> TreeDetailViewModel(c.treeRepository, c.syncScheduler, treeId) }
    val load by viewModel.tree.collectAsStateWithLifecycle()
    val renameError by viewModel.renameError.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((load as? TreeLoad.Loaded)?.tree?.treeCode ?: "Tree") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val state = load) {
            TreeLoad.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            TreeLoad.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("This tree is not on this device.") }
            is TreeLoad.Loaded -> TreeDetailContent(
                tree = state.tree,
                onCenterOnMap = { onCenterOnMap(state.tree.id) },
                onNavigate = { onNavigate(state.tree.id) },
                onRetrySync = viewModel::retrySync,
                onRename = { renaming = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

    val loaded = load as? TreeLoad.Loaded
    if (renaming && loaded != null) {
        var code by remember { mutableStateOf(loaded.tree.treeCode) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Change Tree Code") },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    visualTransformation = UppercaseTransformation,
                    label = { Text("Tree Code") },
                    singleLine = true,
                    isError = renameError != null,
                    supportingText = renameError?.let { { Text(it) } },
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.rename(code) { renaming = false } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TreeDetailContent(
    tree: TreeEntity,
    onCenterOnMap: () -> Unit,
    onNavigate: () -> Unit,
    onRetrySync: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        TreeImage(tree, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tree.treeCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).testTag("detail_tree_code"))
            StatusPill(tree.syncStatus.label(), tree.syncStatus.tone(), Modifier.testTag("detail_sync_status"))
        }
        tree.lastSyncError?.let { error ->
            Surface(
                color = if (tree.syncStatus == SyncStatus.FAILED) GeoColors.ErrorLight else GeoColors.GpsAmberLight,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("detail_sync_error"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetrySync) { Text("Retry sync") }
                        if (error.contains("Tree Code", ignoreCase = true)) OutlinedButton(onClick = onRename) { Text("Change code") }
                    }
                }
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Geotag", style = MaterialTheme.typography.titleMedium)
                CoordinateRow("Latitude", Formatters.latitude(tree.latitude), tag = "detail_latitude")
                CoordinateRow("Longitude", Formatters.longitude(tree.longitude), tag = "detail_longitude")
                CoordinateRow("Accuracy", Formatters.accuracy(tree.accuracyMeters), tag = "detail_accuracy")
                tree.altitudeMeters?.let { CoordinateRow("Altitude", Formatters.altitude(it), tag = "detail_altitude") }
                CoordinateRow("Captured at", Formatters.dateTime(tree.locationCapturedAt), tag = "detail_time", monospace = false)
            }
        }

        val details = listOfNotNull(
            tree.age?.let { "Age" to "$it years" },
            tree.tasteCategory?.let { "Taste" to it },
            tree.yearlyYield?.let { "Yearly yield" to "$it kg" },
            tree.fruitQuality?.let { "Fruit quality" to it },
        )
        if (details.isNotEmpty() || tree.notes != null) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tree details", style = MaterialTheme.typography.titleMedium)
                    details.forEach { (label, value) -> CoordinateRow(label, value, tag = "detail_$label", monospace = false) }
                    tree.notes?.let { notes ->
                        // Free text can be long, so it gets its own block instead of a label/value row.
                        Text("Notes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("detail_notes"))
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCenterOnMap, modifier = Modifier.weight(1f).testTag("center_on_map")) {
                Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Center on Map")
            }
            Button(onClick = onNavigate, modifier = Modifier.weight(1f).testTag("detail_navigate")) {
                Icon(painterResource(R.drawable.ic_near_me), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Navigate to Tree")
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Leaf Assessment", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Coming in the next milestone. Leaf-disease assessment is not part of this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "Record ID ${tree.id}" + (tree.serverVersion?.let { " · server v$it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("detail_record_id"),
        )
    }
}
