package com.geotree.app.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeGuidanceTest {
    private val config = NavigationConfig(alignmentToleranceDegrees = 12.0)

    @Test
    fun `target 64, heading 30 means turn 34 right`() {
        val g = RelativeBearing.guidance(targetBearing = 64.0, deviceHeading = 30.0, config)
        assertEquals(34.0, g.degrees, 1e-9)
        assertEquals(RelativeDirection.AHEAD_RIGHT, g.direction)
        assertFalse(g.aligned)
        assertEquals("Turn 34° right", g.instruction)
    }

    @Test
    fun `turning toward the target brings the relative angle to zero`() {
        val headings = listOf(30.0, 45.0, 55.0, 60.0, 64.0)
        val relative = headings.map { RelativeBearing.relativeDegrees(64.0, it) }
        assertEquals(listOf(34.0, 19.0, 9.0, 4.0, 0.0), relative)
        val facing = RelativeBearing.guidance(64.0, 64.0, config)
        assertTrue(facing.aligned)
        assertEquals("Facing the tree", facing.instruction)
    }

    @Test
    fun `relative bearing wraps across north`() {
        assertEquals(20.0, RelativeBearing.relativeDegrees(targetBearing = 10.0, deviceHeading = 350.0), 1e-9)
        assertEquals(-20.0, RelativeBearing.relativeDegrees(targetBearing = 350.0, deviceHeading = 10.0), 1e-9)
    }

    @Test
    fun `eight relative directions`() {
        val cases = mapOf(
            0.0 to RelativeDirection.AHEAD, 22.0 to RelativeDirection.AHEAD, 45.0 to RelativeDirection.AHEAD_RIGHT,
            90.0 to RelativeDirection.RIGHT, 135.0 to RelativeDirection.BEHIND_RIGHT, 180.0 to RelativeDirection.BEHIND,
            -135.0 to RelativeDirection.BEHIND_LEFT, -90.0 to RelativeDirection.LEFT, -45.0 to RelativeDirection.AHEAD_LEFT,
            -22.0 to RelativeDirection.AHEAD,
        )
        cases.forEach { (rel, expected) -> assertEquals("relative $rel", expected, RelativeBearing.direction(rel)) }
    }

    @Test
    fun `alignment tolerance is inclusive and centralised`() {
        assertTrue(RelativeBearing.isAligned(12.0, config.alignmentToleranceDegrees))
        assertTrue(RelativeBearing.isAligned(-12.0, config.alignmentToleranceDegrees))
        assertFalse(RelativeBearing.isAligned(12.1, config.alignmentToleranceDegrees))
        assertEquals(12.0, NavigationConfig.Default.alignmentToleranceDegrees, 0.0)
    }

    @Test
    fun `tree behind and to the left`() {
        val behind = RelativeBearing.guidance(targetBearing = 0.0, deviceHeading = 180.0, config)
        assertEquals(RelativeDirection.BEHIND, behind.direction)
        assertEquals("Tree is behind you · turn 180° right", behind.instruction)
        val left = RelativeBearing.guidance(targetBearing = 0.0, deviceHeading = 90.0, config)
        assertEquals(RelativeDirection.LEFT, left.direction)
        assertEquals("Turn 90° left", left.instruction)
    }
}
