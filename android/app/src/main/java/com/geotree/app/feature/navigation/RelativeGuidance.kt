package com.geotree.app.feature.navigation

import com.geotree.app.core.orientation.AngleMath
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the tree is relative to the way the phone is pointing. */
enum class RelativeDirection(val label: String) {
    AHEAD("ahead"),
    AHEAD_RIGHT("ahead-right"),
    RIGHT("right"),
    BEHIND_RIGHT("behind-right"),
    BEHIND("behind"),
    BEHIND_LEFT("behind-left"),
    LEFT("left"),
    AHEAD_LEFT("ahead-left"),
}

/**
 * [degrees] = normalize(targetBearing − deviceHeading) as a signed turn in (-180, 180]:
 * positive means turn right (clockwise), 0 means the phone points at the tree.
 */
data class RelativeGuidance(
    val degrees: Double,
    val direction: RelativeDirection,
    val aligned: Boolean,
) {
    /** "Facing the tree", "Turn 34° right", "Tree is behind you · turn 160° left". */
    val instruction: String
        get() {
            val turn = abs(degrees).roundToInt()
            val side = if (degrees >= 0) "right" else "left"
            return when {
                aligned -> "Facing the tree"
                direction == RelativeDirection.BEHIND -> "Tree is behind you · turn $turn° $side"
                else -> "Turn $turn° $side"
            }
        }
}

object RelativeBearing {
    /** Signed turn from the phone heading to the target bearing, (-180, 180]. */
    fun relativeDegrees(targetBearing: Double, deviceHeading: Double): Double = AngleMath.signedDelta(deviceHeading, targetBearing)

    /** 8 sectors of 45°, centred on ahead/right/behind/left. */
    fun direction(relativeDegrees: Double): RelativeDirection {
        val d = AngleMath.normalize(relativeDegrees)
        return RelativeDirection.entries[((d + 22.5) / 45.0).toInt() % 8]
    }

    fun isAligned(relativeDegrees: Double, toleranceDegrees: Double): Boolean = abs(relativeDegrees) <= toleranceDegrees

    fun guidance(targetBearing: Double, deviceHeading: Double, config: NavigationConfig = NavigationConfig.Default): RelativeGuidance {
        val rel = relativeDegrees(targetBearing, deviceHeading)
        return RelativeGuidance(rel, direction(rel), isAligned(rel, config.alignmentToleranceDegrees))
    }
}
