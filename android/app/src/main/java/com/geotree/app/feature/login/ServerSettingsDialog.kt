package com.geotree.app.feature.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.network.BackendCheck
import com.geotree.app.core.network.BackendConfig
import com.geotree.app.core.network.BackendConnectionManager
import com.geotree.app.geoViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSettingsState(
    val url: String = "",
    val checking: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val saved: Boolean = false,
)

class ServerSettingsViewModel(
    private val backendConfig: BackendConfig,
    private val connection: BackendConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerSettingsState())
    val state: StateFlow<ServerSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(url = backendConfig.current()) } }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value, message = null, saved = false) }

    fun testAndSave() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, message = null) }
        viewModelScope.launch {
            val result = connection.verifyAndSave(_state.value.url)
            val (message, isError) = when (result) {
                is BackendCheck.Verified -> "Connected to GEO Tree API ${result.version.orEmpty()}. Saved." to false
                is BackendCheck.NotGeoTree -> "${result.baseUrl} responded, but it is not a GEO Tree server. Not saved." to true
                is BackendCheck.DatabaseUnavailable -> "GEO Tree server found but its database is unavailable. Not saved." to true
                is BackendCheck.Unreachable -> "Cannot reach ${result.baseUrl}. Not saved." to true
                BackendCheck.InvalidUrl -> "Enter an address like http://192.168.1.20:8000" to true
            }
            _state.update { it.copy(checking = false, message = message, isError = isError, saved = result is BackendCheck.Verified) }
        }
    }

    fun resetToDefault() {
        viewModelScope.launch {
            backendConfig.resetToDefault()
            _state.update { it.copy(url = backendConfig.defaultBaseUrl, message = "Reset to the default server.", isError = false, saved = true) }
        }
    }
}

@Composable
fun ServerSettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val viewModel = geoViewModel(key = "server_settings") { c, _ -> ServerSettingsViewModel(c.backendConfig, c.backendConnection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GEO Tree server") },
        text = {
            Column {
                Text(
                    "Emulator: http://10.0.2.2:8000\nPhone on Wi-Fi: http://<laptop-LAN-IP>:8000",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChange,
                    label = { Text("Server address") },
                    singleLine = true,
                    enabled = !state.checking,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.message?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = viewModel::testAndSave, enabled = !state.checking) { Text("Test & Save") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = viewModel::resetToDefault, enabled = !state.checking) { Text("Default") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
