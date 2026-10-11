package com.geotree.app.core.sync

import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionStore
import com.geotree.app.data.model.TreeDraft
import com.geotree.app.data.repository.CreateTreeResult
import com.geotree.app.data.repository.LeafAssessmentRepository
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.testing.FakeAiServer
import com.geotree.app.testing.FakeGeoTreeServer
import com.geotree.app.testing.FakeLeafAssessmentDao
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Leaf assessment history through the real SyncEngine: idempotent by assessment id, never overwritten. */
class LeafAssessmentSyncTest {
    @get:Rule val temp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val treeDao = FakeTreeDao()
    private val leafDao = FakeLeafAssessmentDao()
    private val server = FakeGeoTreeServer()
    private var ids = 0
    private val trees = TreeRepository(treeDao, clock = { 1_790_000_100_000L + ids }, newId = { "tree-${++ids}" })
    private val assessments = LeafAssessmentRepository(leafDao, { FakeAiServer() }, scope = workScope)
    private lateinit var preferences: SyncPreferences
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() = runTest {
        val dataStore = testDataStore(temp.newFolder(), storeScope)
        val sessions = SessionStore(dataStore)
        preferences = SyncPreferences(dataStore)
        sessions.save(Session("token-123", "admin@gmail.com", 0))
        engine = SyncEngine(trees, { server }, sessions, preferences, SyncStatusTracker(), leafAssessments = assessments)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        workScope.cancel()
    }

    private suspend fun tree(code: String) = (trees.createTree(TreeDraft(code, null, gpsFix())) as CreateTreeResult.Created).tree

    private fun scan(
        id: String,
        treeId: String,
        className: String?,
        createdAt: Long,
        status: AssessmentStatus = AssessmentStatus.COMPLETED,
        photo: Boolean = true,
    ) = LeafAssessmentEntity(
        id = id, treeId = treeId,
        imageLocalPath = if (photo) temp.newFile("$id.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }.absolutePath else null,
        remoteImagePath = null, classId = className?.let { "c-$it" }, className = className,
        confidence = if (status.hasResult) 0.9f else null, modelName = if (status.hasResult) "dinov2_vits14" else null,
        modelDisplayName = if (status.hasResult) "DINOv2 ViT-S/14" else null, modelVersion = if (status.hasResult) 2 else null,
        status = status, errorCode = if (status == AssessmentStatus.FAILED) "UNREACHABLE" else null,
        errorMessage = if (status == AssessmentStatus.FAILED) "offline" else null,
        createdAt = createdAt, completedAt = createdAt + 3_000, updatedAt = createdAt + 3_000,
        syncStatus = SyncStatus.PENDING, serverVersion = null, lastSyncError = null,
    )

    @Test
    fun `several scans of one tree sync with photos and become SYNCED`() = runTest {
        val t = tree("GEO-TAM-001")
        leafDao.insert(scan("a1", t.id, "Mold", 1_000))
        leafDao.insert(scan("a2", t.id, "Healthy", 2_000))
        leafDao.insert(scan("a3", t.id, null, 3_000, AssessmentStatus.FAILED))

        val outcome = engine.sync() as SyncOutcome.Completed

        assertEquals(3, outcome.assessmentsPushed)
        assertEquals(3, server.assessments.size)
        listOf("a1", "a2", "a3").forEach { id ->
            val local = leafDao.getById(id)!!
            assertEquals(SyncStatus.SYNCED, local.syncStatus)
            assertEquals("leaf_assessments/$id.jpg", local.remoteImagePath)
            assertTrue(local.serverVersion!! > 0)
        }
        assertEquals("FAILED", server.assessments.getValue("a3").status)
    }

    @Test
    fun `repeated sync never duplicates or re-uploads`() = runTest {
        val t = tree("GEO-TAM-001")
        leafDao.insert(scan("a1", t.id, "Spotting", 1_000))
        engine.sync()
        val calls = server.assessmentSyncCalls to server.assessmentUploadCalls

        engine.sync()
        engine.sync()

        assertEquals(1, server.assessments.size)
        assertEquals(calls, server.assessmentSyncCalls to server.assessmentUploadCalls) // synced records are not resent
        // Even if the same record is resent (e.g. its SYNCED mark was lost), the server answers "unchanged".
        leafDao.update(leafDao.getById("a1")!!.copy(syncStatus = SyncStatus.PENDING))
        engine.sync()
        assertEquals(1, server.assessments.size)
        assertEquals(SyncStatus.SYNCED, leafDao.getById("a1")!!.syncStatus)
    }

    @Test
    fun `network failure keeps local history and a later sync completes it`() = runTest {
        val t = tree("GEO-TAM-001")
        val record = scan("a1", t.id, "Spotting", 1_000)
        leafDao.insert(record)
        server.failNextAssessmentSyncWithIo = true

        val outcome = engine.sync()

        assertTrue(outcome is SyncOutcome.Interrupted)
        val kept = leafDao.getById("a1")!!
        assertEquals(SyncStatus.FAILED, kept.syncStatus)
        assertEquals(record.copy(syncStatus = SyncStatus.FAILED, lastSyncError = kept.lastSyncError), kept)
        assertTrue(File(kept.imageLocalPath!!).isFile)

        engine.sync()
        assertEquals(SyncStatus.SYNCED, leafDao.getById("a1")!!.syncStatus)
        assertNull(leafDao.getById("a1")!!.lastSyncError)
    }

    @Test
    fun `scans still processing are not synced`() = runTest {
        val t = tree("GEO-TAM-001")
        leafDao.insert(scan("p1", t.id, null, 1_000, AssessmentStatus.PROCESSING))
        engine.sync()
        assertTrue(server.assessments.isEmpty())
        assertEquals(SyncStatus.PENDING, leafDao.getById("p1")!!.syncStatus)
    }

    @Test
    fun `a scan whose tree is not on the server waits without blocking others`() = runTest {
        val synced = tree("GEO-TAM-001")
        leafDao.insert(scan("orphan", "tree-not-on-server", "Mold", 1_000))
        leafDao.insert(scan("ok", synced.id, "Healthy", 2_000))

        val outcome = engine.sync() as SyncOutcome.Completed

        assertEquals(1, outcome.assessmentsPushed)
        assertEquals(SyncStatus.FAILED, leafDao.getById("orphan")!!.syncStatus)
        assertEquals("Waiting for its tree to sync first.", leafDao.getById("orphan")!!.lastSyncError)
        assertEquals(SyncStatus.SYNCED, leafDao.getById("ok")!!.syncStatus)
    }

    @Test
    fun `a photo format the server rejects does not block the result from syncing`() = runTest {
        val t = tree("GEO-TAM-001")
        leafDao.insert(scan("a1", t.id, "Spotting", 1_000))
        server.rejectAssessmentImagesWith = 415
        engine.sync()
        val local = leafDao.getById("a1")!!
        assertEquals(SyncStatus.SYNCED, local.syncStatus)
        assertNull(local.remoteImagePath)
        assertTrue(local.lastSyncError!!.startsWith("Photo kept on this device only"))
    }

    @Test
    fun `pulled history from another device is merged without touching local scans`() = runTest {
        val t = tree("GEO-TAM-001")
        engine.sync() // tree on server
        val local = scan("mine", t.id, "Healthy", 5_000)
        leafDao.insert(local)
        server.addRemoteAssessment("theirs-old", t.id, "Mold", "2026-10-01T01:44:00Z")
        server.addRemoteAssessment("theirs-new", t.id, "Spotting", "2026-10-08T11:20:00Z")

        val outcome = engine.sync() as SyncOutcome.Completed

        assertEquals(2, outcome.assessmentsPulled)
        val history = assessments.observeHistory(t.id).first()
        assertEquals(3, history.size)
        val theirs = leafDao.getById("theirs-new")!!
        assertEquals(SyncStatus.SYNCED, theirs.syncStatus)
        assertNull(theirs.imageLocalPath)
        assertEquals("leaf_assessments/theirs-new.jpg", theirs.remoteImagePath)
        // The local scan kept its result and photo; only sync fields changed.
        val mine = leafDao.getById("mine")!!
        assertEquals(local.copy(syncStatus = SyncStatus.SYNCED, serverVersion = mine.serverVersion, remoteImagePath = mine.remoteImagePath), mine)
        assertTrue(preferences.lastPulledAssessmentVersion() >= 3)

        engine.sync() // nothing new: no duplicates
        assertEquals(3, assessments.observeHistory(t.id).first().size)
    }
}
