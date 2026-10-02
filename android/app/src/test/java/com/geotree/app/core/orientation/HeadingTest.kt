package com.geotree.app.core.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingTest {
    @Test
    fun `angles normalise into 0 to 360`() {
        assertEquals(0.0, AngleMath.normalize(360.0), 1e-9)
        assertEquals(359.0, AngleMath.normalize(-1.0), 1e-9)
        assertEquals(10.0, AngleMath.normalize(730.0), 1e-9)
        assertEquals(270.0, AngleMath.normalize(-450.0), 1e-9)
    }

    @Test
    fun `signed delta takes the short way across north`() {
        assertEquals(2.0, AngleMath.signedDelta(359.0, 1.0), 1e-9)
        assertEquals(-2.0, AngleMath.signedDelta(1.0, 359.0), 1e-9)
        assertEquals(180.0, AngleMath.signedDelta(0.0, 180.0), 1e-9)
        assertEquals(-90.0, AngleMath.signedDelta(90.0, 0.0), 1e-9)
    }

    @Test
    fun `interpolating 359 and 1 gives north, not south`() {
        assertEquals(0.0, AngleMath.interpolate(359.0, 1.0, 0.5), 1e-9)
        assertEquals(359.5, AngleMath.interpolate(359.0, 1.0, 0.25), 1e-9)
    }

    @Test
    fun `smoothing near north never swings through 180`() {
        val smoother = HeadingSmoother(alpha = 0.3)
        var last = smoother.update(358.0)
        repeat(20) { i ->
            last = smoother.update(if (i % 2 == 0) 2.0 else 358.0)
            val offNorth = Math.abs(AngleMath.signedDelta(0.0, last))
            assertTrue("jitter around north stays near north, got $last", offNorth < 3.0)
        }
    }

    @Test
    fun `smoothing follows a real turn quickly`() {
        val smoother = HeadingSmoother(alpha = 0.3)
        smoother.update(0.0)
        var h = 0.0
        repeat(10) { h = smoother.update(90.0) } // ~10 sensor samples, well under a second
        assertTrue("arrow reaches the new heading, got $h", h > 85.0)
    }

    @Test
    fun `declination turns magnetic into true heading`() {
        val processor = HeadingProcessor(HeadingSmoother(alpha = 1.0))
        val withDeclination = processor.process(RawHeading(100.0, HeadingAccuracy.HIGH), declinationDegrees = -1.5)
        assertEquals(98.5, withDeclination.degrees, 1e-9)
        assertEquals(NorthReference.TRUE, withDeclination.reference)

        val noFix = processor.process(RawHeading(100.0, HeadingAccuracy.LOW), declinationDegrees = null)
        assertEquals(100.0, noFix.degrees, 1e-9)
        assertEquals("never pretends to be true north", NorthReference.MAGNETIC, noFix.reference)
        assertEquals(HeadingAccuracy.LOW, noFix.accuracy)

        val wrap = processor.process(RawHeading(0.5, HeadingAccuracy.HIGH), declinationDegrees = -1.5)
        assertEquals(359.0, wrap.degrees, 1e-9)
    }

    @Test
    fun `heading from rotation matrix, phone flat`() {
        // Identity: top edge points north.
        assertEquals(0.0, headingFromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))!!, 1e-6)
        // Rotated 90° clockwise: top edge (device Y) points east.
        assertEquals(90.0, headingFromRotationMatrix(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))!!, 1e-6)
        // Rotated 225°: top edge (device Y column) points south-west, right edge (X) north-west.
        val s = Math.sqrt(0.5).toFloat()
        assertEquals(225.0, headingFromRotationMatrix(floatArrayOf(-s, -s, 0f, s, -s, 0f, 0f, 0f, 1f))!!, 1e-4)
    }

    @Test
    fun `heading from rotation matrix, phone upright uses the camera direction`() {
        // Device Y = up, device Z (screen) = south, so the back camera looks north.
        val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0.0, headingFromRotationMatrix(upright)!!, 1e-6)
    }

    @Test
    fun `degenerate matrix gives no heading`() {
        assertNull(headingFromRotationMatrix(FloatArray(9)))
    }
}
