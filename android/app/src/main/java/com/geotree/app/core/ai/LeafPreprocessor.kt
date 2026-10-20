package com.geotree.app.core.ai

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Decoded pixels in the photo's stored orientation, readable one row or column at a time. */
interface PixelReader {
    val width: Int
    val height: Int

    /** Fills out[0 until width] with 0xRRGGBB pixels (alpha ignored) of row [y]. */
    fun readRow(y: Int, out: IntArray)

    /** Fills out[0 until height] with 0xRRGGBB pixels of column [x]. */
    fun readColumn(x: Int, out: IntArray)
}

/** Packed 0xRRGGBB pixels; the JVM-test and small-image implementation of [PixelReader]. */
class IntArrayPixels(override val width: Int, override val height: Int, private val pixels: IntArray) : PixelReader {
    init {
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}." }
    }

    override fun readRow(y: Int, out: IntArray) {
        pixels.copyInto(out, 0, y * width, y * width + width)
    }

    override fun readColumn(x: Int, out: IntArray) {
        for (y in 0 until height) out[y] = pixels[y * width + x]
    }
}

/**
 * The upright image, applying the EXIF orientation tag exactly like Pillow's
 * `ImageOps.exif_transpose` (which the server applies before preprocessing). Pure pixel
 * permutations: no resampling, so no value changes.
 */
class OrientedPixels(private val source: PixelReader, orientation: Int) {
    private val orientation = if (orientation in 2..8) orientation else 1
    private val transposed = this.orientation >= 5
    val width: Int = if (transposed) source.height else source.width
    val height: Int = if (transposed) source.width else source.height
    private val buffer = IntArray(max(source.width, source.height))

    /** Fills out[0 until width] with row [y] of the upright image. */
    fun readRow(y: Int, out: IntArray) {
        val w = width
        when (orientation) {
            1 -> source.readRow(y, out)
            2 -> { source.readRow(y, buffer); reverseInto(buffer, w, out) } // FLIP_LEFT_RIGHT
            3 -> { source.readRow(source.height - 1 - y, buffer); reverseInto(buffer, w, out) } // ROTATE_180
            4 -> source.readRow(source.height - 1 - y, out) // FLIP_TOP_BOTTOM
            5 -> source.readColumn(y, out) // TRANSPOSE
            6 -> { source.readColumn(y, buffer); reverseInto(buffer, w, out) } // ROTATE_270 (90° clockwise)
            7 -> { source.readColumn(source.width - 1 - y, buffer); reverseInto(buffer, w, out) } // TRANSVERSE
            8 -> source.readColumn(source.width - 1 - y, out) // ROTATE_90 (90° counter-clockwise)
        }
    }

    private fun reverseInto(from: IntArray, count: Int, to: IntArray) {
        for (i in 0 until count) to[i] = from[count - 1 - i]
    }
}

/** Interleaved 8-bit RGB. */
class RgbImage(val width: Int, val height: Int, val data: ByteArray) {
    fun channel(x: Int, y: Int, c: Int): Int = data[(y * width + x) * 3 + c].toInt() and 0xFF
}

/**
 * A port of Pillow's `Image.resize(..., Image.Resampling.BICUBIC)` for 8-bit RGB
 * (libImaging/Resample.c): separable convolution, horizontal pass then vertical pass, bicubic
 * a = -0.5 with support scaled by the reduction factor (antialiasing), 22-bit fixed-point
 * coefficients and 8-bit clipping after each pass. Reproducing it bit for bit keeps on-device
 * features identical to the server's for the same decoded pixels.
 */
object PillowResample {
    private const val PRECISION_BITS = 32 - 8 - 2
    private const val BICUBIC_SUPPORT = 2.0

    private fun bicubic(value: Double): Double {
        val a = -0.5
        val x = abs(value)
        return when {
            x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1
            x < 2.0 -> (((x - 5) * x + 8) * x - 4) * a
            else -> 0.0
        }
    }

    internal class Coefficients(val ksize: Int, val bounds: IntArray, val kk: IntArray)

    /** precompute_coeffs + normalize_coeffs_8bpc for the box (0, inSize). */
    internal fun coefficients(inSize: Int, outSize: Int): Coefficients {
        val scale = inSize.toDouble() / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = BICUBIC_SUPPORT * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val pre = DoubleArray(outSize * ksize)
        val bounds = IntArray(outSize * 2)
        val ss = 1.0 / filterScale
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            val base = xx * ksize
            var ww = 0.0
            for (x in 0 until xmax) {
                val w = bicubic((x + xmin - center + 0.5) * ss)
                pre[base + x] = w
                ww += w
            }
            if (ww != 0.0) for (x in 0 until xmax) pre[base + x] /= ww
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        val one = (1 shl PRECISION_BITS).toDouble()
        val kk = IntArray(pre.size) { i ->
            val v = pre[i]
            if (v < 0) (-0.5 + v * one).toInt() else (0.5 + v * one).toInt()
        }
        return Coefficients(ksize, bounds, kk)
    }

    private fun clip8(value: Int): Int = (value shr PRECISION_BITS).coerceIn(0, 255)

    /** Resizes the upright image to [outWidth] × [outHeight]. */
    fun resize(image: OrientedPixels, outWidth: Int, outHeight: Int): RgbImage {
        val inWidth = image.width
        val inHeight = image.height
        val needHorizontal = outWidth != inWidth
        val needVertical = outHeight != inHeight
        val vertical = coefficients(inHeight, outHeight)
        // Source rows the vertical pass uses; only these are read (and resampled horizontally).
        val firstRow = if (needVertical) vertical.bounds[0] else 0
        val lastRow = if (needVertical) vertical.bounds[outHeight * 2 - 2] + vertical.bounds[outHeight * 2 - 1] else inHeight
        val rows = lastRow - firstRow
        val tempWidth = if (needHorizontal) outWidth else inWidth
        val temp = ByteArray(tempWidth * rows * 3)
        val row = IntArray(max(inWidth, inHeight))
        val horizontal = if (needHorizontal) coefficients(inWidth, outWidth) else null
        val half = 1 shl (PRECISION_BITS - 1)
        for (r in 0 until rows) {
            image.readRow(r + firstRow, row)
            var o = r * tempWidth * 3
            if (horizontal == null) {
                for (x in 0 until inWidth) {
                    val p = row[x]
                    temp[o++] = (p shr 16).toByte(); temp[o++] = (p shr 8).toByte(); temp[o++] = p.toByte()
                }
                continue
            }
            for (xx in 0 until outWidth) {
                val xmin = horizontal.bounds[xx * 2]
                val xmax = horizontal.bounds[xx * 2 + 1]
                val k = xx * horizontal.ksize
                var s0 = half
                var s1 = half
                var s2 = half
                for (x in 0 until xmax) {
                    val p = row[x + xmin]
                    val w = horizontal.kk[k + x]
                    s0 += ((p shr 16) and 0xFF) * w
                    s1 += ((p shr 8) and 0xFF) * w
                    s2 += (p and 0xFF) * w
                }
                temp[o++] = clip8(s0).toByte(); temp[o++] = clip8(s1).toByte(); temp[o++] = clip8(s2).toByte()
            }
        }
        if (!needVertical) return RgbImage(tempWidth, rows, temp)

        val out = ByteArray(tempWidth * outHeight * 3)
        var o = 0
        for (yy in 0 until outHeight) {
            val ymin = vertical.bounds[yy * 2] - firstRow
            val ymax = vertical.bounds[yy * 2 + 1]
            val k = yy * vertical.ksize
            for (xx in 0 until tempWidth) {
                var s0 = half
                var s1 = half
                var s2 = half
                for (y in 0 until ymax) {
                    val i = ((y + ymin) * tempWidth + xx) * 3
                    val w = vertical.kk[k + y]
                    s0 += (temp[i].toInt() and 0xFF) * w
                    s1 += (temp[i + 1].toInt() and 0xFF) * w
                    s2 += (temp[i + 2].toInt() and 0xFF) * w
                }
                out[o++] = clip8(s0).toByte(); out[o++] = clip8(s1).toByte(); out[o++] = clip8(s2).toByte()
            }
        }
        return RgbImage(tempWidth, outHeight, out)
    }
}

/**
 * The server's `app.ai.features.preprocess`: resize the short side to [resizeShortSide] (Pillow
 * bicubic), center-crop [imageSize]², scale to [0, 1] and apply ImageNet normalization, all in
 * float32 like NumPy. Returns a CHW tensor of 3 × imageSize × imageSize values.
 */
class LeafPreprocessor(
    private val imageSize: Int,
    private val resizeShortSide: Int,
    private val mean: FloatArray,
    private val std: FloatArray,
) {
    constructor(spec: LeafModelManifest.Preprocessing) :
        this(spec.imageSize, spec.resizeShortSide, spec.mean.toFloatArray(), spec.std.toFloatArray())

    /** The size Pillow resizes to before cropping (Python's round() is half-to-even, like rint). */
    fun resizedSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = resizeShortSide.toDouble() / min(width, height)
        return max(resizeShortSide, Math.rint(width * scale).toInt()) to max(resizeShortSide, Math.rint(height * scale).toInt())
    }

    fun resize(image: OrientedPixels): RgbImage {
        val (w, h) = resizedSize(image.width, image.height)
        return PillowResample.resize(image, w, h)
    }

    fun normalize(resized: RgbImage): FloatArray {
        val left = (resized.width - imageSize) / 2
        val top = (resized.height - imageSize) / 2
        val plane = imageSize * imageSize
        val tensor = FloatArray(3 * plane)
        for (y in 0 until imageSize) {
            for (x in 0 until imageSize) {
                for (c in 0 until 3) {
                    val value = resized.channel(left + x, top + y, c).toFloat() / 255f
                    tensor[c * plane + y * imageSize + x] = (value - mean[c]) / std[c]
                }
            }
        }
        return tensor
    }

    fun tensor(image: OrientedPixels): FloatArray = normalize(resize(image))
}
