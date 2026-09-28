package com.geotree.app.feature.tagtree

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geotree.app.appContainer
import com.geotree.app.core.camera.CameraCaptureScreen
import com.geotree.app.core.design.GeoTreeLogo
import com.geotree.app.core.location.GpsAccuracyPolicy
import com.geotree.app.geoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagTreeScreen(onBack: () -> Unit, onSaved: (id: String, treeCode: String) -> Unit) {
    val context = LocalContext.current
    val viewModel = geoViewModel { c, _ ->
        TagTreeViewModel(c.treeRepository, c.locationClient, c.imageStore, onTreeSaved = { c.syncScheduler.requestSync() })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onLocationPermissionResult()
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openCamera() else viewModel.onCameraPermissionDenied()
    }

    LaunchedEffect(state.saved) { state.saved?.let { onSaved(it.id, it.treeCode) } }

    if (state.cameraOpen) {
        BackHandler(onBack = viewModel::closeCamera)
        CameraCaptureScreen(
            imageStore = context.appContainer.imageStore,
            onCaptured = viewModel::onImageCaptured,
            onClose = viewModel::closeCamera,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GeoTreeLogo(size = 28.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Tag Tree")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Field Locator") }
                },
            )
        },
        bottomBar = {
            SaveBar(saving = state.saving, onSave = { viewModel.save() })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TreeImageSection(
                imagePath = state.imagePath,
                message = state.cameraMessage,
                onCapture = { cameraPermission.launch(Manifest.permission.CAMERA) },
            )
            OutlinedTextField(
                value = state.treeCode,
                onValueChange = viewModel::onTreeCodeChange,
                label = { Text("Tree Code") },
                placeholder = { Text("GEO-TAM-003") },
                singleLine = true,
                isError = state.treeCodeError != null,
                supportingText = { Text(state.treeCodeError ?: "Human-readable code, unique per tree. e.g. GEO-TAM-003") },
                visualTransformation = UppercaseTransformation,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("tree_code"),
            )
            GpsCard(
                location = state.location,
                onAcquire = viewModel::acquireLocation,
                onRequestPermission = {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                onOpenLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
            OptionalDetailsSection(state = state, viewModel = viewModel)
            state.formError?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp).testTag("form_error"))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (state.confirmLowAccuracy) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLowAccuracyDialog,
            title = { Text("Low GPS accuracy") },
            text = { Text("${GpsAccuracyPolicy.LOW_ACCURACY_MESSAGE}\n\nThe reading is valid, so you can still save it and recapture later.") },
            confirmButton = { TextButton(onClick = { viewModel.save(confirmLowAccuracy = true) }) { Text("Save Anyway") } },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissLowAccuracyDialog()
                    viewModel.acquireLocation()
                }) { Text("Recapture") }
            },
        )
    }
}

/** Shows Tree Codes in upper case without rewriting the underlying text (same length, identity offsets). */
internal val UppercaseTransformation = VisualTransformation { text ->
    TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
}

@Composable
private fun SaveBar(saving: Boolean, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(54.dp)
                .testTag("save_tag"),
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.size(10.dp))
                Text("Saving…")
            } else {
                Text("Save Tag", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
