package com.geotree.app.feature.leafassessment

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.geotree.app.R
import com.geotree.app.appContainer
import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.StatusPill
import com.geotree.app.geoViewModel
import java.io.File
import kotlinx.coroutines.flow.map

/**
 * One scan. As the result page ([asResult]) it follows the scan from PROCESSING to its outcome
 * and offers Back to Tree / View History; from the history list it is a read-only record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentDetailScreen(
    assessmentId: String,
    asResult: Boolean,
    onBack: () -> Unit,
    onViewHistory: () -> Unit,
    onOpenAssessment: (newAssessmentId: String) -> Unit,
) {
    val viewModel = geoViewModel(key = "assessment_$assessmentId") { c, _ ->
        AssessmentDetailViewModel(
            assessmentId = assessmentId,
            repository = c.leafAssessmentRepository,
            treeCodeOf = { treeId -> c.treeRepository.observeTree(treeId).map { it?.treeCode } },
        )
    }
    val load by viewModel.load.collectAsStateWithLifecycle()
    val newAssessmentId by viewModel.newAssessmentId.collectAsStateWithLifecycle()
    val reassessing by viewModel.reassessing.collectAsStateWithLifecycle()
    LaunchedEffect(newAssessmentId) {
        newAssessmentId?.let {
            viewModel.navigationHandled()
            onOpenAssessment(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (asResult) "Leaf Assessment" else "Assessment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (asResult) "Back to tree" else "Back")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val state = load) {
            AssessmentLoad.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            AssessmentLoad.Missing -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("This assessment is not on this device.", style = MaterialTheme.typography.bodyLarge)
            }
            is AssessmentLoad.Loaded -> Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .testTag(if (asResult) "assessment_result_page" else "assessment_detail_page"),
            ) {
                AssessmentPhoto(state.record)
                OutcomeCard(state.record)
                DetailsCard(state.record, state.treeCode)
                if (asResult) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onViewHistory, modifier = Modifier.weight(1f).testTag("view_history")) { Text("View History") }
                        Button(onClick = onBack, modifier = Modifier.weight(1f).testTag("back_to_tree")) { Text("Back to Tree") }
                    }
                }
                if (canReassess(state.record)) {
                    OutlinedButton(
                        onClick = viewModel::reassess,
                        enabled = !reassessing,
                        modifier = Modifier.fillMaxWidth().testTag("reassess_photo"),
                    ) {
                        Icon(painterResource(R.drawable.ic_leaf), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (state.record.status == AssessmentStatus.FAILED) "Retry with this photo (new scan)" else "Assess this photo again (new scan)")
                    }
                }
            }
        }
    }
}

@Composable
private fun AssessmentPhoto(record: LeafAssessmentEntity) {
    val context = LocalContext.current
    val local = record.imageLocalPath?.let(::File)?.takeIf { it.isFile }
    val model by produceState<Any?>(local, record.id, record.remoteImagePath) {
        value = local ?: record.remoteImagePath?.let { context.appContainer.apiProvider.imageUrl(it) }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (model != null) {
            SubcomposeAsyncImage(model = model, contentDescription = "Assessed leaf photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("Photo not available on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OutcomeCard(record: LeafAssessmentEntity) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("assessment_outcome")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val (label, tone) = statusLabel(record.status)
            StatusPill(label, tone, Modifier.testTag("assessment_status"))
            when (record.status) {
                AssessmentStatus.PENDING, AssessmentStatus.PROCESSING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Analyzing with DINOv2 on the AI server…", style = MaterialTheme.typography.bodyLarge)
                }
                AssessmentStatus.COMPLETED -> {
                    Text(record.className ?: "Unknown", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("result_condition"))
                    record.confidence?.let { Text("${confidenceText(it)} confidence", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("result_confidence")) }
                }
                AssessmentStatus.UNCERTAIN -> {
                    Text("Uncertain", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("result_condition"))
                    Text(
                        "Confidence below threshold · closest: ${record.className ?: "Unknown"}" + (record.confidence?.let { " (${confidenceText(it)})" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("result_confidence"),
                    )
                    Notice("Retake the photo closer and in even light, or treat this result as a hint only.", GeoColors.GpsAmberLight)
                }
                AssessmentStatus.FAILED -> {
                    Text("Assessment unavailable", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("result_condition"))
                    Text(record.errorMessage ?: "No result was produced.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("result_error"))
                    if (record.imageLocalPath != null) {
                        Text("This attempt and its photo are kept in the tree's history.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (record.status.hasResult) {
                Text(
                    "Result from the classifier trained on the GEO Tree tamarind dataset. Confirm important findings in the field.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailsCard(record: LeafAssessmentEntity, treeCode: String?) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("assessment_details")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailRow("Tree", treeCode ?: "Unknown tree")
            DetailRow("Model", modelText(record), tag = "result_model")
            DetailRow("Scanned", Formatters.dateTime(record.createdAt))
            record.completedAt?.let { DetailRow(if (record.status == AssessmentStatus.FAILED) "Ended" else "Completed", Formatters.dateTime(it)) }
            DetailRow("Status", statusLabel(record.status).first)
            DetailRow("Sync", syncLabel(record), tag = "result_sync")
            record.lastSyncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            record.errorCode?.let { DetailRow("Error code", it) }
            HorizontalDivider()
            Text(
                "Assessment ID ${record.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("assessment_id"),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, tag: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.65f).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        )
    }
}
