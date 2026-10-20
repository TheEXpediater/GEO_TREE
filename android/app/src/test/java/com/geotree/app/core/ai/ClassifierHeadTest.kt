package com.geotree.app.core.ai

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device head reproduces the server's `LinearHead.predict_proba` (backend/app/ai/head.py). */
class ClassifierHeadTest {
    @Serializable
    data class Fixture(val head: ClassifierHeadSpec, val features: List<List<Float>>, val probabilities: List<List<Double>>)

    private val fixture: Fixture = modelJson.decodeFromString(PreprocessParityTest.resource("leaf_model/head_case.json"))

    private val manifest = LeafModelManifest(
        formatVersion = 1, model = "dinov2_vits14", modelDisplayName = "DINOv2 ViT-S/14",
        embeddingModel = fixture.head.embeddingModel, embeddingDimension = 384,
        preprocessing = LeafModelManifest.Preprocessing(1, 336, 384, "pillow-bicubic", "center", listOf(0.485f, 0.456f, 0.406f), listOf(0.229f, 0.224f, 0.225f), "NCHW"),
        confidenceThreshold = 0.6,
        backbone = LeafModelManifest.BackboneFile("b.onnx", "00", 1, "pixel_values", "features"),
        head = LeafModelManifest.HeadFile("h.json", "00"),
    )

    @Test
    fun `probabilities match the server head`() {
        val head = ClassifierHead(fixture.head)
        fixture.features.zip(fixture.probabilities).forEach { (features, expected) ->
            val actual = head.probabilities(features.toFloatArray())
            expected.indices.forEach { assertEquals(expected[it], actual[it], 1e-12) }
            assertEquals(1.0, actual.sum(), 1e-12)
        }
    }

    @Test
    fun `prediction keeps class order, names, version and the uncertainty threshold`() {
        val head = ClassifierHead(fixture.head)
        fixture.features.forEachIndexed { i, features ->
            val expected = fixture.probabilities[i]
            val best = expected.indices.maxBy { expected[it] }
            val prediction = predictionFrom(head.probabilities(features.toFloatArray()), fixture.head, manifest, InferenceTimings(0, 0, 0, 0))
            assertEquals(fixture.head.classes[best].classId, prediction.classId)
            assertEquals(fixture.head.classes[best].name, prediction.className)
            assertEquals(expected[best], prediction.confidence, 1e-12)
            assertEquals(expected[best] < 0.6, prediction.uncertain)
            assertEquals(4, prediction.modelVersion)
            assertEquals("dinov2_vits14", prediction.model)
            assertEquals(prediction.probabilities.sortedByDescending { it.probability }, prediction.probabilities)
        }
    }

    @Test
    fun `incompatible or malformed heads are refused`() {
        val good = fixture.head
        assertNull(good.problemWith(manifest))
        val problems = listOf(
            good.copy(embeddingModel = "dinov2_vitb14|336px|resize384-bicubic-centercrop|imagenet-norm|cls|v1"),
            good.copy(embeddingDimension = 768),
            good.copy(weights = good.weights.dropLast(1)),
            good.copy(weights = good.weights.map { it.drop(1) }),
            good.copy(bias = good.bias + 0.0),
            good.copy(mean = good.mean.toMutableList().also { it[0] = Double.NaN }),
            good.copy(classes = good.classes.reversed()),
            good.copy(classes = good.classes.map { it.copy(classId = "same") }),
            good.copy(classes = good.classes.take(1), weights = good.weights.take(1), bias = good.bias.take(1)),
            good.copy(formatVersion = 2),
            good.copy(modelVersion = 0),
        ).map { it.problemWith(manifest) }
        problems.forEach { assertTrue("expected a problem, got $it", !it.isNullOrBlank()) }
    }
}
