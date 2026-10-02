package com.geotree.app.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geotree.app.BuildConfig
import com.geotree.app.R
import com.geotree.app.appContainer
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.GeoTreeLogo
import com.geotree.app.geoViewModel

@Composable
/**
 * [reauth] = the stored session expired and the user chose "Sign In to Sync": local data stays,
 * the new token replaces the old one, and [onBack] returns to the app without signing in.
 */
fun LoginScreen(onSignedIn: () -> Unit, reauth: Boolean = false, onBack: (() -> Unit)? = null) {
    val viewModel = geoViewModel { c, _ ->
        LoginViewModel(
            authRepository = c.authRepository,
            serverDescription = { c.backendConfig.current() },
            afterSignIn = {
                // Resume sync immediately (also after a re-sign-in with an expired session).
                c.syncScheduler.requestSync(replace = true)
                c.syncScheduler.schedulePeriodicSync()
            },
            prefillEmail = BuildConfig.DEV_EMAIL,
            prefillPassword = BuildConfig.DEV_PASSWORD,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverUrl by LocalContext.current.appContainer.backendConfig.baseUrl.collectAsStateWithLifecycle(initialValue = "")
    var showServerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            if (reauth) {
                ReauthNotice(onBack)
                Spacer(Modifier.height(16.dp))
            }
            GeoTreeLogo(size = 88.dp)
            Spacer(Modifier.height(12.dp))
            Text("GEO Tree", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("FIELD MAPPING", style = MaterialTheme.typography.labelMedium, color = GeoColors.Clay, letterSpacing = 3.sp)
            Spacer(Modifier.height(28.dp))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Sign in", style = MaterialTheme.typography.titleLarge)
                    if (BuildConfig.DEBUG) {
                        Text(
                            "Debug build · development credentials prefilled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::onEmailChange,
                        label = { Text("Email") },
                        singleLine = true,
                        isError = state.emailError != null,
                        supportingText = state.emailError?.let { { Text(it) } },
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("login_email"),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text("Password") },
                        singleLine = true,
                        isError = state.passwordError != null,
                        supportingText = state.passwordError?.let { { Text(it) } },
                        enabled = !state.isLoading,
                        visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                        trailingIcon = {
                            IconButton(onClick = viewModel::togglePasswordVisibility) {
                                Icon(
                                    painterResource(if (state.passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                    contentDescription = if (state.passwordVisible) "Hide password" else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("login_password"),
                    )
                    state.errorMessage?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp).semantics { error(message) }.testTag("login_error"),
                            )
                        }
                    }
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("login_submit"),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.size(10.dp))
                            Text("Signing in…")
                        } else {
                            Text("Sign In")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Server: $serverUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = { showServerDialog = true }, enabled = !state.isLoading) { Text("Change") }
            }
        }
    }

    if (showServerDialog) ServerSettingsDialog(onDismiss = { showServerDialog = false })
}

@Composable
private fun ReauthNotice(onBack: (() -> Unit)?) {
    Surface(color = GeoColors.GpsAmberLight, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("reauth_notice")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sign in to resume synchronization", style = MaterialTheme.typography.titleSmall)
            Text(
                "Your 7-day session has expired. Trees, photos and maps on this device are kept, and field work continues offline.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("reauth_back")) { Text("Continue without syncing") }
            }
        }
    }
}
