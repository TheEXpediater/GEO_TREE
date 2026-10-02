package com.geotree.app.feature.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geotree.app.core.design.GeoColors
import kotlin.math.roundToInt

/**
 * North-up navigation compass.
 *
 * - Grey corridor: where the user SHOULD face. Centre line = exact bearing to the tree; the two
 *   faint side lines = ± [toleranceDegrees]. These are orientation aids, not roads.
 * - Live arrow: where the phone IS facing (compass heading). Forest green, turning success green
 *   once it lies inside the corridor. Absent when there is no compass reading; nothing is invented.
 *
 * Turn the phone until the live arrow sits on the grey centre line: you are facing the tree.
 */
@Composable
fun NavigationCompass(
    targetBearing: Double?,
    heading: Double?,
    aligned: Boolean,
    toleranceDegrees: Double,
    modifier: Modifier = Modifier,
    showNorthLabel: Boolean = true,
) {
    val dial = MaterialTheme.colorScheme.surfaceContainerHigh
    val ring = MaterialTheme.colorScheme.outline
    val guide = MaterialTheme.colorScheme.onSurfaceVariant
    val northColor = GeoColors.Clay
    val liveColor = if (aligned) GeoColors.Success else GeoColors.Forest
    val textMeasurer = rememberTextMeasurer()
    val description = buildString {
        append(targetBearing?.let { "Tree bearing ${it.roundToInt()} degrees ${NavigationMath.cardinal(it)}. " } ?: "No bearing yet. ")
        append(heading?.let { "Phone facing ${it.roundToInt()} degrees. " } ?: "No compass reading. ")
        if (aligned) append("Facing the tree.")
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val r = size.minDimension / 2f
        drawCircle(dial, radius = r)
        drawCircle(ring, radius = r - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
        drawTicks(ring, r)
        if (showNorthLabel && r > 24.dp.toPx()) {
            val label = textMeasurer.measure("N", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = northColor))
            drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - r + 7.dp.toPx()))
        } else {
            drawLine(northColor, Offset(center.x, center.y - r), Offset(center.x, center.y - r + 6.dp.toPx()), 2.dp.toPx())
        }

        if (targetBearing != null) {
            val t = targetBearing.toFloat()
            val tol = toleranceDegrees.toFloat()
            // Corridor between the side guides (Compose arcs start at 3 o'clock, so -90° = north).
            val inset = r * 0.12f
            drawArc(
                color = guide.copy(alpha = 0.10f),
                startAngle = t - 90f - tol,
                sweepAngle = 2 * tol,
                useCenter = true,
                topLeft = Offset(center.x - r + inset, center.y - r + inset),
                size = Size(2 * (r - inset), 2 * (r - inset)),
            )
            guideLine(guide.copy(alpha = 0.22f), t - tol, r, 1.2.dp.toPx())
            guideLine(guide.copy(alpha = 0.22f), t + tol, r, 1.2.dp.toPx())
            guideLine(guide.copy(alpha = 0.55f), t, r, 2.dp.toPx())
            // Small grey target notch on the rim.
            rotate(t) {
                val notch = Path().apply {
                    moveTo(center.x, center.y - r + 2.dp.toPx())
                    lineTo(center.x - r * 0.09f, center.y - r * 0.80f)
                    lineTo(center.x + r * 0.09f, center.y - r * 0.80f)
                    close()
                }
                drawPath(notch, guide.copy(alpha = 0.55f))
            }
        }

        if (heading != null) {
            rotate(heading.toFloat()) {
                val arrow = Path().apply {
                    moveTo(center.x, center.y - r * 0.74f)
                    lineTo(center.x + r * 0.20f, center.y - r * 0.38f)
                    lineTo(center.x + r * 0.07f, center.y - r * 0.40f)
                    lineTo(center.x + r * 0.07f, center.y + r * 0.22f)
                    lineTo(center.x - r * 0.07f, center.y + r * 0.22f)
                    lineTo(center.x - r * 0.07f, center.y - r * 0.40f)
                    lineTo(center.x - r * 0.20f, center.y - r * 0.38f)
                    close()
                }
                drawPath(arrow, liveColor)
            }
        }
        drawCircle(if (heading != null) liveColor else guide.copy(alpha = 0.5f), radius = r * 0.08f)
    }
}

private fun DrawScope.drawTicks(color: Color, r: Float) {
    for (deg in 0 until 360 step 30) {
        val major = deg % 90 == 0
        rotate(deg.toFloat()) {
            drawLine(
                color.copy(alpha = if (major) 0.9f else 0.5f),
                Offset(center.x, center.y - r + 2.dp.toPx()),
                Offset(center.x, center.y - r + (if (major) 6 else 4).dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

private fun DrawScope.guideLine(color: Color, bearing: Float, r: Float, width: Float) {
    rotate(bearing) {
        drawLine(color, Offset(center.x, center.y - r * 0.28f), Offset(center.x, center.y - r * 0.86f), strokeWidth = width, cap = StrokeCap.Round)
    }
}
