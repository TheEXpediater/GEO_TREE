package com.geotree.app.feature.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.GeoTreeLogo
import com.geotree.app.core.network.ApiProvider
import com.geotree.app.core.session.SessionStore
import com.geotree.app.core.sync.SyncScheduler
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.geoViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SplashDestination { Login, Locator }

data class SplashUiState(
    val step: String = "Starting…",
    val error: String? = null,
    val destination: SplashDestination? = null,
)

class SplashViewModel(
    private val treeRepository: TreeRepository,
    private val sessionStore: SessionStore,
    private val apiProvider: ApiProvider,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(SplashUiState())
    val state: StateFlow<SplashUiState> = _state.asStateFlow()

    init {
        start()
    }

    fun start() {
        _state.value = SplashUiState()
        viewModelScope.launch {
            try {
                _state.value = SplashUiState(step = "Opening field records…")
                treeRepository.treeCount()
                _state.value = SplashUiState(step = "Restoring session…")
                val session = sessionStore.current()
                _state.value = SplashUiState(step = "Loading server settings…")
                apiProvider.api()
                if (session != null) {
                    // Offline-safe: work waits for connectivity and a healthy backend.
                    syncScheduler.requestSync()
                    syncScheduler.schedulePeriodicSync()
                }
                _state.value = SplashUiState(
                    step = "Ready",
                    destination = if (session != null) SplashDestination.Locator else SplashDestination.Login,
                )
            } catch (e: Exception) {
                _state.value = SplashUiState(error = "Could not open local records: ${e.message}")
            }
        }
    }
}

@Composable
fun SplashScreen(onReady: (SplashDestination) -> Unit) {
    val viewModel = geoViewModel { c, _ ->
        SplashViewModel(c.treeRepository, c.sessionStore, c.apiProvider, c.syncScheduler)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.destination) {
        state.destination?.let(onReady)
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("splash"),
        contentAlignment = Alignment.Center,
    ) {
        ContourBackdrop()
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            GeoTreeLogo(size = 132.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "GEO Tree",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
            )
            Text(
                "FIELD MAPPING",
                style = MaterialTheme.typography.labelLarge,
                color = GeoColors.Clay,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(40.dp))
            if (state.error == null) {
                LinearProgressIndicator(modifier = Modifier.width(160.dp))
                Spacer(Modifier.height(12.dp))
                Text(state.step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    state.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = viewModel::start) { Text("Try again") }
            }
        }
    }
}

/** Faint survey contours: a restrained cartographic cue behind the brand. */
@Composable
private fun ContourBackdrop() {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width * 0.5f, size.height * 0.42f)
        for (i in 1..9) {
            drawCircle(color, radius = i * size.minDimension * 0.09f, center = center, style = Stroke(width = 2f))
        }
    }
}
