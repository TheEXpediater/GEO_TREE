package com.geotree.app.feature.classifiers

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.geotree.app.R
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.StatusPill
import com.geotree.app.data.remote.DatasetImageDto
import com.geotree.app.data.remote.parseIsoMillis
import com.geotree.app.geoViewModel

private const val MAX_PICK = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassImagesScreen(classId: String, onBack: () -> Unit) {
    val viewModel = geoViewModel(key = "class_images_$classId") { c, _ ->
        ClassImagesViewModel(classId, c.classifierRepository, c.localImageReader)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<DatasetImageDto?>(null) }
    var confirmDeleteOne by remember { mutableStateOf<DatasetImageDto?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        viewModel.upload(uris.map { it.toString() })
    }

    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.selecting) "${state.selected.size} selected" else state.className.ifEmpty { "Images" }) },
                navigationIcon = {
                    if (state.selecting) IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, contentDescription = "Clear selection") }
                    else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (state.selecting) {
                        TextButton(onClick = viewModel::selectAll) { Text("All") }
                        IconButton(onClick = { confirmDelete = true }, enabled = !state.busy, modifier = Modifier.testTag("delete_selected")) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected images", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.busy && state.problem == null,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("add_images"),
                ) {
                    Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Add Images")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val problem = state.problem
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            problem != null && state.images.isEmpty() -> ProblemPanel(problem.message, viewModel::refresh, Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("class_images"),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { GalleryHeader(state, onDismissUpload = viewModel::uploadDismissed) }
                if (state.images.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyGallery(state.className) }
                }
                items(state.images, key = { it.id }) { image ->
                    Thumbnail(
                        image = image,
                        selected = image.id in state.selected,
                        url = { viewModel.thumbnailUrl(image.id) },
                        onClick = { if (state.selecting) viewModel.toggleSelection(image.id) else preview = image },
                        onLongClick = { viewModel.toggleSelection(image.id) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${state.selected.size} image(s)?") },
            text = { Text("The images and their cached DINOv2 features are removed from the server dataset. The classifier will be rebuilt automatically.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteSelected() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    preview?.let { image ->
        PreviewDialog(
            image = image,
            url = { viewModel.thumbnailUrl(image.id) },
            deleteEnabled = !state.busy,
            onDelete = { preview = null; confirmDeleteOne = image },
            onDismiss = { preview = null },
        )
    }
    confirmDeleteOne?.let { image ->
        AlertDialog(
            onDismissRequest = { confirmDeleteOne = null },
            title = { Text("Delete this image?") },
            text = { Text("${image.originalFilename} and its cached DINOv2 feature are removed from the server dataset. The classifier will be rebuilt automatically.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteOne = null; viewModel.deleteImage(image.id) }, modifier = Modifier.testTag("confirm_delete_image")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteOne = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GalleryHeader(state: ClassImagesUiState, onDismissUpload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(imageCountText(state.images.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            state.trainingStatus?.let {
                val label = modelStatusLabel(it)
                StatusPill("Model: ${label.text}", label.tone, Modifier.testTag("images_training_status"))
            }
        }
        Text(
            "Tap an image to preview or delete it. Long-press to select several.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.upload?.let { UploadPanel(it, onDismissUpload) }
        if (state.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun UploadPanel(progress: UploadProgress, onDismiss: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("upload_progress")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (progress.finished) "Uploaded ${progress.uploaded} of ${progress.total}"
                else "Uploading ${progress.done + 1} of ${progress.total} · computing DINOv2 features…",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
            progress.failures.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (progress.finished) TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(image: DatasetImageDto, selected: Boolean, url: suspend () -> String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val model by produceState<String?>(null, image.id) { value = url() }
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics {
                this.selected = selected
                contentDescription = "${image.originalFilename}, uploaded ${uploadedText(image)}"
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.small) else Modifier),
        ) {
            if (model != null) {
                SubcomposeAsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp),
                )
            }
            if (image.embeddingStatus != "READY") {
                Text(
                    if (image.embeddingStatus == "ERROR") "Feature error" else "Processing",
                    style = MaterialTheme.typography.labelSmall,
                    color = GeoColors.Charcoal,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(GeoColors.GpsAmberLight, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp),
                )
            }
        }
        Text(image.originalFilename, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(uploadedText(image), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun uploadedText(image: DatasetImageDto): String =
    runCatching { Formatters.shortDateTime(image.createdAt.parseIsoMillis()) }.getOrDefault("")

@Composable
private fun PreviewDialog(image: DatasetImageDto, url: suspend () -> String, deleteEnabled: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val model by produceState<String?>(null, image.id) { value = url().removeSuffix("/thumbnail") + "/file" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(image.originalFilename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (model != null) {
                        SubcomposeAsyncImage(model = model, contentDescription = "Preview of ${image.originalFilename}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(
                    listOfNotNull(
                        "Uploaded ${uploadedText(image)}",
                        if (image.width != null && image.height != null) "${image.width} × ${image.height}" else null,
                        "Feature: ${image.embeddingStatus.lowercase()}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = onDelete, enabled = deleteEnabled, modifier = Modifier.testTag("preview_delete")) {
                Text("Delete image", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun EmptyGallery(className: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp).testTag("empty_gallery"),
    ) {
        Icon(painterResource(R.drawable.ic_leaf), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
        Text("No images in ${className.ifEmpty { "this classifier" }} yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Use Add Images to upload clear close-ups of single tamarind leaflets showing this condition. Each classifier needs enough images before the model can be trained.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
