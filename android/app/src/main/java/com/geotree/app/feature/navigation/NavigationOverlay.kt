package com.geotree.app.feature.navigation

import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geotree.app.core.design.CoordinateTextStyle
import com.geotree.app.core.design.Formatters
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.design.StatusPill
import com.geotree.app.core.design.tone
import com.geotree.app.core.location.GpsAccuracyPolicy
import com.geotree.app.core.orientation.HeadingAccuracy
import com.geotree.app.core.orientation.HeadingState
import com.geotree.app.core.orientation.degreesOrNull
import java.util.Locale

/**
 * How arrival time is shown. A measured GPS speed and the walking-pace fallback are different
 * kinds of information, so they are different types and never share the "Current speed" label.
 */
sealed interface ArrivalTiming {
    /** Android reported a plausible, recent speed: show it and the ETA it implies. */
    data class Measured(val speed: String, val eta: String) : ArrivalTiming

    /** No usable measured speed: an estimate at a fixed walking pace, with the reason. */
    data class WalkingEstimate(val eta: String, val reason: String, val pace: String) : ArrivalTiming

    /** No GPS fix yet in this session. */
    data object Unavailable : ArrivalTiming
}

/** Display strings for a guidance session; pure so it can be unit-tested. */
data class NavigationDisplay(
    val distance: String,
    val direction: String,
    val timing: ArrivalTiming,
)

fun FieldNavigationState.display(config: NavigationConfig = NavigationConfig.Default): NavigationDisplay {
    val distance = distanceRemainingMeters?.let { "${NavigationFormat.distance(it)} away" } ?: "Waiting for GPS fix…"
    val direction = bearingDegrees?.let { String.format(Locale.US, "%s · %.0f°", NavigationMath.cardinal(it), it) } ?: "—"
    val measured = speed as? SpeedReading.Moving
    val timing = when {
        etaSource == EtaSource.CURRENT_SPEED && measured != null && etaSeconds != null ->
            ArrivalTiming.Measured(NavigationFormat.speedKmh(measured.metersPerSecond), NavigationFormat.duration(etaSeconds))
        etaSource == EtaSource.WALKING_ESTIMATE && etaSeconds != null -> ArrivalTiming.WalkingEstimate(
            eta = "≈ ${NavigationFormat.duration(etaSeconds)}",
            reason = if (speed == SpeedReading.Stationary) "You are stationary" else "GPS is not reporting your speed",
            pace = String.format(Locale.US, "at %.1f km/h walking pace", config.walkingSpeedMps * 3.6),
        )
        else -> ArrivalTiming.Unavailable
    }
    return NavigationDisplay(distance, direction, timing)
}

/** Heading line under the distance: relative turn when the compass is live, otherwise why not. */
fun headingCaption(state: FieldNavigationState, heading: HeadingState, config: NavigationConfig = NavigationConfig.Default): String {
    val bearing = state.bearingDegrees ?: return "Direction appears once GPS has a fix."
    return when (heading) {
        is HeadingState.Available -> {
            val guidance = RelativeBearing.guidance(bearing, heading.degrees, config)
            val calibrate = if (heading.accuracy == HeadingAccuracy.LOW || heading.accuracy == HeadingAccuracy.UNRELIABLE) {
                " · compass needs calibration: move the phone in a figure-8"
            } else ""
            guidance.instruction + calibrate
        }
        HeadingState.Inactive -> "Reading the phone compass…"
        HeadingState.Unsupported -> "No compass on this phone · direction is the bearing from north"
    }
}

/**
 * Field-guidance panel during an active session. [expanded] only changes the UI: the same
 * [state] keeps updating from GPS either way, and collapsing never stops guidance.
 */
@Composable
fun NavigationPanel(
    state: FieldNavigationState,
    heading: HeadingState,
    expanded: Boolean,
    headingUp: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCompassTap: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Space left between the map's top controls and the bottom; the expanded panel scrolls within it. */
    maxHeight: Dp = Dp.Unspecified,
    config: NavigationConfig = NavigationConfig.Default,
) {
    val display = state.display(config)
    val deviceHeading = heading.degreesOrNull
    val aligned = state.bearingDegrees != null && deviceHeading != null &&
        RelativeBearing.guidance(state.bearingDegrees, deviceHeading, config).aligned
    val swipe = Modifier.pointerInput(expanded) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val threshold = 40.dp.toPx()
                if (expanded && total > threshold) onExpandedChange(false)
                if (!expanded && total < -threshold) onExpandedChange(true)
            },
        ) { _, dy -> total += dy }
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
            .testTag("navigation_panel"),
    ) {
        if (expanded) {
            ExpandedGuidance(state, display, heading, aligned, headingUp, onExpandedChange, onCompassTap, onStop, config, swipe)
        } else {
            CollapsedGuidance(state, display, deviceHeading, aligned, config, swipe) { onExpandedChange(true) }
        }
    }
}

@Composable
private fun ExpandedGuidance(
    state: FieldNavigationState,
    display: NavigationDisplay,
    heading: HeadingState,
    aligned: Boolean,
    headingUp: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCompassTap: () -> Unit,
    onStop: () -> Unit,
    config: NavigationConfig,
    swipe: Modifier,
) {
    // The drag handle and header take the swipe-down gesture; the body scrolls on short screens.
    Column(Modifier.padding(start = 18.dp, end = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(end = 10.dp)
                .then(swipe)
                .padding(top = 8.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = swipe) {
            Column(Modifier.weight(1f)) {
                Text("FIELD GUIDANCE · DIRECT LINE", style = MaterialTheme.typography.labelSmall, color = GeoColors.Clay, letterSpacing = 1.5.sp)
                Text(state.destination.treeCode, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("nav_tree_code"))
            }
            state.gpsAccuracy?.let { accuracy ->
                StatusPill(
                    "GPS ${Formatters.accuracy(accuracy)}",
                    GpsAccuracyPolicy.Default.classify(accuracy).tone(),
                    Modifier.testTag("nav_gps"),
                    description = "GPS accuracy ${Formatters.accuracy(accuracy)}",
                )
            }
            IconButton(onClick = { onExpandedChange(false) }, modifier = Modifier.testTag("nav_collapse")) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize guidance")
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.arrived) StatusPill("Tree reached", PillTone.Good, Modifier.testTag("nav_arrived"))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NavigationCompass(
                    targetBearing = state.bearingDegrees,
                    heading = heading.degreesOrNull,
                    aligned = aligned,
                    toleranceDegrees = config.alignmentToleranceDegrees,
                    modifier = Modifier
                        .size(104.dp)
                        .clickable(onClickLabel = if (headingUp) "Switch the map to North Up" else "Switch the map to Heading Up", onClick = onCompassTap)
                        .testTag("nav_compass"),
                )
                Text(
                    if (headingUp) "Heading Up" else "North Up",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("nav_map_orientation"),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(display.distance, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("nav_distance"))
                Text("Destination ${display.direction}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("nav_direction"))
                Text(
                    headingCaption(state, heading, config),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aligned) GeoColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (aligned) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.testTag("nav_relative"),
                )
            }
        }
        Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TimingBlock(display.timing)
            Text(
                "Direct line to the tree, not a road route. Tap the compass to switch Heading Up / North Up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onStop, modifier = Modifier.fillMaxWidth().testTag("stop_navigation")) { Text("Stop Guidance") }
        }
        }
    }
}

/** One-line bar: guidance keeps running underneath while the map gets the space. */
@Composable
private fun CollapsedGuidance(
    state: FieldNavigationState,
    display: NavigationDisplay,
    deviceHeading: Double?,
    aligned: Boolean,
    config: NavigationConfig,
    swipe: Modifier,
    onExpand: () -> Unit,
) {
    val eta = when (val t = display.timing) {
        is ArrivalTiming.Measured -> t.eta
        is ArrivalTiming.WalkingEstimate -> t.eta
        ArrivalTiming.Unavailable -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(swipe)
            .clickable(onClickLabel = "Expand guidance", onClick = onExpand)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("nav_collapsed"),
    ) {
        NavigationCompass(state.bearingDegrees, deviceHeading, aligned, config.alignmentToleranceDegrees, Modifier.size(40.dp), showNorthLabel = false)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(state.destination.treeCode, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                if (state.arrived) "Tree reached" else "Field guidance",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.arrived) GeoColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            state.distanceRemainingMeters?.let { NavigationFormat.distance(it) } ?: "—",
            style = CoordinateTextStyle.copy(fontSize = 15.sp),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("nav_collapsed_distance"),
        )
        if (eta != null) {
            Spacer(Modifier.width(10.dp))
            Text(eta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("nav_collapsed_eta"))
        }
        IconButton(onClick = onExpand, modifier = Modifier.testTag("nav_expand")) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Expand guidance")
        }
    }
}

/** Measured values look like instrument readings; the walking estimate is visibly an estimate. */
@Composable
private fun TimingBlock(timing: ArrivalTiming) {
    when (timing) {
        is ArrivalTiming.Measured -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("nav_measured")) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Current speed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    StatusPill("GPS", PillTone.Good, description = "Measured by GPS")
                }
                Text(timing.speed, style = CoordinateTextStyle.copy(fontSize = 16.sp), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("nav_speed"))
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text("ETA at current speed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(timing.eta, style = CoordinateTextStyle.copy(fontSize = 16.sp), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("nav_eta"))
            }
        }
        is ArrivalTiming.WalkingEstimate -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("nav_walking_estimate"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                Column(Modifier.weight(1f)) {
                    Text("Walking estimate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${timing.reason} · ${timing.pace}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("nav_estimate_reason"),
                    )
                }
                Text(
                    timing.eta,
                    style = MaterialTheme.typography.titleMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("nav_eta"),
                )
            }
        }
        ArrivalTiming.Unavailable -> Text(
            "Arrival time appears once GPS has a fix.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("nav_eta"),
        )
    }
}

@Composable
private fun Metric(label: String, value: String, tag: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = CoordinateTextStyle.copy(fontSize = 14.sp), fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(tag))
    }
}

/** Shown once per session when the user first comes within the arrival radius. */
@Composable
fun ArrivalDialog(treeCode: String, radiusMeters: Double, onStop: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("Tree reached") },
        text = { Text(String.format(Locale.US, "You are within %.0f m of %s.", radiusMeters, treeCode)) },
        confirmButton = { TextButton(onClick = onStop, modifier = Modifier.testTag("arrival_stop")) { Text("Stop Navigation") } },
        dismissButton = { TextButton(onClick = onContinue) { Text("Keep guiding") } },
        modifier = Modifier.testTag("arrival_dialog"),
    )
}
