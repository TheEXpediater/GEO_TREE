package com.geotree.app.core.ai

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geotree.app.appContainer
import com.geotree.app.data.repository.AiResult
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device parity harness (not a unit test): runs the app's real pipeline (BitmapFactory decode,
 * EXIF orientation, Pillow-exact preprocessing, ONNX Runtime backbone, classifier head) over the
 * photos in <app files>/parity/inputs and writes parity/android_results.json for comparison with
 * backend/scripts/mobile_parity_reference.py. See README "On-device leaf assessment".
 *
 *   adb push <inputs> /data/local/tmp/parity_inputs
 *   adb shell run-as com.geotree.app sh -c "mkdir -p files/parity/inputs && cp -r /data/local/tmp/parity_inputs/. files/parity/inputs"
 *   adb shell am instrument -w -e class com.geotree.app.core.ai.LeafModelParityTest com.geotree.app.test/androidx.test.runner.AndroidJUnitRunner
 *   adb exec-out run-as com.geotree.app cat files/parity/android_results.json > android_results.json
 *
 * Skipped when no inputs were pushed. No network is used.
 */
@RunWith(AndroidJUnit4::class)
class LeafModelParityTest {
    @Serializable
    data class Row(
        val name: String,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val rgbSha256: String,
        val tensorSha256: String,
        val features: List<Float>,
        val probabilities: List<Double>,
        val predictedClassId: String,
        val decodeMs: Long,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val classifyCallMs: Long,
        val classifyClassId: String?,
    )

    @Serializable
    data class Report(
        val device: String,
        val abi: String,
        val sdk: Int,
        val modelVersion: Int,
        val headSource: String,
        val sessionLoadMs: Long,
        val nativeHeapMbAfter: Long,
        val javaHeapMbAfter: Long,
        val rows: List<Row>,
    )

    @Test
    fun runParity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "parity")
        val inputs = File(root, "inputs").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no parity inputs pushed to ${root.path}/inputs", inputs.isNotEmpty())

        val container = context.appContainer
        val classifier = container.leafClassifier as OnDeviceLeafClassifier
        val model = container.leafModelStore.prepare()
        val loadStart = SystemClock.elapsedRealtime()
        val session = classifier.session(model)
        val sessionLoadMs = SystemClock.elapsedRealtime() - loadStart
        val head = ClassifierHead(model.head)
        val preprocessor = LeafPreprocessor(model.manifest.preprocessing)
        val size = model.manifest.preprocessing.imageSize

        val rows = inputs.map { file ->
            val t0 = SystemClock.elapsedRealtime()
            val orientation = OnDeviceLeafClassifier.exifOrientation(file)
            val oriented = OrientedPixels(OnDeviceLeafClassifier.decodePhoto(file), orientation)
            val rgbSha = rgbSha256(oriented)
            val t1 = SystemClock.elapsedRealtime()
            val tensor = preprocessor.tensor(oriented)
            val t2 = SystemClock.elapsedRealtime()
            val features = classifier.extract(session, model.manifest, tensor, size)
            val probabilities = head.probabilities(features)
            val t3 = SystemClock.elapsedRealtime()
            // The production entry point too (what LeafAssessmentRepository calls).
            val call = classifier.classify(file)
            val t4 = SystemClock.elapsedRealtime()
            val best = probabilities.indices.maxBy { probabilities[it] }
            Row(
                name = file.name, width = oriented.width, height = oriented.height, orientation = orientation,
                rgbSha256 = rgbSha, tensorSha256 = tensorSha256(tensor), features = features.toList(),
                probabilities = probabilities.toList(), predictedClassId = model.head.classes[best].classId,
                decodeMs = t1 - t0, preprocessMs = t2 - t1, inferenceMs = t3 - t2, classifyCallMs = t4 - t3,
                classifyClassId = (call as? AiResult.Ok)?.value?.classId,
            )
        }
        val runtime = Runtime.getRuntime()
        val report = Report(
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            abi = android.os.Build.SUPPORTED_ABIS.first(),
            sdk = android.os.Build.VERSION.SDK_INT,
            modelVersion = model.head.modelVersion,
            headSource = model.headSource.name,
            sessionLoadMs = sessionLoadMs,
            nativeHeapMbAfter = Debug.getNativeHeapAllocatedSize() / 1_048_576,
            javaHeapMbAfter = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576,
            rows = rows,
        )
        File(root, "android_results.json").writeText(Json.encodeToString(report))
        assertTrue(rows.all { it.classifyClassId == it.predictedClassId })
    }

    private fun rgbSha256(image: OrientedPixels): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(image.width)
        val bytes = ByteArray(image.width * 3)
        for (y in 0 until image.height) {
            image.readRow(y, row)
            for (x in 0 until image.width) {
                bytes[x * 3] = (row[x] shr 16).toByte(); bytes[x * 3 + 1] = (row[x] shr 8).toByte(); bytes[x * 3 + 2] = row[x].toByte()
            }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun tensorSha256(tensor: FloatArray): String {
        val buffer = java.nio.ByteBuffer.allocate(tensor.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        tensor.forEach { buffer.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }
}
