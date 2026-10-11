package com.geotree.app.feature.leafassessment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geotree.app.R
import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.StatusPill

/**
 * Tree Detail: the current leaf status (a summary derived from the newest scan with a result)
 * and the Assess Leaf action. Visually separate from [AssessmentHistoryCard]: a new scan adds to
 * the history, it never erases it.
 */
@Composable
fun LeafStatusCard(
    latest: LeafAssessmentEntity?,
    assessmentCount: Int,
    availability: AiAvailability,
    onAssess: () -> Unit,
    onOpenLatest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth().testTag("leaf_status_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CURRENT LEAF STATUS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val (label, tone) = availabilityPill(availability)
                StatusPill(label, tone, Modifier.testTag("leaf_ai_status"))
            }
            if (latest == null) {
                Text("No leaf assessment yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("leaf_status_empty"))
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClickLabel = "Open latest scan") { onOpenLatest(latest.id) }
                        .testTag("leaf_status_latest"),
                ) {
                    Text(conditionText(latest), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    latest.confidence?.let { Text(confidenceText(it), style = MaterialTheme.typography.titleMedium) }
                    Text(
                        "Latest scan · ${Formatters.shortDateTime(latest.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(modelText(latest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (availability) {
                is AiAvailability.NotReady -> Text(availability.message, style = MaterialTheme.typography.bodySmall)
                is AiAvailability.Unavailable -> Text(
                    "New scans need the local AI backend. History, tree records, the map and navigation keep working offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
            FilledTonalButton(onClick = onAssess, modifier = Modifier.fillMaxWidth().testTag("assess_leaf")) {
                Icon(painterResource(R.drawable.ic_leaf), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Assess Leaf")
            }
            Text(
                assessmentCountText(assessmentCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("leaf_assessment_count"),
            )
        }
    }
}

/** Every scan of this tree, newest first. Rows open the read-only assessment detail. */
@Composable
fun AssessmentHistoryCard(history: List<LeafAssessmentEntity>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth().testTag("assessment_history")) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "ASSESSMENT HISTORY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (history.isEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("assessment_history_empty")) {
                    Text("No assessments yet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Run a leaf scan to begin monitoring this tree's condition over time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            history.forEachIndexed { index, record ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                HistoryRow(record, index, onOpen)
            }
        }
    }
}

@Composable
private fun HistoryRow(record: LeafAssessmentEntity, index: Int, onOpen: (String) -> Unit) {
    val (statusText, _) = statusLabel(record.status)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open assessment") { onOpen(record.id) }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .testTag("history_item_$index"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                historyTitle(record),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (record.status.hasResult) FontWeight.SemiBold else FontWeight.Normal,
                color = if (record.status.hasResult) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Formatters.shortDateTime(record.createdAt)} · $statusText · ${syncLabel(record)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (record.status == AssessmentStatus.FAILED) GeoColors.Clay else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
