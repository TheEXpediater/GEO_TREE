package com.geotree.app.feature.leafassessment

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.geotree.app.R
import com.geotree.app.appContainer
import com.geotree.app.core.camera.CameraCaptureScreen
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.StatusPill
import com.geotree.app.geoViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeafAssessmentScreen(treeId: String, onBack: () -> Unit, onStarted: (assessmentId: String) -> Unit) {
    val viewModel = geoViewModel(key = "leaf_$treeId") { c, _ ->
        LeafAssessmentViewModel(
            treeId = treeId,
            repository = c.leafAssessmentRepository,
            importPicked = { uri -> c.localImageReader.read(uri)?.let { image -> withContext(Dispatchers.IO) { c.leafImageStore.save(image.bytes) } } },
            deleteUnsaved = c.leafImageStore::delete,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openCamera() else viewModel.onCameraPermissionDenied()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.onImagePicked(it.toString()) }
    }
    val takePhoto = { cameraPermission.launch(Manifest.permission.CAMERA) }
    // The scan is recorded in Room before this fires; its result page takes over from here.
    LaunchedEffect(state.startedAssessmentId) {
        state.startedAssessmentId?.let { id ->
            viewModel.navigationHandled()
            onStarted(id)
        }
    }
    val choosePhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    if (state.cameraOpen) {
        val store = LocalContext.current.appContainer.leafImageStore
        BackHandler(onBack = viewModel::closeCamera)
        CameraCaptureScreen(
            imageStore = store,
            onCaptured = viewModel::onImageCaptured,
            onClose = viewModel::closeCamera,
            hint = "Fill the frame with one leaflet, in even light",
            captureDescription = "Capture leaf photo",
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assess Leaf") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            AvailabilityBanner(state.availability, onRefresh = viewModel::refreshAvailability)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            when (val stage = state.stage) {
                LeafStage.Capture -> CapturePrompt(takePhoto, choosePhoto)
                is LeafStage.Review -> {
                    LeafPhoto(stage.imagePath)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = viewModel::retake, modifier = Modifier.weight(1f)) { Text("Retake") }
                        Button(onClick = viewModel::assess, modifier = Modifier.weight(1f).testTag("run_assessment")) { Text("Run Assessment") }
                    }
                }
                is LeafStage.Starting -> {
                    LeafPhoto(stage.imagePath)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("assessing")) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Saving this scan…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailabilityBanner(availability: AiAvailability, onRefresh: () -> Unit) {
    val (label, tone) = availabilityPill(availability)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(label, tone, Modifier.testTag("assessment_ai_status"))
        Spacer(Modifier.size(8.dp))
        Text(
            when (availability) {
                is AiAvailability.Ready -> availability.modelDisplayName + (availability.modelVersion?.let { " · v$it" } ?: "") + " · on this phone"
                AiAvailability.Checking -> ""
                else -> "On-device model"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (availability !is AiAvailability.Ready && availability != AiAvailability.Checking) TextButton(onClick = onRefresh) { Text("Check again") }
    }
    when (availability) {
        is AiAvailability.NotReady -> Notice(availability.message, GeoColors.ClayLight)
        else -> Unit
    }
}

@Composable
private fun CapturePrompt(onTakePhoto: () -> Unit, onChoosePhoto: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(painterResource(R.drawable.ic_leaf), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                "Photograph one tamarind leaflet up close, filling the frame, in even daylight.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth().testTag("take_leaf_photo")) {
        Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Take Photo")
    }
    OutlinedButton(onClick = onChoosePhoto, modifier = Modifier.fillMaxWidth().testTag("choose_leaf_photo")) {
        Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Choose Photo")
    }
}

@Composable
private fun LeafPhoto(path: String) {
    AsyncImage(
        model = File(path),
        contentDescription = "Leaf photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
internal fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = GeoColors.Charcoal, modifier = Modifier.padding(10.dp))
    }
}
