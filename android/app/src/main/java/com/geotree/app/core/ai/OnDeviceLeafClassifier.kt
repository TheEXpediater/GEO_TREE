package com.geotree.app.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.geotree.app.data.repository.AiResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * DINOv2 ViT-S/14 + the trained classifier head, run on the phone with ONNX Runtime.
 *
 * photo file → decode (raw pixels, EXIF orientation) → Pillow-exact resize/crop/normalize →
 * ONNX backbone → 384-d feature → head → class + confidence. Off the main thread, one inference
 * at a time; the ONNX session is created on first use and kept for the process.
 */
class OnDeviceLeafClassifier(
    private val store: LeafModelStore,
    private val decode: (File) -> PixelReader = ::decodePhoto,
) : LeafClassifier {
    private val mutex = Mutex()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var sessionFile: File? = null

    override suspend fun status(): AiResult<LeafModelInfo> = withContext(Dispatchers.IO) {
        try {
            AiResult.Ok(store.prepare().info())
        } catch (e: LeafModelUnavailable) {
            LeafInferenceErrors.modelUnavailable(e.message ?: "The on-device leaf model is unavailable.")
        }
    }

    override suspend fun classify(image: File): AiResult<LeafPrediction> = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                classifyLocked(image)
            } catch (e: LeafModelUnavailable) {
                LeafInferenceErrors.modelUnavailable(e.message ?: "The on-device leaf model is unavailable.")
            } catch (e: OutOfMemoryError) {
                LeafInferenceErrors.outOfMemory()
            } catch (e: UnreadableImage) {
                LeafInferenceErrors.unreadable()
            } catch (e: ImageTooLarge) {
                LeafInferenceErrors.tooLarge()
            } catch (e: Exception) {
                Log.e(TAG, "On-device inference failed", e)
                LeafInferenceErrors.inferenceFailed(e.javaClass.simpleName)
            }
        }
    }

    private fun classifyLocked(image: File): AiResult<LeafPrediction> {
        if (!image.isFile) return LeafInferenceErrors.imageMissing()
        val started = SystemClock.elapsedRealtime()
        val model = store.prepare()
        val session = session(model)
        val loaded = SystemClock.elapsedRealtime()

        val pixels = decode(image)
        val oriented = OrientedPixels(pixels, exifOrientation(image))
        if (minOf(oriented.width, oriented.height) < MIN_DIMENSION) return LeafInferenceErrors.tooSmall()
        val decoded = SystemClock.elapsedRealtime()

        val size = model.manifest.preprocessing.imageSize
        val tensor = LeafPreprocessor(model.manifest.preprocessing).tensor(oriented)
        val preprocessed = SystemClock.elapsedRealtime()

        val features = extract(session, model.manifest, tensor, size)
        val probabilities = ClassifierHead(model.head).probabilities(features)
        val finished = SystemClock.elapsedRealtime()

        val timings = InferenceTimings(loaded - started, decoded - loaded, preprocessed - decoded, finished - preprocessed)
        val prediction = predictionFrom(probabilities, model.head, model.manifest, timings)
        Log.i(
            TAG,
            "On-device assessment: ${prediction.className} ${"%.4f".format(prediction.confidence)} " +
                "(model v${prediction.modelVersion} ${model.headSource}; ${oriented.width}x${oriented.height}; " +
                "load ${timings.loadMs} ms, decode ${timings.decodeMs} ms, preprocess ${timings.preprocessMs} ms, " +
                "inference ${timings.inferenceMs} ms; native heap ${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} MB)",
        )
        return AiResult.Ok(prediction)
    }

    /** The 384-d CLS feature for a preprocessed CHW tensor. Exposed for the parity harness. */
    fun extract(session: OrtSession, manifest: LeafModelManifest, tensor: FloatArray, size: Int): FloatArray {
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            session.run(mapOf(manifest.backbone.input to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = (result.get(manifest.backbone.output).get() as OnnxTensor).floatBuffer
                val features = FloatArray(output.remaining())
                output.get(features)
                if (features.size != manifest.embeddingDimension) error("Backbone returned ${features.size} features")
                return features
            }
        }
    }

    /** Creates (once) the ONNX session for the verified backbone file. */
    fun session(model: PreparedLeafModel): OrtSession {
        session?.takeIf { sessionFile == model.backboneFile }?.let { return it }
        session?.close()
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val started = SystemClock.elapsedRealtime()
        return env.createSession(model.backboneFile.absolutePath, options).also {
            session = it
            sessionFile = model.backboneFile
            Log.i(TAG, "Loaded ${model.manifest.modelDisplayName} on-device model in ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    class UnreadableImage : Exception()
    class ImageTooLarge : Exception()

    companion object {
        const val TAG = "LeafModel"
        private const val MIN_DIMENSION = 32
        /** Decoding beyond this would not fit phone memory (a 12 MP photo is 12 M pixels). */
        private const val MAX_PIXELS = 50_000_000L

        fun exifOrientation(image: File): Int =
            runCatching { ExifInterface(image.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        /**
         * Decodes the stored pixel values like Pillow does: no color-space conversion (getPixels
         * would convert e.g. Display P3 to sRGB, Pillow does not), no EXIF rotation (applied
         * separately by [OrientedPixels]), no scaling.
         */
        fun decodePhoto(image: File): PixelReader {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadableImage()
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) throw ImageTooLarge()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inMutable = false
            }
            val bitmap = BitmapFactory.decodeFile(image.absolutePath, options) ?: throw UnreadableImage()
            try {
                val rgba = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val buffer = ByteBuffer.allocate(rgba.rowBytes * rgba.height)
                rgba.copyPixelsToBuffer(buffer)
                if (rgba !== bitmap) rgba.recycle()
                return RgbaBytes(bitmap.width, bitmap.height, bitmap.rowBytes, buffer.array())
            } finally {
                bitmap.recycle()
            }
        }
    }
}

/** ARGB_8888 bitmap memory (R, G, B, A bytes per pixel). Opaque photos are not premultiplied. */
private class RgbaBytes(override val width: Int, override val height: Int, private val rowBytes: Int, private val bytes: ByteArray) : PixelReader {
    private fun pixel(x: Int, y: Int): Int {
        val i = y * rowBytes + x * 4
        return ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
    }

    override fun readRow(y: Int, out: IntArray) {
        for (x in 0 until width) out[x] = pixel(x, y)
    }

    override fun readColumn(x: Int, out: IntArray) {
        for (y in 0 until height) out[y] = pixel(x, y)
    }
}
