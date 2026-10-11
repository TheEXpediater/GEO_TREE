package com.geotree.app.feature.leafassessment

import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.design.PillTone
import com.geotree.app.data.repository.LeafAssessmentRepository
import com.geotree.app.testing.FakeAiServer
import com.geotree.app.testing.FakeLeafAssessmentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The result page / read-only history entry, and the display rules shared with Tree Detail. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentDetailTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val server = FakeAiServer()
    private val dao = FakeLeafAssessmentDao()
    private var now = 1_790_000_000_000

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.repository() = LeafAssessmentRepository(dao, { server }, scope = this, clock = { now += 1_000; now })

    private fun TestScope.detail(repo: LeafAssessmentRepository, id: String) =
        AssessmentDetailViewModel(id, repo) { flowOf("GEO-TAM-013") }.also { vm ->
            backgroundScope.launch { vm.load.collect {} }
            advanceUntilIdle()
        }

    private fun photo(name: String) = temp.newFile(name).apply { writeBytes(name.toByteArray()) }.absolutePath

    @Test
    fun `result page follows the scan from processing to its stored result`() = runTest(dispatcher) {
        server.assessment = FakeAiServer.result("Spotting", 0.94, version = 2)
        val repo = repository()
        val scan = repo.start("tree-1", photo("a.jpg"))
        val vm = AssessmentDetailViewModel(scan.id, repo) { flowOf("GEO-TAM-013") }
        backgroundScope.launch { vm.load.collect {} }

        dispatcher.scheduler.runCurrent()
        // The record exists in Room before any AI result: it is visible right away.
        assertTrue((vm.load.value as AssessmentLoad.Loaded).record.status in setOf(AssessmentStatus.PROCESSING, AssessmentStatus.COMPLETED))

        advanceUntilIdle()
        val loaded = vm.load.value as AssessmentLoad.Loaded
        assertEquals(AssessmentStatus.COMPLETED, loaded.record.status)
        assertEquals("GEO-TAM-013", loaded.treeCode)
        assertEquals("Spotting", conditionText(loaded.record))
        assertEquals("DINOv2 ViT-S/14 · v2", modelText(loaded.record))
    }

    @Test
    fun `opening an older history entry shows its original details`() = runTest(dispatcher) {
        val repo = repository()
        server.assessment = FakeAiServer.result("Healthy", 0.91)
        val first = repo.start("tree-1", photo("1.jpg")); advanceUntilIdle()
        server.assessment = FakeAiServer.result("Mold", 0.83, version = 3)
        repo.start("tree-1", photo("2.jpg")); advanceUntilIdle()

        val record = (detail(repo, first.id).load.value as AssessmentLoad.Loaded).record
        assertEquals("Healthy", record.className)
        assertEquals(0.91f, record.confidence!!, 1e-6f)
        assertEquals(7, record.modelVersion)
    }

    @Test
    fun `unknown id is reported as missing`() = runTest(dispatcher) {
        assertEquals(AssessmentLoad.Missing, detail(repository(), "nope").load.value)
    }

    @Test
    fun `assess again from a failed entry opens a new scan and keeps the failed one`() = runTest(dispatcher) {
        val repo = repository()
        server.unreachable = true
        val failed = repo.start("tree-1", photo("f.jpg")); advanceUntilIdle()
        val vm = detail(repo, failed.id)
        assertTrue(canReassess((vm.load.value as AssessmentLoad.Loaded).record))

        server.unreachable = false
        server.assessment = FakeAiServer.result()
        vm.reassess()
        advanceUntilIdle()

        val newId = vm.newAssessmentId.value!!
        assertNotEquals(failed.id, newId)
        assertEquals(AssessmentStatus.FAILED, dao.rows.value.getValue(failed.id).status)
        assertEquals(AssessmentStatus.COMPLETED, dao.rows.value.getValue(newId).status)
        vm.navigationHandled()
        assertEquals(null, vm.newAssessmentId.value)
    }

    private fun record(status: AssessmentStatus, className: String? = "Spotting", confidence: Float? = 0.94f, sync: SyncStatus = SyncStatus.PENDING) =
        LeafAssessmentEntity(
            id = "x", treeId = "t", imageLocalPath = null, remoteImagePath = null, classId = null, className = className,
            confidence = confidence, modelName = if (status.hasResult) "dinov2_vits14" else null,
            modelDisplayName = if (status.hasResult) "DINOv2 ViT-S/14" else null, modelVersion = if (status.hasResult) 2 else null,
            status = status, errorCode = null, errorMessage = null, createdAt = 0, completedAt = null, updatedAt = 0,
            syncStatus = sync, serverVersion = null, lastSyncError = null,
        )

    @Test
    fun `display rules never invent a class or model`() {
        assertEquals("Spotting · 94%", historyTitle(record(AssessmentStatus.COMPLETED)))
        assertEquals("Uncertain · Healthy 48%", historyTitle(record(AssessmentStatus.UNCERTAIN, "Healthy", 0.48f)))
        assertEquals("Uncertain · closest: Healthy", conditionText(record(AssessmentStatus.UNCERTAIN, "Healthy", 0.48f)))
        assertEquals("Not assessed", historyTitle(record(AssessmentStatus.FAILED, null, null)))
        assertEquals("Assessment unavailable", conditionText(record(AssessmentStatus.FAILED, null, null)))
        assertEquals("Not processed by the model", modelText(record(AssessmentStatus.FAILED, null, null)))
        assertEquals("Processing…", historyTitle(record(AssessmentStatus.PROCESSING, null, null)))
        assertEquals("Completed" to PillTone.Good, statusLabel(AssessmentStatus.COMPLETED))
        assertEquals("Failed" to PillTone.Error, statusLabel(AssessmentStatus.FAILED))
        assertEquals("1 assessment", assessmentCountText(1))
        assertEquals("3 assessments", assessmentCountText(3))
    }

    @Test
    fun `AI result and sync state are shown separately`() {
        assertEquals("Sync pending", syncLabel(record(AssessmentStatus.COMPLETED)))
        assertEquals("Synced", syncLabel(record(AssessmentStatus.COMPLETED, sync = SyncStatus.SYNCED)))
        assertEquals("Sync pending (last attempt failed)", syncLabel(record(AssessmentStatus.FAILED, sync = SyncStatus.FAILED)))
        assertEquals("Not synced (in progress)", syncLabel(record(AssessmentStatus.PROCESSING)))
        assertFalse(canReassess(record(AssessmentStatus.PROCESSING).copy(imageLocalPath = "x")) { true })
        assertTrue(canReassess(record(AssessmentStatus.FAILED).copy(imageLocalPath = "x")) { true })
        assertFalse(canReassess(record(AssessmentStatus.FAILED).copy(imageLocalPath = "x")) { false })
    }
}
