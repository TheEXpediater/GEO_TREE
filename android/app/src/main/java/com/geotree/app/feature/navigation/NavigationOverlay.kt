package com.geotree.app.feature.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
    val direction = bearingDegrees?.let { String.format(Locale.US, "%s %.0f°", NavigationMath.cardinal(it), it) } ?: "—"
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

/** Persistent bottom panel during active field guidance. The map stays visible above it. */
@Composable
fun NavigationPanel(
    state: FieldNavigationState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    config: NavigationConfig = NavigationConfig.Default,
) {
    val display = state.display(config)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().testTag("navigation_panel"),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "FIELD GUIDANCE · DIRECT LINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = GeoColors.Clay,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        state.destination.treeCode,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("nav_tree_code"),
                    )
                }
                state.gpsAccuracy?.let { accuracy ->
                    StatusPill(
                        "GPS ${Formatters.accuracy(accuracy)}",
                        GpsAccuracyPolicy.Default.classify(accuracy).tone(),
                        Modifier.testTag("nav_gps"),
                        description = "GPS accuracy ${Formatters.accuracy(accuracy)}",
                    )
                }
            }

            if (state.arrived) {
                StatusPill("Tree reached", PillTone.Good, Modifier.testTag("nav_arrived"))
            }
            Text(
                display.distance,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("nav_distance"),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                BearingArrow(state.bearingDegrees)
                Spacer(Modifier.width(10.dp))
                Metric("Direction to tree", display.direction, "nav_direction")
            }
            TimingBlock(display.timing)

            Text(
                "Direct line to the tree, not a road route. Direction is the bearing from north.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onStop, modifier = Modifier.fillMaxWidth().testTag("stop_navigation")) {
                Text("Stop Navigation")
            }
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

/** Arrow pointing along the bearing to the tree, relative to map north (not a device compass). */
@Composable
private fun BearingArrow(bearingDegrees: Double?) {
    val ring = MaterialTheme.colorScheme.outline
    val arrow = MaterialTheme.colorScheme.primary
    val description = bearingDegrees?.let { "Tree is ${NavigationMath.cardinal(it)} of you" } ?: "Direction unavailable"
    Canvas(Modifier.size(32.dp).semantics { contentDescription = description }) {
        val r = size.minDimension / 2
        drawCircle(ring, radius = r - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
        // North tick.
        drawLine(ring, Offset(center.x, 0f), Offset(center.x, 4.dp.toPx()), strokeWidth = 2.dp.toPx())
        if (bearingDegrees != null) {
            rotate(bearingDegrees.toFloat()) {
                val path = Path().apply {
                    moveTo(center.x, center.y - r * 0.7f)
                    lineTo(center.x + r * 0.42f, center.y + r * 0.5f)
                    lineTo(center.x, center.y + r * 0.25f)
                    lineTo(center.x - r * 0.42f, center.y + r * 0.5f)
                    close()
                }
                drawPath(path, arrow)
            }
        }
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
