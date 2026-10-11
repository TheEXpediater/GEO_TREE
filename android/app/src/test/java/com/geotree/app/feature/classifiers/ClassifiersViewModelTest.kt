package com.geotree.app.feature.classifiers

import com.geotree.app.data.repository.AiProblem
import com.geotree.app.data.repository.ClassifierRepository
import com.geotree.app.testing.FakeAiServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClassifiersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = FakeAiServer()
    private val repository = ClassifierRepository({ server }, { "http://10.0.2.2:8000/$it" })

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server.addClass("Healthy", 12)
        server.addClass("Spotting", 11)
        server.addClass("Mold", 3)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.loaded(): ClassifiersViewModel = ClassifiersViewModel(repository, pollIntervalMillis = 1_000).also { advanceUntilIdle() }

    @Test
    fun `loads classes, image counts and model status`() = runTest(dispatcher) {
        val vm = ClassifiersViewModel(repository)
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("Healthy", "Spotting", "Mold"), state.classes.map { it.name })
        assertEquals(listOf(12, 11, 3), state.classes.map { it.imageCount })
        assertEquals(10, state.minImagesPerClass)
        assertEquals("UNTRAINED", state.model?.status)
        assertEquals(26, state.datasetImageCount)
    }

    @Test
    fun `non-admin sees an authorization problem instead of the list`() = runTest(dispatcher) {
        server.admin = false
        val vm = loaded()
        assertEquals(AiProblem.AdminRequired, vm.state.value.problem)
        assertTrue(vm.state.value.classes.isEmpty())
    }

    @Test
    fun `offline server is reported without crashing`() = runTest(dispatcher) {
        server.unreachable = true
        val vm = loaded()
        assertTrue(vm.state.value.problem is AiProblem.Unreachable)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `add class succeeds, refreshes and closes the dialog`() = runTest(dispatcher) {
        val vm = loaded()
        var closed = false
        vm.addClass("  Other ") { closed = true }
        advanceUntilIdle()

        assertTrue(closed)
        assertEquals(listOf("Healthy", "Spotting", "Mold", "Other"), vm.state.value.classes.map { it.name })
        assertEquals("Added Other.", vm.state.value.message)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `duplicate or blank names are refused before calling the server`() = runTest(dispatcher) {
        val vm = loaded()
        var closed = false
        vm.addClass("healthy") { closed = true }
        advanceUntilIdle()
        assertFalse(closed)
        assertEquals("A classifier named healthy already exists.", vm.state.value.dialogError)

        val mold = vm.state.value.classes.last()
        vm.renameClass(mold.id, " SPOTTING ") { closed = true }
        assertEquals("A classifier named SPOTTING already exists.", vm.state.value.dialogError)
        vm.renameClass(mold.id, "   ") { closed = true }
        assertEquals("Enter a classifier name.", vm.state.value.dialogError)
        assertFalse(closed)
        assertEquals(listOf("Healthy", "Spotting", "Mold"), server.classes.values.map { it.name })

        vm.dialogClosed()
        assertNull(vm.state.value.dialogError)
    }

    @Test
    fun `server-side name conflict keeps the dialog open with its message`() = runTest(dispatcher) {
        val vm = loaded()
        server.addClass("Other") // created elsewhere after this screen loaded
        var closed = false
        vm.addClass("other") { closed = true }
        advanceUntilIdle()
        assertFalse(closed)
        assertEquals("A class named other already exists.", vm.state.value.dialogError)
    }

    @Test
    fun `renaming to the same name closes without a request`() = runTest(dispatcher) {
        val vm = loaded()
        val healthy = vm.state.value.classes.first()
        var closed = false
        vm.renameClass(healthy.id, "Healthy ") { closed = true }
        assertTrue(closed)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `rename and delete update the list`() = runTest(dispatcher) {
        val vm = loaded()
        val mold = vm.state.value.classes.last()
        vm.renameClass(mold.id, "Sooty Mold") {}
        advanceUntilIdle()
        assertEquals("Sooty Mold", vm.state.value.classes.last().name)

        vm.deleteClass(vm.state.value.classes.last())
        advanceUntilIdle()
        assertEquals(listOf("Healthy", "Spotting"), vm.state.value.classes.map { it.name })
        assertTrue(vm.state.value.message!!.contains("must be rebuilt"))
    }

    /** A visible screen polls forever in virtual time; always hide it so runTest can finish. */
    private fun TestScope.whileVisible(vm: ClassifiersViewModel, block: () -> Unit) {
        vm.setVisible(true)
        try {
            runCurrent()
            block()
        } finally {
            vm.setVisible(false)
        }
    }

    @Test
    fun `retrain polls the model status until it settles`() = runTest(dispatcher) {
        val vm = loaded()
        server.statusSequence.clear()
        // Screen refresh on becoming visible, retrain refresh, then two polls.
        server.statusSequence.addAll(listOf("UNTRAINED", "QUEUED", "TRAINING", "READY"))
        whileVisible(vm) {
            assertFalse(vm.state.value.isServerBusy())
            vm.retrain()
            advanceTimeBy(100)
            assertEquals("QUEUED", vm.state.value.model?.status)
            assertTrue(vm.state.value.isServerBusy())

            advanceTimeBy(1_000)
            assertEquals("TRAINING", vm.state.value.model?.status)
            advanceTimeBy(1_000)
            assertEquals("READY", vm.state.value.model?.status)

            val calls = server.modelStatusCalls
            advanceTimeBy(5_000)
            assertEquals("polling stops once nothing is running", calls, server.modelStatusCalls)
        }
    }

    @Test
    fun `hidden screen does not poll`() = runTest(dispatcher) {
        val vm = loaded()
        server.statusSequence.clear()
        server.statusSequence.add("TRAINING")
        whileVisible(vm) { advanceTimeBy(2_500) }
        val calls = server.modelStatusCalls
        advanceTimeBy(10_000)
        assertEquals(calls, server.modelStatusCalls)
    }
}
