package com.geotree.app.feature.locator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.design.TreeImage
import com.geotree.app.core.design.label
import com.geotree.app.core.design.tone
import com.geotree.app.feature.navigation.NavigationFormat
import com.geotree.app.feature.tagtree.CoordinateRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeSummarySheet(
    tree: TreeEntity,
    distanceMeters: Double?,
    isDestination: Boolean,
    onDismiss: () -> Unit,
    onViewDetails: () -> Unit,
    onNavigate: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("tree_sheet"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TreeImage(tree, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tree.treeCode, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("sheet_tree_code"))
                    Spacer(Modifier.height(4.dp))
                    StatusPill(tree.syncStatus.label(), tree.syncStatus.tone(), Modifier.testTag("sheet_sync_status"))
                }
            }
            CoordinateRow("Latitude", Formatters.latitude(tree.latitude), tag = "sheet_latitude")
            CoordinateRow("Longitude", Formatters.longitude(tree.longitude), tag = "sheet_longitude")
            CoordinateRow("Accuracy", Formatters.accuracy(tree.accuracyMeters), tag = "sheet_accuracy")
            CoordinateRow("Captured", Formatters.dateTime(tree.locationCapturedAt), tag = "sheet_time", monospace = false)
            CoordinateRow(
                "Distance",
                distanceMeters?.let { "${NavigationFormat.distance(it)} from you" } ?: "Waiting for GPS",
                tag = "sheet_distance",
                monospace = false,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onViewDetails, modifier = Modifier.weight(1f).testTag("view_details")) { Text("View Details") }
                Button(onClick = onNavigate, enabled = !isDestination, modifier = Modifier.weight(1f).testTag("navigate_to_tree")) {
                    Text(if (isDestination) "Navigating" else "Navigate to Tree")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeListSheet(trees: List<TreeEntity>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Tagged trees (${trees.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (trees.isEmpty()) {
            Text("No trees yet.", modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            items(trees, key = { it.id }) { tree ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Show ${tree.treeCode} on map") { onSelect(tree.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .testTag("tree_row_${tree.treeCode}"),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tree.treeCode, style = MaterialTheme.typography.titleSmall)
                        Text(
                            Formatters.coordinatePair(tree.latitude, tree.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(tree.syncStatus.label(), tree.syncStatus.tone())
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
