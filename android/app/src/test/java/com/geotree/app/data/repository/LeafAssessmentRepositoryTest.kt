package com.geotree.app.data.repository

import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.testing.FakeAiServer
import com.geotree.app.testing.FakeLeafAssessmentDao
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Every scan is a permanent history record; nothing is overwritten. */
@OptIn(ExperimentalCoroutinesApi::class)
class LeafAssessmentRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeAiServer()
    private val dao = FakeLeafAssessmentDao()
    private var now = 1_790_000_000_000
    private var finishedCalls = 0

    private fun TestScope.repository() =
        LeafAssessmentRepository(dao, { server }, scope = this, onFinished = { finishedCalls++ }, clock = { now += 1_000; now })

    private fun photo(name: String): String = temp.newFile(name).apply { writeBytes(name.toByteArray()) }.absolutePath

    @Test
    fun `a scan is saved as PROCESSING before the AI request, then completed`() = runTest {
        server.assessment = FakeAiServer.result("Spotting", 0.94, version = 2)
        val repo = repository()
        val started = repo.start("tree-1", photo("a.jpg"))

        assertEquals(AssessmentStatus.PROCESSING, dao.rows.value.getValue(started.id).status)
        assertEquals(0, server.assessCalls)

        advanceUntilIdle()
        val done = dao.rows.value.getValue(started.id)
        assertEquals(AssessmentStatus.COMPLETED, done.status)
        assertEquals("Spotting", done.className)
        assertEquals(0.94f, done.confidence!!, 1e-6f)
        assertEquals("dinov2_vits14", done.modelName)
        assertEquals(2, done.modelVersion)
        assertTrue(done.completedAt!! > done.createdAt)
        assertEquals(SyncStatus.PENDING, done.syncStatus)
        assertEquals(1, finishedCalls) // a background sync is requested
    }

    @Test
    fun `a second scan creates a second record and leaves the first unchanged`() = runTest {
        val repo = repository()
        server.assessment = FakeAiServer.result("Healthy", 0.91)
        val first = repo.start("tree-1", photo("a.jpg"))
        advanceUntilIdle()
        val firstFinished = dao.rows.value.getValue(first.id)

        server.assessment = FakeAiServer.result("Spotting", 0.94)
        val second = repo.start("tree-1", photo("b.jpg"))
        advanceUntilIdle()

        assertNotEquals(first.id, second.id)
        assertEquals(2, dao.rows.value.size)
        assertEquals(firstFinished, dao.rows.value.getValue(first.id))
        // The first record was written exactly twice: inserted, then finalized. Never again.
        assertEquals(2, dao.writes.count { it.id == first.id })
    }

    @Test
    fun `history is newest first and the current status is derived from it`() = runTest {
        val repo = repository()
        server.assessment = FakeAiServer.result("Mold", 0.83)
        val oldest = repo.start("tree-1", photo("1.jpg")); advanceUntilIdle()
        server.assessment = FakeAiServer.result("Healthy", 0.91)
        val middle = repo.start("tree-1", photo("2.jpg")); advanceUntilIdle()
        server.unreachable = true
        val newest = repo.start("tree-1", photo("3.jpg")); advanceUntilIdle()
        repo.start("tree-2", photo("other.jpg")); advanceUntilIdle()

        val history = repo.observeHistory("tree-1").first()
        assertEquals(listOf(newest.id, middle.id, oldest.id), history.map { it.id })
        // A failed newest scan does not become the current status: the newest *result* does.
        assertEquals(middle.id, repo.observeLatestResult("tree-1").first()?.id)
        assertEquals("Healthy", repo.observeLatestResult("tree-1").first()?.className)
        assertNull(repo.observeLatestResult("tree-without-scans").first())
    }

    @Test
    fun `uncertain results are kept as UNCERTAIN with their class and confidence`() = runTest {
        server.assessment = FakeAiServer.result("Healthy", 0.48)
        val repo = repository()
        val scan = repo.start("tree-1", photo("u.jpg")); advanceUntilIdle()
        val record = dao.rows.value.getValue(scan.id)
        assertEquals(AssessmentStatus.UNCERTAIN, record.status)
        assertEquals("Healthy", record.className)
        assertEquals(scan.id, repo.observeLatestResult("tree-1").first()?.id)
    }

    @Test
    fun `failed scans keep their photo and error and are never invented a result`() = runTest {
        val repo = repository()
        val notReady = repo.start("tree-1", photo("n.jpg")); advanceUntilIdle() // no model yet → 409
        server.unreachable = true
        val offline = repo.start("tree-1", photo("o.jpg")); advanceUntilIdle()

        val a = dao.rows.value.getValue(notReady.id)
        assertEquals(AssessmentStatus.FAILED, a.status)
        assertEquals("AI_MODEL_NOT_READY", a.errorCode)
        val b = dao.rows.value.getValue(offline.id)
        assertEquals("UNREACHABLE", b.errorCode)
        listOf(a, b).forEach {
            assertNull(it.className); assertNull(it.confidence); assertNull(it.modelVersion)
            assertTrue(File(it.imageLocalPath!!).isFile)
        }
    }

    @Test
    fun `assess again makes a new scan from the same photo and leaves the failed one as history`() = runTest {
        val repo = repository()
        server.unreachable = true
        val failed = repo.start("tree-1", photo("f.jpg")); advanceUntilIdle()
        val failedRecord = dao.rows.value.getValue(failed.id)

        server.unreachable = false
        server.assessment = FakeAiServer.result("Spotting", 0.97)
        val retry = requireNotNull(repo.reassess(failed.id)); advanceUntilIdle()

        assertNotEquals(failed.id, retry.id)
        assertEquals(failedRecord, dao.rows.value.getValue(failed.id))
        assertEquals(AssessmentStatus.COMPLETED, dao.rows.value.getValue(retry.id).status)
        assertEquals(failedRecord.imageLocalPath, dao.rows.value.getValue(retry.id).imageLocalPath)
        assertEquals(2, repo.observeHistory("tree-1").first().size)
    }

    @Test
    fun `assess again needs a finished scan with its photo on the device`() = runTest {
        val repo = repository()
        server.unreachable = true
        val scan = repo.start("tree-1", photo("gone.jpg")); advanceUntilIdle()
        File(dao.rows.value.getValue(scan.id).imageLocalPath!!).delete()
        assertNull(repo.reassess(scan.id))
        assertNull(repo.reassess("unknown"))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `scans interrupted by a killed process become FAILED history entries`() = runTest {
        val repo = repository()
        // Simulate a request that never returned: the record is PROCESSING and the process died.
        val scan = repo.start("tree-1", photo("i.jpg"))
        assertEquals(1, repo.failInterrupted())
        val record = dao.rows.value.getValue(scan.id)
        assertEquals(AssessmentStatus.FAILED, record.status)
        assertEquals("INTERRUPTED", record.errorCode)
        assertTrue(File(record.imageLocalPath!!).isFile)

        advanceUntilIdle() // the late job must not rewrite the recorded failure
        assertEquals(record, dao.rows.value.getValue(scan.id))
    }

    @Test
    fun `sync updates change only sync fields`() = runTest {
        server.assessment = FakeAiServer.result("Mold", 0.83)
        val repo = repository()
        val scan = repo.start("tree-1", photo("s.jpg")); advanceUntilIdle()
        val recorded = dao.rows.value.getValue(scan.id)

        repo.markSyncing(scan.id)
        repo.markSynced(scan.id, serverVersion = 7, remoteImagePath = "leaf_assessments/${scan.id}.jpg")
        val synced = dao.rows.value.getValue(scan.id)
        assertEquals(SyncStatus.SYNCED, synced.syncStatus)
        assertEquals(7L, synced.serverVersion)
        assertEquals(recorded.copy(syncStatus = SyncStatus.SYNCED, serverVersion = 7, remoteImagePath = "leaf_assessments/${scan.id}.jpg"), synced)
    }
}
