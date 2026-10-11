package com.geotree.app.feature.classifiers

import com.geotree.app.data.repository.AiProblem
import com.geotree.app.data.repository.ClassifierRepository
import com.geotree.app.data.repository.LocalImageReader
import com.geotree.app.testing.FakeAiServer
import com.geotree.app.testing.fakeImageReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClassImagesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = FakeAiServer()
    private val repository = ClassifierRepository({ server }, { "http://10.0.2.2:8000/$it" })
    private val read = fakeImageReader()
    private lateinit var classId: String

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        classId = server.addClass("Healthy", 3).id
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(reader: LocalImageReader = LocalImageReader { read(it) }) = ClassImagesViewModel(classId, repository, reader)

    @Test
    fun `loads class images`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("Healthy", vm.state.value.className)
        assertEquals(3, vm.state.value.images.size)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `upload reports progress per image`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(LocalImageReader { uri -> if (uri == "b") gate.await(); read(uri) })
        advanceUntilIdle()

        vm.upload(listOf("a", "b", "c"))
        runCurrent()
        val midway = vm.state.value.upload!!
        assertEquals(1, midway.done)
        assertEquals(3, midway.total)
        assertTrue(vm.state.value.busy)

        gate.complete(Unit)
        advanceUntilIdle()
        val done = vm.state.value.upload!!
        assertTrue(done.finished)
        assertEquals(3, done.uploaded)
        assertEquals(6, vm.state.value.images.size)
        assertEquals("Uploaded 3 of 3 image(s).", vm.state.value.message)
        assertEquals("UNTRAINED", vm.state.value.trainingStatus) // refreshed from the server after the upload
    }

    @Test
    fun `failed and unreadable images are listed while the rest upload`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.upload(listOf("a", "a", "unreadable-1", "d"))
        advanceUntilIdle()

        val progress = vm.state.value.upload!!
        assertEquals(4, progress.done)
        assertEquals(2, progress.uploaded)
        assertEquals(2, progress.failures.size)
        assertTrue(progress.failures[0].contains("identical image"))
        assertTrue(progress.failures[1].contains("could not be read"))
    }

    @Test
    fun `losing the server stops the upload and says how many were not sent`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        server.unreachable = true

        vm.upload(listOf("a", "b", "c"))
        advanceUntilIdle()

        val progress = vm.state.value.upload!!
        assertTrue(progress.finished)
        assertEquals(0, progress.uploaded)
        assertEquals(1, server.uploadCalls)
        assertTrue(progress.failures.single().contains("3 image(s) not sent"))
    }

    @Test
    fun `delete selected removes images and clears the selection`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val ids = vm.state.value.images.map { it.id }
        vm.toggleSelection(ids[0])
        vm.toggleSelection(ids[1])
        assertTrue(vm.state.value.selecting)

        vm.deleteSelected()
        advanceUntilIdle()

        assertEquals(listOf(ids[2]), vm.state.value.images.map { it.id })
        assertTrue(vm.state.value.selected.isEmpty())
        assertFalse(vm.state.value.deleting)
        assertTrue(vm.state.value.message!!.startsWith("Deleted 2 image(s)"))
    }

    @Test
    fun `a single image can be deleted from its preview`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val ids = vm.state.value.images.map { it.id }

        vm.deleteImage(ids[1])
        assertTrue(vm.state.value.deleting)
        advanceUntilIdle()

        assertEquals(listOf(ids[0], ids[2]), vm.state.value.images.map { it.id })
        assertFalse(vm.state.value.deleting)
        assertEquals(2, server.images[classId]!!.size)
    }

    @Test
    fun `failed deletes stay selected and are reported`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val ids = vm.state.value.images.map { it.id }
        server.failDeleteFor = ids[1]
        vm.selectAll()

        vm.deleteSelected()
        advanceUntilIdle()

        assertEquals(listOf(ids[1]), vm.state.value.images.map { it.id })
        assertEquals(setOf(ids[1]), vm.state.value.selected)
        assertTrue(vm.state.value.message!!.startsWith("Deleted 2 of 3"))
    }

    @Test
    fun `non-admin cannot open a class`() = runTest(dispatcher) {
        server.admin = false
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(AiProblem.AdminRequired, vm.state.value.problem)
    }
}
