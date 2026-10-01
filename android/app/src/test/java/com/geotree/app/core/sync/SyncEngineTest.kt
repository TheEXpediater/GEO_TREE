package com.geotree.app.core.sync

import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionStore
import com.geotree.app.data.model.TreeDraft
import com.geotree.app.data.repository.CreateTreeResult
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.testing.FakeGeoTreeServer
import com.geotree.app.testing.FakeTreeDao
import com.geotree.app.testing.gpsFix
import com.geotree.app.testing.testDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncEngineTest {
    @get:Rule val temp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dao = FakeTreeDao()
    private val server = FakeGeoTreeServer()
    private var ids = 0
    private val repository = TreeRepository(dao, clock = { 1_790_000_100_000L + ids }, newId = { "uuid-${++ids}" })
    private lateinit var sessionStore: SessionStore
    private lateinit var preferences: SyncPreferences
    private lateinit var engine: SyncEngine
    private lateinit var image: File

    @Before
    fun setUp() = runTest {
        val dataStore = testDataStore(temp.newFolder(), storeScope)
        sessionStore = SessionStore(dataStore)
        preferences = SyncPreferences(dataStore)
        sessionStore.save(Session("token-123", "admin@gmail.com", 0))
        engine = SyncEngine(repository, { server }, sessionStore, preferences, SyncStatusTracker())
        image = temp.newFile("tree.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
    }

    @After
    fun tearDown() = storeScope.cancel()

    private suspend fun createTree(code: String, imagePath: String? = image.absolutePath) =
        (repository.createTree(TreeDraft(code, imagePath, gpsFix())) as CreateTreeResult.Created).tree

    @Test
    fun `pending tree goes PENDING - SYNCING - SYNCED with metadata and image`() = runTest {
        val tree = createTree("GEO-TAM-003")

        val outcome = engine.sync()

        assertEquals(SyncOutcome.Completed(pushed = 1, rejected = 0, pulled = 1, blockedTreeCode = null), outcome)
        assertEquals(listOf(SyncStatus.PENDING, SyncStatus.SYNCING, SyncStatus.SYNCED), dao.statusHistory[tree.id])
        val synced = dao.getById(tree.id)!!
        assertEquals(2L, synced.serverVersion) // metadata v1, image attach v2
        assertEquals("tree_images/${tree.id}-abc.jpg", synced.remoteImagePath)
        assertEquals(image.absolutePath, synced.localImagePath)
        assertEquals(1, server.trees.size)
        assertEquals(2L, preferences.lastPulledServerVersion())
    }

    @Test
    fun `repeated sync does not duplicate or re-upload`() = runTest {
        createTree("GEO-TAM-003")
        engine.sync()
        val second = engine.sync()

        assertEquals(SyncOutcome.Completed(0, 0, 0, null), second)
        assertEquals(1, server.trees.size)
        assertEquals(1, server.syncCalls)
        assertEquals(1, server.uploadCalls)
    }

    @Test
    fun `unreachable backend leaves records PENDING and untouched`() = runTest {
        val tree = createTree("GEO-TAM-005")
        server.healthy = false

        val outcome = engine.sync()

        assertTrue(outcome is SyncOutcome.BackendUnavailable)
        assertEquals(listOf(SyncStatus.PENDING), dao.statusHistory[tree.id])
        assertEquals(0, server.syncCalls)
        assertEquals(tree, dao.getById(tree.id))
    }

    @Test
    fun `connection drop marks FAILED and the next sync retries to SYNCED`() = runTest {
        val tree = createTree("GEO-TAM-005")
        server.failNextSyncWithIo = true

        val first = engine.sync()
        assertTrue(first is SyncOutcome.Interrupted)
        assertEquals(SyncStatus.FAILED, dao.getById(tree.id)!!.syncStatus)
        assertTrue(dao.getById(tree.id)!!.lastSyncError!!.contains("Will retry"))

        val second = engine.sync()
        assertEquals(1, (second as SyncOutcome.Completed).pushed)
        assertEquals(SyncStatus.SYNCED, dao.getById(tree.id)!!.syncStatus)
        assertNull(dao.getById(tree.id)!!.lastSyncError)
        assertEquals(1, server.trees.size)
    }

    @Test
    fun `failed image upload keeps the tree and retries only the image`() = runTest {
        val tree = createTree("GEO-TAM-006")
        server.failNextUploadWithIo = true

        engine.sync()
        assertEquals(SyncStatus.FAILED, dao.getById(tree.id)!!.syncStatus)
        assertNull(server.trees[tree.id]!!.imagePath)

        engine.sync()
        assertEquals(SyncStatus.SYNCED, dao.getById(tree.id)!!.syncStatus)
        assertEquals(2, server.uploadCalls)
        assertEquals(1, server.trees.size)
    }

    @Test
    fun `tree code conflict is FAILED with an explanation and never deleted`() = runTest {
        server.addRemoteTree("uuid-other-device", "GEO-TAM-003")
        val mine = createTree("GEO-TAM-003")

        val outcome = engine.sync() as SyncOutcome.Completed

        assertEquals(1, outcome.rejected)
        val local = dao.getById(mine.id)!!
        assertEquals(SyncStatus.FAILED, local.syncStatus)
        assertTrue(local.lastSyncError!!.contains("Tree Code"))
        // The other device's tree cannot be applied while our local tree holds the code.
        assertEquals("GEO-TAM-003", outcome.blockedTreeCode)
        assertEquals(0L, preferences.lastPulledServerVersion())
    }

    @Test
    fun `other devices' trees are pulled into Room and the cursor advances`() = runTest {
        server.addRemoteTree("uuid-device-b", "GEO-TAM-004", latitude = 15.3, longitude = 120.7)

        val outcome = engine.sync() as SyncOutcome.Completed

        assertEquals(1, outcome.pulled)
        val pulled = dao.getById("uuid-device-b")!!
        assertEquals(SyncStatus.SYNCED, pulled.syncStatus)
        assertEquals(15.3, pulled.latitude, 0.0)
        assertEquals(1L, preferences.lastPulledServerVersion())
        assertEquals(SyncOutcome.Completed(0, 0, 0, null), engine.sync())
    }

    @Test
    fun `signed out device does not sync`() = runTest {
        createTree("GEO-TAM-007")
        sessionStore.clear()
        assertEquals(SyncOutcome.NotSignedIn, engine.sync())
        assertEquals(0, server.syncCalls)
    }

    @Test
    fun `tree interrupted mid-sync by process death is retried`() = runTest {
        val tree = createTree("GEO-TAM-008")
        repository.markSyncing(tree.id) // simulate a crash after SYNCING was written

        engine.sync()

        assertEquals(SyncStatus.SYNCED, dao.getById(tree.id)!!.syncStatus)
    }

    @Test
    fun `tracker reports running and last outcome`() = runTest {
        val tracker = SyncStatusTracker { 42L }
        val trackedEngine = SyncEngine(repository, { server }, sessionStore, preferences, tracker)
        server.healthy = false
        trackedEngine.sync()
        assertFalse(tracker.state.value.running)
        assertTrue(tracker.state.value.lastOutcome is SyncOutcome.BackendUnavailable)
        assertEquals(42L, tracker.state.value.lastFinishedAt)
    }

    @Test
    fun `last successful sync time is recorded only for completed runs`() = runTest {
        createTree("GEO-TAM-010")
        server.healthy = false
        assertTrue(engine.sync() is SyncOutcome.BackendUnavailable)
        assertNull(preferences.lastSuccessfulSyncAt.first())

        server.healthy = true
        assertTrue(engine.sync() is SyncOutcome.Completed)
        assertTrue(preferences.lastSuccessfulSyncAt.first() != null)
    }
}
