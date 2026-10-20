package com.geotree.app.core.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * On-device preprocessing must equal the server's `app.ai.features.preprocess` bit for bit
 * (Pillow bicubic resize, center crop, float32 ImageNet normalization) and apply EXIF
 * orientation exactly like Pillow's `exif_transpose`. Expected tensors come from the server code:
 * backend/scripts/make_android_ai_fixtures.py.
 */
class PreprocessParityTest {
    @Serializable
    data class Fixture(val mean: List<Float>, val std: List<Float>, val cases: List<Case>)

    @Serializable
    data class Case(
        val width: Int,
        val height: Int,
        val orientation: Int,
        val imageSize: Int,
        val resizeShortSide: Int,
        val uprightWidth: Int,
        val uprightHeight: Int,
        val tensorSha256: String,
        val first: List<Float>,
        val sum: Double,
    )

    private val fixture: Fixture = modelJson.decodeFromString(resource("leaf_model/preprocess_cases.json"))

    @Test
    fun `tensors equal the server's preprocessing bit for bit`() {
        assertEquals(19, fixture.cases.size)
        for (case in fixture.cases) {
            val label = "${case.width}x${case.height} orientation ${case.orientation} size ${case.imageSize}"
            val oriented = OrientedPixels(pattern(case.width, case.height), case.orientation)
            assertEquals("$label upright size", case.uprightWidth to case.uprightHeight, oriented.width to oriented.height)
            val tensor = LeafPreprocessor(case.imageSize, case.resizeShortSide, fixture.mean.toFloatArray(), fixture.std.toFloatArray()).tensor(oriented)
            assertEquals("$label length", 3 * case.imageSize * case.imageSize, tensor.size)
            assertEquals("$label first values", case.first, tensor.take(8))
            assertEquals("$label sum", case.sum, tensor.sumOf { it.toDouble() }, 1e-6)
            assertEquals("$label tensor sha256", case.tensorSha256, sha256(tensor))
        }
    }

    @Test
    fun `resized size uses half-to-even rounding like Python`() {
        val pre = LeafPreprocessor(336, 384, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        assertEquals(384 to 512, pre.resizedSize(3000, 4000))
        assertEquals(1600 * 384 / 1200 to 384, pre.resizedSize(1600, 1200))
        assertEquals(384 to 384, pre.resizedSize(100, 100))
        // 5 * 384 / 2 = 960 exactly; 3 * 384 / 2 = 576 exactly; check a .5 case: 769 * (384/512) = 576.75
        assertEquals(577 to 384, pre.resizedSize(769, 512))
    }

    companion object {
        /** Same integer formula as make_android_ai_fixtures.pattern. */
        fun pattern(width: Int, height: Int): PixelReader {
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                var rgb = 0
                for (c in 0 until 3) {
                    val v = (x * 73 + y * 151 + c * 29 + ((x * y) % 97) * 3 + ((x xor y) and 31) * 5) and 0xFF
                    rgb = (rgb shl 8) or v
                }
                pixels[y * width + x] = rgb
            }
            return IntArrayPixels(width, height, pixels)
        }

        fun sha256(tensor: FloatArray): String {
            val buffer = ByteBuffer.allocate(tensor.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            tensor.forEach { buffer.putFloat(it) }
            return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
        }

        fun resource(path: String): String =
            requireNotNull(PreprocessParityTest::class.java.classLoader!!.getResource(path)) { "missing test resource $path" }.readText()
    }
}
