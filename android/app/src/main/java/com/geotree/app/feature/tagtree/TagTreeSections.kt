package com.geotree.app.feature.tagtree

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.geotree.app.R
import com.geotree.app.core.design.CoordinateTextStyle
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.design.label
import com.geotree.app.core.design.tone
import com.geotree.app.core.location.GpsAccuracyPolicy
import com.geotree.app.core.location.GpsQuality
import com.geotree.app.core.location.LocationState
import java.io.File

@Composable
fun TreeImageSection(imagePath: String?, message: String?, onCapture: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = "Capture tree image", onClick = onCapture)
                .testTag("tree_image"),
        ) {
            if (imagePath != null) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = "Captured tree photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painterResource(R.drawable.ic_photo_camera),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("No tree image yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (imagePath == null) {
            FilledTonalButton(onClick = onCapture, modifier = Modifier.fillMaxWidth().testTag("capture_image")) {
                Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Capture Tree Image")
            }
        } else {
            OutlinedButton(onClick = onCapture, modifier = Modifier.fillMaxWidth().testTag("retake_image")) { Text("Retake") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun GpsCard(
    location: LocationState,
    onAcquire: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().testTag("gps_card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GPS Location", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                GpsStatePill(location)
            }
            val fix = (location as? LocationState.Ready)?.fix
            CoordinateRow("Latitude", fix?.let { Formatters.latitude(it.latitude) }, tag = "gps_latitude")
            CoordinateRow("Longitude", fix?.let { Formatters.longitude(it.longitude) }, tag = "gps_longitude")
            CoordinateRow("Accuracy", fix?.let { Formatters.accuracy(it.accuracyMeters) }, tag = "gps_accuracy")
            if (fix?.altitudeMeters != null) CoordinateRow("Altitude", Formatters.altitude(fix.altitudeMeters), tag = "gps_altitude")
            CoordinateRow("Captured at", fix?.let { Formatters.dateTime(it.capturedAt) }, tag = "gps_time", monospace = false)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GpsGuidance(location, onRequestPermission, onOpenLocationSettings)
            FilledTonalButton(
                onClick = onAcquire,
                enabled = location !is LocationState.Acquiring,
                modifier = Modifier.fillMaxWidth().testTag("acquire_location"),
            ) {
                if (location is LocationState.Acquiring) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = GeoColors.GpsAmber)
                    Spacer(Modifier.size(8.dp))
                    Text("Acquiring GPS…")
                } else {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (location is LocationState.Ready) "Refresh Location" else "Acquire Location")
                }
            }
        }
    }
}

@Composable
private fun GpsStatePill(location: LocationState) {
    when (location) {
        is LocationState.Ready -> StatusPill(location.fix.quality.label(), location.fix.quality.tone(), description = "GPS quality ${location.fix.quality.label()}")
        LocationState.Acquiring -> StatusPill("Acquiring", PillTone.Amber)
        is LocationState.PermissionRequired -> StatusPill("Permission needed", PillTone.Warning)
        LocationState.LocationDisabled -> StatusPill("Location off", PillTone.Error)
        is LocationState.Error -> StatusPill("No fix", PillTone.Error)
        LocationState.Idle -> StatusPill("Not captured", PillTone.Neutral)
    }
}

@Composable
private fun GpsGuidance(location: LocationState, onRequestPermission: () -> Unit, onOpenLocationSettings: () -> Unit) {
    when (location) {
        is LocationState.PermissionRequired -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (location.denied) "Location permission was denied. GEO Tree needs precise location to geotag trees."
                else "Allow location access to record where this tree stands.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onRequestPermission, modifier = Modifier.testTag("grant_location")) { Text("Allow Location") }
        }
        LocationState.LocationDisabled -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Location services are turned off on this device.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenLocationSettings) { Text("Open Location Settings") }
        }
        is LocationState.Error -> Text(location.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        is LocationState.Ready -> {
            if (location.approximateOnly) {
                Warning("Only approximate location is allowed. Enable Precise location for GEO Tree in Settings for usable tree coordinates.")
            }
            when (location.fix.quality) {
                GpsQuality.LOW -> Warning(GpsAccuracyPolicy.LOW_ACCURACY_MESSAGE)
                GpsQuality.ACCEPTABLE -> Text("Accuracy is acceptable. Wait a moment and refresh for a better fix if needed.", style = MaterialTheme.typography.bodySmall)
                GpsQuality.GOOD -> Text("Good fix. Coordinates are ready to save.", style = MaterialTheme.typography.bodySmall, color = GeoColors.Success)
            }
        }
        LocationState.Acquiring -> Text("Requesting a fresh high-accuracy fix from Android location services…", style = MaterialTheme.typography.bodySmall)
        LocationState.Idle -> Text("Stand at the trunk, then acquire location.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Warning(text: String) {
    Surface(color = GeoColors.ClayLight, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color(0xFF5A2A0E), modifier = Modifier.padding(10.dp).testTag("gps_warning"))
    }
}

@Composable
fun CoordinateRow(label: String, value: String?, tag: String, monospace: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label ${value ?: "not captured"}" },
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value ?: "—",
            style = if (monospace) CoordinateTextStyle else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(tag),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionalDetailsSection(state: TagTreeUiState, viewModel: TagTreeViewModel) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (state.detailsExpanded) "Collapse optional details" else "Expand optional details", onClick = viewModel::toggleDetails)
                .padding(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Optional Tree Details", style = MaterialTheme.typography.titleMedium)
                Text("Age, taste, yield, fruit quality, notes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (state.detailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        AnimatedVisibility(visible = state.detailsExpanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.ageText,
                    onValueChange = viewModel::onAgeChange,
                    label = { Text("Age (years)") },
                    singleLine = true,
                    isError = state.ageError != null,
                    supportingText = state.ageError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceChips("Taste Category", listOf("Sweet", "Sour", "Sweet-sour"), state.tasteCategory, viewModel::onTasteChange)
                OutlinedTextField(
                    value = state.yieldText,
                    onValueChange = viewModel::onYieldChange,
                    label = { Text("Yearly Yield (kg)") },
                    singleLine = true,
                    isError = state.yieldError != null,
                    supportingText = state.yieldError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceChips("Fruit Quality", listOf("Excellent", "Good", "Fair", "Poor"), state.fruitQuality, viewModel::onFruitQualityChange)
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = viewModel::onNotesChange,
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(title: String, options: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(if (selected == option) null else option) },
                    label = { Text(option) },
                )
            }
        }
    }
}
