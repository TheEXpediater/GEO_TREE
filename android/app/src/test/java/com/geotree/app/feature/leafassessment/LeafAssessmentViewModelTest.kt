package com.geotree.app.feature.leafassessment

import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.data.repository.LeafAssessmentRepository
import com.geotree.app.testing.FakeAiServer
import com.geotree.app.testing.FakeLeafAssessmentDao
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Capture → review → Run Assessment hands a recorded scan id to the result page. */
@OptIn(ExperimentalCoroutinesApi::class)
class LeafAssessmentViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val server = FakeAiServer()
    private val dao = FakeLeafAssessmentDao()
    private val deleted = mutableListOf<String>()
    private var now = 1_790_000_000_000

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun photo(name: String = "leaf.jpg"): File = temp.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun TestScope.repository() = LeafAssessmentRepository(dao, { server }, scope = this, clock = { now++ })

    private fun TestScope.viewModel(import: suspend (String) -> File? = { null }) =
        LeafAssessmentViewModel("tree-1", repository(), import) { deleted += it }.also { advanceUntilIdle() }

    @Test
    fun `availability reflects whether a model is ready`() = runTest(dispatcher) {
        assertTrue(viewModel().state.value.availability is AiAvailability.NotReady)
        server.assessment = FakeAiServer.result()
        val ready = viewModel().state.value.availability as AiAvailability.Ready
        assertEquals("DINOv2 ViT-S/14", ready.modelDisplayName)
        assertEquals(3, ready.modelVersion)
        server.unreachable = true
        assertTrue(viewModel().state.value.availability is AiAvailability.Unavailable)
    }

    @Test
    fun `run assessment records the scan before handing over to the result page`() = runTest(dispatcher) {
        server.assessment = FakeAiServer.result()
        val vm = viewModel()
        val file = photo()
        vm.onImageCaptured(file)
        assertEquals(LeafStage.Review(file.absolutePath), vm.state.value.stage)

        vm.assess()
        assertTrue(vm.state.value.stage is LeafStage.Starting)
        advanceUntilIdle()

        val id = requireNotNull(vm.state.value.startedAssessmentId)
        val record = requireNotNull(dao.rows.value[id])
        assertEquals("tree-1", record.treeId)
        assertEquals(file.absolutePath, record.imageLocalPath)
        assertEquals(AssessmentStatus.COMPLETED, record.status)
        // The submitted photo belongs to the record: it is never discarded, even when the screen closes.
        vm.navigationHandled()
        assertNull(vm.state.value.startedAssessmentId)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `offline run still records a scan and keeps its photo`() = runTest(dispatcher) {
        server.unreachable = true
        val vm = viewModel()
        val file = photo()
        vm.onImageCaptured(file)
        vm.assess()
        advanceUntilIdle()

        val record = dao.rows.value.getValue(requireNotNull(vm.state.value.startedAssessmentId))
        assertEquals(AssessmentStatus.FAILED, record.status)
        assertEquals("UNREACHABLE", record.errorCode)
        assertTrue(File(record.imageLocalPath!!).isFile)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `retake discards only the unsubmitted photo`() = runTest(dispatcher) {
        val vm = viewModel()
        val first = photo("a.jpg")
        vm.onImageCaptured(first)
        vm.retake()
        assertEquals(listOf(first.absolutePath), deleted)
        assertEquals(LeafStage.Capture, vm.state.value.stage)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `picked image goes to review, unreadable one shows a message`() = runTest(dispatcher) {
        val picked = photo("picked.jpg")
        val vm = viewModel(import = { uri -> if (uri == "ok") picked else null })
        vm.onImagePicked("broken")
        advanceUntilIdle()
        assertEquals("That image could not be read or is larger than 10 MB.", vm.state.value.message)
        vm.onImagePicked("ok")
        advanceUntilIdle()
        assertEquals(LeafStage.Review(picked.absolutePath), vm.state.value.stage)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `camera permission denial explains the alternative`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onCameraPermissionDenied()
        assertTrue(vm.state.value.message!!.contains("pick a photo"))
    }
}
