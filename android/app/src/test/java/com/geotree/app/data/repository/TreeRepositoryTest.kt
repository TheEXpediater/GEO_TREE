package com.geotree.app.data.repository

import com.geotree.app.core.database.SyncStatus
import com.geotree.app.data.model.DraftError
import com.geotree.app.data.model.TreeDraft
import com.geotree.app.data.remote.toSyncRequest
import com.geotree.app.testing.FakeGeoTreeServer
import com.geotree.app.testing.FakeTreeDao
import com.geotree.app.testing.gpsFix
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeRepositoryTest {
    private val dao = FakeTreeDao()
    private var now = 1_790_000_100_000L
    private var nextId = 0
    private val repository = TreeRepository(dao, clock = { now }, newId = { "uuid-${++nextId}" })

    private fun draft(code: String = "GEO-TAM-003") = TreeDraft(code, "/data/tree_images/a.jpg", gpsFix())

    @Test
    fun `create saves locally as PENDING with a generated UUID and no network`() = runTest {
        val result = repository.createTree(draft(code = "geo-tam-003"))

        val created = (result as CreateTreeResult.Created).tree
        assertEquals("uuid-1", created.id)
        assertEquals("GEO-TAM-003", created.treeCode)
        assertEquals(SyncStatus.PENDING, created.syncStatus)
        assertEquals(15.1449, created.latitude, 0.0)
        assertEquals(4.2f, created.accuracyMeters)
        assertNull(created.serverVersion)
        assertEquals(listOf(created), repository.observeTrees().first())
    }

    @Test
    fun `duplicate local tree code is rejected without touching the first tree`() = runTest {
        repository.createTree(draft())
        val result = repository.createTree(draft(code = " geo-tam-003 "))

        assertEquals(CreateTreeResult.DuplicateTreeCode("GEO-TAM-003"), result)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `invalid draft is not saved`() = runTest {
        val result = repository.createTree(TreeDraft("GEO-TAM-003", null, fix = null))
        assertEquals(CreateTreeResult.Invalid(setOf(DraftError.LOCATION_MISSING)), result)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `sync counts reflect Room state`() = runTest {
        repository.createTree(draft("GEO-TAM-003"))
        repository.createTree(draft("GEO-TAM-004"))
        repository.markFailed("uuid-2", "offline")
        assertEquals(SyncCounts(pending = 1, failed = 1), repository.observeSyncCounts().first())
    }

    @Test
    fun `remote changes are upserted as SYNCED and keep the local image`() = runTest {
        val server = FakeGeoTreeServer()
        val created = (repository.createTree(draft()) as CreateTreeResult.Created).tree
        val mine = server.syncTree(created.toSyncRequest()).tree
        val theirs = server.addRemoteTree("uuid-other", "GEO-TAM-004")

        val result = repository.applyRemoteChanges(listOf(theirs, mine))

        assertEquals(ApplyResult(appliedThroughVersion = theirs.serverVersion, blockedTreeCode = null), result)
        val other = dao.getById("uuid-other")!!
        assertEquals(SyncStatus.SYNCED, other.syncStatus)
        assertEquals("tree_images/uuid-other-remote.jpg", other.remoteImagePath)
        assertNull(other.localImagePath)
        assertEquals("/data/tree_images/a.jpg", dao.getById(created.id)!!.localImagePath)
    }

    @Test
    fun `remote tree whose code is held by a different local tree blocks the cursor`() = runTest {
        repository.createTree(draft("GEO-TAM-004")) // local, unsynced
        val server = FakeGeoTreeServer()
        val first = server.addRemoteTree("uuid-a", "GEO-TAM-010")
        val clash = server.addRemoteTree("uuid-b", "GEO-TAM-004")
        val after = server.addRemoteTree("uuid-c", "GEO-TAM-011")

        val result = repository.applyRemoteChanges(listOf(first, clash, after))

        assertEquals(ApplyResult(appliedThroughVersion = first.serverVersion, blockedTreeCode = "GEO-TAM-004"), result)
        assertNull(dao.getById("uuid-b"))
        assertNull(dao.getById("uuid-c"))
    }

    @Test
    fun `pending local edit newer than the remote copy is not overwritten`() = runTest {
        val created = (repository.createTree(draft()) as CreateTreeResult.Created).tree
        val stale = FakeGeoTreeServer().addRemoteTree(created.id, "GEO-TAM-003", latitude = 1.0)

        repository.applyRemoteChanges(listOf(stale.copy(updatedAt = "2020-01-01T00:00:00Z")))

        val local = dao.getById(created.id)!!
        assertEquals(SyncStatus.PENDING, local.syncStatus)
        assertEquals(15.1449, local.latitude, 0.0)
    }

    @Test
    fun `renaming re-queues the tree and enforces uniqueness`() = runTest {
        repository.createTree(draft("GEO-TAM-003"))
        repository.createTree(draft("GEO-TAM-004"))
        repository.markFailed("uuid-1", "Tree Code conflict")

        assertEquals(RenameResult.Duplicate, repository.renameTreeCode("uuid-1", "geo-tam-004"))
        assertEquals(RenameResult.Renamed, repository.renameTreeCode("uuid-1", "geo-tam-005"))
        val renamed = dao.getById("uuid-1")!!
        assertEquals("GEO-TAM-005", renamed.treeCode)
        assertEquals(SyncStatus.PENDING, renamed.syncStatus)
        assertNull(renamed.lastSyncError)
        assertTrue(renamed.updatedAt > renamed.createdAt)
    }
}
