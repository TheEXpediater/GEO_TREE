package com.geotree.app.feature.classifiers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geotree.app.R
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.StatusPill
import com.geotree.app.data.remote.ClassifierClassDto
import com.geotree.app.data.remote.ModelStatusDto
import com.geotree.app.data.remote.parseIsoMillis
import com.geotree.app.geoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassifiersScreen(onBack: () -> Unit, onOpenClass: (String) -> Unit) {
    val viewModel = geoViewModel { c, _ -> ClassifiersViewModel(c.classifierRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ClassifierClassDto?>(null) }
    var deleting by remember { mutableStateOf<ClassifierClassDto?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leaf Classifiers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val problem = state.problem
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            problem != null && state.classes.isEmpty() -> ProblemPanel(problem.message, onRetry = viewModel::refresh, modifier = Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("classifiers"),
            ) {
                item { ModelStatusCard(state, onRetrain = viewModel::retrain) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Classes", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Labels of one classifier · at least ${state.minImagesPerClass} images each",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { adding = true }, enabled = !state.working, modifier = Modifier.testTag("add_classifier")) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Add Classifier")
                        }
                    }
                }
                if (state.classes.isEmpty()) {
                    item { Text("No classes yet. Add one, e.g. Healthy.", style = MaterialTheme.typography.bodyMedium) }
                }
                items(state.classes, key = { it.id }) { item ->
                    ClassRow(
                        item = item,
                        minImages = state.minImagesPerClass,
                        onManageImages = { onOpenClass(item.id) },
                        onRename = { renaming = item },
                        onDelete = { deleting = item },
                    )
                }
            }
        }
    }

    if (adding) {
        ClassNameDialog(
            title = "Add Classifier",
            initial = "",
            error = state.dialogError,
            working = state.working,
            onConfirm = { viewModel.addClass(it) { adding = false } },
            onDismiss = { adding = false; viewModel.dialogClosed() },
            hint = "A label of the leaf classifier (e.g. Leaf Spot), not a separate model.",
        )
    }
    renaming?.let { item ->
        ClassNameDialog(
            title = "Rename Classifier",
            currentName = item.name,
            initial = item.name,
            error = state.dialogError,
            working = state.working,
            onConfirm = { viewModel.renameClass(item.id, it) { renaming = null } },
            onDismiss = { renaming = null; viewModel.dialogClosed() },
            hint = "Its images, cached features and model data are kept.",
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete classifier ${item.name}?") },
            text = {
                Text(
                    "This permanently removes the classifier, its ${imageCountText(item.imageCount)} and their cached DINOv2 features from the server. " +
                        if (item.inActiveModel) "The active model uses this classifier, so leaf assessment stops until the model is rebuilt." else "The model must be rebuilt after the classifier set changes.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteClass(item); deleting = null }, modifier = Modifier.testTag("confirm_delete_class")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelStatusCard(state: ClassifiersUiState, onRetrain: () -> Unit) {
    val model = state.model
    val active = model?.activeModel
    OutlinedCard(Modifier.fillMaxWidth().testTag("model_status_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tamarind Leaf Classifier", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${model?.backbone?.displayName ?: "DINOv2 ViT-S/14"} · frozen backbone, trained head",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val label = modelStatusLabel(model?.status, model?.stale == true)
                StatusPill(label.text, label.tone, Modifier.testTag("model_status"), description = "Model status ${label.text}")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Metric("Active version", active?.let { "v${it.modelVersion}" } ?: "None")
            Metric("Dataset images", state.datasetImageCount.toString())
            Metric("Last trained", active?.trainedAt?.let { runCatching { Formatters.relative(it.parseIsoMillis()) }.getOrNull() } ?: "Never")
            if (active != null) {
                Metric("Validation (${active.validationCount} images)", "Accuracy ${percent(active.metrics.accuracy)} · F1 ${percent(active.metrics.macroF1)}")
                active.metrics.perClass.forEach { row ->
                    Text(
                        "${row.className}: precision ${percent(row.precision)}, recall ${percent(row.recall)}, F1 ${percent(row.f1)} (n=${row.support})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            model?.let { JobAndReadiness(it) }
            Button(
                onClick = onRetrain,
                enabled = model?.readiness?.canTrain == true && model.status !in setOf("QUEUED", "TRAINING") && !state.working,
                modifier = Modifier.fillMaxWidth().testTag("retrain"),
            ) { Text("Train / Rebuild Model") }
            Text(
                "Uploads queue an automatic rebuild of the small classifier head from cached features. The DINOv2 backbone itself is never retrained.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JobAndReadiness(model: ModelStatusDto) {
    val job = model.currentJob
    when (job?.status) {
        "QUEUED" -> Notice(
            "Training queued" + (job.coalescedChanges.takeIf { it > 1 }?.let { " · $it dataset changes combined" } ?: "") +
                ". It starts a few seconds after the last change.",
            GeoColors.GpsAmberLight,
        )
        "TRAINING" -> Notice("Training the classifier head and validating it…", GeoColors.GpsAmberLight)
    }
    model.lastError?.let { Notice("Last training failed: $it" + if (model.activeModel != null) " The previous model is still active." else "", GeoColors.ErrorLight) }
    if (model.readiness.problems.isNotEmpty()) {
        Notice("Not ready to train: " + model.readiness.problems.joinToString(" "), GeoColors.ClayLight)
    }
    model.backbone.error?.let { Notice("Backbone: $it", GeoColors.ErrorLight) }
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = GeoColors.Charcoal, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ClassRow(item: ClassifierClassDto, minImages: Int, onManageImages: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val label = classStatusLabel(item, minImages)
    OutlinedCard(Modifier.fillMaxWidth().testTag("class_${item.slug}")) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 4.dp)) {
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_leaf), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(imageCountText(item.imageCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(label.text, label.tone, description = "${item.name} ${label.text}")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("class_menu_${item.slug}")) {
                    Icon(Icons.Default.MoreVert, contentDescription = "${item.name} actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename Classifier") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                        modifier = Modifier.testTag("menu_rename"),
                    )
                    DropdownMenuItem(
                        text = { Text("Manage Images") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null) },
                        onClick = { menuOpen = false; onManageImages() },
                        modifier = Modifier.testTag("menu_manage_images"),
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete Classifier", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                        modifier = Modifier.testTag("menu_delete"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassNameDialog(
    title: String,
    initial: String,
    error: String?,
    working: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String,
    currentName: String? = null,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                currentName?.let {
                    Text("Current name: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Class name") },
                    placeholder = { Text("e.g. Leaf Spot") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: hint) },
                    modifier = Modifier.testTag("class_name_field"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank() && !working) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProblemPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize().padding(24.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("ai_problem"))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}
