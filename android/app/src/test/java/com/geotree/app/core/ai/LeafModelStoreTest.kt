package com.geotree.app.core.ai

import com.geotree.app.testing.FakeAiServer
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Model integrity (Test E): a missing, corrupt or incompatible model never produces a result,
 * and a failed or damaged update never removes the last known-good model.
 */
class LeafModelStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val headCase: ClassifierHeadTest.Fixture = modelJson.decodeFromString(PreprocessParityTest.resource("leaf_model/head_case.json"))
    private val bundledHead = modelJson.encodeToString(headCase.head.copy(modelVersion = 1)).encodeToByteArray()
    private val backbone = ByteArray(4096) { (it * 31).toByte() }
    private val assets = mutableMapOf<String, ByteArray>()

    private fun manifest(backboneBytes: ByteArray = backbone, head: ByteArray = bundledHead) = LeafModelManifest(
        formatVersion = 1, model = "dinov2_vits14", modelDisplayName = "DINOv2 ViT-S/14",
        embeddingModel = headCase.head.embeddingModel, embeddingDimension = 384,
        preprocessing = LeafModelManifest.Preprocessing(1, 336, 384, "pillow-bicubic", "center", listOf(0.485f, 0.456f, 0.406f), listOf(0.229f, 0.224f, 0.225f), "NCHW"),
        confidenceThreshold = 0.6,
        backbone = LeafModelManifest.BackboneFile("backbone.onnx", LeafModelStore.sha256(backboneBytes), backboneBytes.size.toLong(), "pixel_values", "features"),
        head = LeafModelManifest.HeadFile("classifier_head.json", LeafModelStore.sha256(head)),
    )

    private fun installAssets(manifest: LeafModelManifest = manifest(), backboneBytes: ByteArray = backbone, head: ByteArray = bundledHead) {
        assets["leaf_model/manifest.json"] = modelJson.encodeToString(manifest).encodeToByteArray()
        assets["leaf_model/backbone.onnx"] = backboneBytes
        assets["leaf_model/classifier_head.json"] = head
    }

    private val directory by lazy { temp.newFolder("leaf_model") }

    private fun store() = LeafModelStore({ path -> assets[path]?.inputStream() ?: throw FileNotFoundException(path) }, directory)

    private fun head(version: Int, transform: (ClassifierHeadSpec) -> ClassifierHeadSpec = { it }) =
        modelJson.encodeToString(transform(headCase.head.copy(modelVersion = version))).encodeToByteArray()

    @Test
    fun `fresh install copies and verifies the bundled model`() {
        installAssets()
        val model = store().prepare()
        assertEquals(HeadSource.BUNDLED, model.headSource)
        assertEquals(1, model.head.modelVersion)
        assertTrue(model.backboneFile.readBytes().contentEquals(backbone))
        assertEquals(listOf("Healthy", "Spotting", "Mold"), model.info().classes)
    }

    @Test
    fun `a corrupted installed backbone is replaced from the APK`() {
        installAssets()
        val file = store().prepare().backboneFile
        file.writeBytes(ByteArray(backbone.size)) // same size, wrong content
        val repaired = store().prepare() // a new process verifies the checksum again
        assertTrue(repaired.backboneFile.readBytes().contentEquals(backbone))
    }

    @Test
    fun `a damaged or missing bundled model is an explicit error, never a result`() {
        installAssets(backboneBytes = backbone.copyOf().also { it[0] = 99 }) // manifest checksum no longer matches
        assertThrows(LeafModelUnavailable::class.java) { store().prepare() }

        assets.clear()
        installAssets(head = bundledHead.copyOf(bundledHead.size - 10), manifest = manifest()) // truncated head
        assertThrows(LeafModelUnavailable::class.java) { store().prepare() }

        assets.clear()
        assertThrows(LeafModelUnavailable::class.java) { store().prepare() } // no model in the build at all
    }

    @Test
    fun `a bundled head for another backbone is refused`() {
        val foreign = head(1) { it.copy(embeddingModel = "dinov2_vitb14|336px|other") }
        installAssets(manifest = manifest(head = foreign), head = foreign)
        assertThrows(LeafModelUnavailable::class.java) { store().prepare() }
    }

    @Test
    fun `a valid update is activated and survives a restart`() {
        installAssets()
        assertEquals(HeadInstallResult.Installed(2), store().installHead(head(2)))
        val model = store().prepare()
        assertEquals(HeadSource.DOWNLOADED, model.headSource)
        assertEquals(2, model.head.modelVersion)
        assertEquals(HeadInstallResult.AlreadyActive, store().installHead(head(2)))
    }

    @Test
    fun `incompatible, malformed or truncated updates are rejected and the active model is kept`() {
        installAssets()
        val store = store()
        store.installHead(head(2))
        val rejected = listOf(
            head(3) { it.copy(embeddingModel = "dinov2_vitb14|other") },
            head(3) { it.copy(weights = it.weights.dropLast(1)) },
            head(3).copyOf(200),
            "not json".encodeToByteArray(),
        ).map { store.installHead(it) }
        rejected.forEach { assertTrue("$it", it is HeadInstallResult.Rejected) }
        assertEquals(2, store().prepare().head.modelVersion)
    }

    @Test
    fun `a damaged downloaded head falls back to the previous one, then to the bundled one`() {
        installAssets()
        val store = store()
        store.installHead(head(2))
        store.installHead(head(3))
        val heads = File(directory, "heads").listFiles { f -> f.name.startsWith("head-") }!!
        assertEquals(2, heads.size) // the new head and the last known-good one

        val v3 = heads.single { modelJson.decodeFromString<ClassifierHeadSpec>(it.readText()).modelVersion == 3 }
        v3.writeText("{ damaged")
        assertEquals(2, store().prepare().head.modelVersion)

        heads.forEach { it.delete() }
        val fallback = store().prepare()
        assertEquals(HeadSource.BUNDLED, fallback.headSource)
        assertEquals(1, fallback.head.modelVersion)
    }

    @Test
    fun `reset returns to the bundled head`() {
        installAssets()
        val store = store()
        store.installHead(head(5))
        store.resetToBundled()
        assertEquals(1, store.prepare().head.modelVersion)
        assertFalse(File(directory, "heads/active.json").exists())
    }

    @Test
    fun `updater installs a newer server head and skips when current or unavailable`() = runTest {
        installAssets()
        val store = store()
        val server = FakeAiServer()
        val updater = LeafModelUpdater(store) { server }

        assertTrue(updater.refresh() is ModelUpdateOutcome.Skipped) // no active model on the server
        server.activeModelVersion = 1
        server.activeClasses = listOf("Healthy", "Spotting", "Mold")
        assertEquals(ModelUpdateOutcome.UpToDate, updater.refresh())
        assertEquals(0, server.headCalls)

        server.activeModelVersion = 2
        assertTrue(updater.refresh() is ModelUpdateOutcome.Skipped) // 404: server without the head endpoint
        assertEquals(1, store.prepare().head.modelVersion)

        server.headBytes = head(2) { it.copy(embeddingModel = "dinov2_vitb14|other") }
        assertTrue(updater.refresh() is ModelUpdateOutcome.Rejected)
        assertEquals(1, store.prepare().head.modelVersion)

        server.headBytes = head(2)
        assertEquals(ModelUpdateOutcome.Updated(2), updater.refresh())
        assertEquals(2, store.prepare().head.modelVersion)

        server.unreachable = true
        assertTrue(updater.refresh() is ModelUpdateOutcome.Skipped)
        assertEquals(2, store.prepare().head.modelVersion)
    }
}
