package com.geotree.app.core.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geotree.app.R
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Owns the CameraX use cases so the Composable only hosts the preview surface. */
class TreeCameraController(private val context: Context) {
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        // ~2 MP keeps uploads small while remaining useful for later leaf assessment.
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .build()

    /** Returns null on success, or a user-facing error. */
    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView): String? = try {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val selector = when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> null
        }
        if (selector == null) {
            "No camera is available on this device."
        } else {
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            null
        }
    } catch (e: Exception) {
        "Camera unavailable: ${e.message ?: e.javaClass.simpleName}"
    }

    suspend fun capture(target: File): Result<File> = suspendCancellableCoroutine { cont ->
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(Result.success(target))
                }

                override fun onError(exception: ImageCaptureException) {
                    target.delete()
                    if (cont.isActive) cont.resume(Result.failure(exception))
                }
            },
        )
    }
}

@Composable
fun CameraCaptureScreen(
    imageStore: TreeImageStore,
    onCaptured: (File) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { TreeCameraController(context) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var bindError by remember { mutableStateOf<String?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var requestCapture by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        bindError = controller.bind(lifecycleOwner, previewView)
    }
    LaunchedEffect(requestCapture) {
        if (!requestCapture) return@LaunchedEffect
        capturing = true
        captureError = null
        controller.capture(imageStore.newImageFile())
            .onSuccess(onCaptured)
            .onFailure { captureError = "Capture failed: ${it.message ?: "unknown error"}. Try again." }
        capturing = false
        requestCapture = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        IconButton(
            onClick = onClose,
            modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
        ) { Icon(Icons.Default.Close, contentDescription = "Close camera") }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
        ) {
            val message = bindError ?: captureError
            if (message != null) {
                Text(
                    message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small).padding(12.dp),
                )
            } else {
                Text("Frame the whole tree, trunk to crown", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = { requestCapture = true },
                    enabled = bindError == null && !capturing,
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .semantics { contentDescription = "Capture tree image" },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.25f), contentColor = Color.White),
                ) {
                    if (capturing) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                    else Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
