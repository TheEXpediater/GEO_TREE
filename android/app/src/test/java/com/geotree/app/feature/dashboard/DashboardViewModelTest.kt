package com.geotree.app.feature.dashboard

import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.location.LocationPermission
import com.geotree.app.core.network.BackendReachability
import com.geotree.app.core.network.BackendStatus
import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionState
import com.geotree.app.core.sync.SyncOutcome
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.testing.FakeLocationSource
import com.geotree.app.testing.FakeTreeDao
import com.geotree.app.testing.gpsFix
import com.geotree.app.feature.locator.map.OfflineMapPackageInfo
import com.geotree.app.feature.locator.map.OfflineMapRegion
import com.geotree.app.feature.locator.map.OfflineMapState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeTreeDao()
    private val location = FakeLocationSource()
    private val tracker = SyncStatusTracker(clock = { 42L })
    private val backend = MutableStateFlow(BackendStatus(BackendReachability.CHECKING))
    private val syncRequests = mutableListOf<Boolean>()
    private var backendChecks = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun row(code: String, status: SyncStatus, createdAt: Long, updatedAt: Long = createdAt) = TreeEntity(
        id = "id-$code", treeCode = code, localImagePath = null, remoteImagePath = null,
        latitude = 15.2167, longitude = 120.66, accuracyMeters = 4f, altitudeMeters = null,
        locationCapturedAt = createdAt, age = null, tasteCategory = null, yearlyYield = null,
        fruitQuality = null, notes = null, createdAt = createdAt, updatedAt = updatedAt,
        syncStatus = status, serverVersion = null, lastSyncError = null,
    )

    private fun TestScope.viewModel(session: Session? = null): DashboardViewModel {
        val vm = DashboardViewModel(
            repository = TreeRepository(dao),
            locationSource = location,
            syncTrigger = { syncRequests += it },
            syncTracker = tracker,
            backendStatus = backend,
            backendUrl = MutableStateFlow("http://10.0.2.2:8000"),
            lastSyncAt = MutableStateFlow(1_790_000_000_000L),
            deviceOnline = MutableStateFlow(false),
            session = MutableStateFlow(session),
            clock = { 1_790_000_000_000L },
            refreshBackend = { backendChecks++ },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `counts come from Room`() = runTest {
        dao.rows.value = listOf(
            row("A", SyncStatus.SYNCED, 1), row("B", SyncStatus.SYNCED, 2), row("C", SyncStatus.SYNCED, 3),
            row("D", SyncStatus.PENDING, 4), row("E", SyncStatus.PENDING, 5), row("F", SyncStatus.FAILED, 6),
        ).associateBy { it.id }
        val vm = viewModel()
        advanceUntilIdle()

        val counts = vm.state.value.counts
        assertEquals(6, counts.total)
        assertEquals(3, counts.synced)
        assertEquals(2, counts.pending)
        assertEquals(1, counts.failed)
        assertEquals(3, vm.state.value.waitingToSync)
        assertEquals("3 records waiting to sync", pendingMessage(vm.state.value.waitingToSync))
    }

    @Test
    fun `dashboard works with an empty database and no backend`() = runTest {
        backend.value = BackendStatus(BackendReachability.OFFLINE, "http://10.0.2.2:8000", "Server unreachable.")
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(0, vm.state.value.counts.total)
        assertEquals(emptyList<TreeEntity>(), vm.state.value.recentTrees)
        assertNull(pendingMessage(0))
        assertEquals(StatusLabel("Offline", PillTone.Warning), vm.state.value.backend.display())
        assertEquals(false, vm.state.value.deviceOnline)
    }

    @Test
    fun `recent trees are newest updated first and limited`() = runTest {
        dao.rows.value = listOf(
            row("OLD", SyncStatus.SYNCED, createdAt = 1, updatedAt = 1),
            row("EDITED", SyncStatus.PENDING, createdAt = 2, updatedAt = 100),
            row("NEW", SyncStatus.PENDING, createdAt = 50),
            row("T4", SyncStatus.SYNCED, 10), row("T5", SyncStatus.SYNCED, 11), row("T6", SyncStatus.SYNCED, 12),
        ).associateBy { it.id }
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("EDITED", "NEW", "T6", "T5", "T4"), vm.state.value.recentTrees.map { it.treeCode })
        assertEquals(listOf("B", "A"), recentTrees(listOf(row("A", SyncStatus.SYNCED, 5, 9), row("B", SyncStatus.SYNCED, 6, 9))).map { it.treeCode })
    }

    @Test
    fun `backend status maps to dashboard labels`() {
        assertEquals(StatusLabel("Connected", PillTone.Good), BackendStatus(BackendReachability.CONNECTED).display())
        assertEquals(StatusLabel("Offline", PillTone.Warning), BackendStatus(BackendReachability.OFFLINE).display())
        assertEquals(StatusLabel("Checking", PillTone.Neutral), BackendStatus(BackendReachability.CHECKING).display())
    }

    @Test
    fun `gps availability reflects permission and location services`() = runTest {
        assertEquals(GpsAvailability.PERMISSION_REQUIRED, gpsAvailability(LocationPermission.NONE, true))
        assertEquals(GpsAvailability.LOCATION_OFF, gpsAvailability(LocationPermission.PRECISE, false))
        assertEquals(GpsAvailability.AVAILABLE, gpsAvailability(LocationPermission.APPROXIMATE, true))

        val vm = viewModel()
        location.lastFix.value = gpsFix(accuracy = 4.8f)
        location.locationEnabled = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(GpsAvailability.LOCATION_OFF, vm.state.value.gps)
        assertEquals(4.8f, vm.state.value.lastFix!!.accuracyMeters)
    }

    @Test
    fun `sync now requests an immediate sync and rechecks the backend`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val before = backendChecks

        vm.syncNow()
        advanceUntilIdle()

        assertEquals(listOf(true), syncRequests)
        assertEquals(before + 1, backendChecks)
    }

    @Test
    fun `finished sync run triggers a backend recheck and shows sync activity`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val before = backendChecks

        tracker.started()
        advanceUntilIdle()
        assertEquals(true, vm.state.value.syncRunning)
        tracker.finished(SyncOutcome.Completed(1, 0, 1, null))
        advanceUntilIdle()

        assertEquals(false, vm.state.value.syncRunning)
        assertEquals(before + 1, backendChecks)
    }

    @Test
    fun `pending message wording`() {
        assertNull(pendingMessage(0))
        assertEquals("1 record waiting to sync", pendingMessage(1))
        assertEquals("3 records waiting to sync", pendingMessage(3))
    }

    @Test
    fun `offline map readiness label and coverage text`() {
        assertEquals(StatusLabel("Ready", PillTone.Good), offlineMapStatusLabel(OfflineMapState.Ready(sampleInfo(), File("x"))))
        assertEquals(StatusLabel("Preparing", PillTone.Neutral), offlineMapStatusLabel(OfflineMapState.Checking))
        assertEquals(StatusLabel("Not installed", PillTone.Warning), offlineMapStatusLabel(OfflineMapState.Missing("none")))
        assertEquals(StatusLabel("Unavailable", PillTone.Error), offlineMapStatusLabel(OfflineMapState.Failed("bad")))
        assertEquals(
            "15.1980–15.2400° N · 120.6480–120.7150° E (≈7.2 × 4.6 km)",
            coverageText(OfflineMapRegion.bounds),
        )
    }

    private fun sampleInfo() = OfflineMapPackageInfo(
        id = "psau_magalang", name = "PSAU / Magalang", version = "v1", file = "psau_magalang.mbtiles", format = "mbtiles",
        bytes = 1, sha256 = "00", minZoom = 13, maxZoom = 17,
        minLatitude = 15.198, maxLatitude = 15.24, minLongitude = 120.648, maxLongitude = 120.715,
    )

    @Test
    fun `expired session keeps the local dashboard and asks to sign in to sync`() = runTest {
        dao.rows.value = listOf(row("A", SyncStatus.PENDING, 1), row("B", SyncStatus.SYNCED, 2)).associateBy { it.id }
        val vm = viewModel(session = Session("old", "admin@gmail.com", expiresAtMillis = 1))
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(SessionState.EXPIRED, state.session)
        assertTrue(state.authRequired)
        assertEquals("Sign in to sync", state.sync.label)
        assertEquals("local data still shown", 2, state.counts.total)
    }

    @Test
    fun `valid session does not ask to sign in unless the server rejected the token`() = runTest {
        val vm = viewModel(session = Session("ok", "admin@gmail.com", expiresAtMillis = 1_790_000_000_000L + 86_400_000L))
        advanceUntilIdle()
        assertEquals(SessionState.VALID, vm.state.value.session)
        assertFalse(vm.state.value.authRequired)

        tracker.finished(SyncOutcome.AuthExpired) // e.g. token revoked on the server
        advanceUntilIdle()
        assertTrue(vm.state.value.authRequired)
    }
}
