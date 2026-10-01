package com.geotree.app.feature.locator

import com.geotree.app.core.location.LocationPermission
import com.geotree.app.core.location.LocationUpdateProfile
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.data.model.TreeDraft
import com.geotree.app.data.repository.CreateTreeResult
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.locator.map.CameraCommand
import com.geotree.app.feature.locator.map.MapCamera
import com.geotree.app.feature.locator.map.MapCoverage
import com.geotree.app.feature.locator.map.MapSourceType
import com.geotree.app.feature.locator.map.OfflineMapPackage
import com.geotree.app.feature.locator.map.OfflineMapRegion
import com.geotree.app.feature.locator.map.OfflinePackageFormat
import com.geotree.app.feature.locator.map.TileEncoding
import com.geotree.app.feature.locator.map.resolveBasemap
import com.geotree.app.feature.navigation.DirectRouteProvider
import com.geotree.app.feature.navigation.FieldNavigationController
import com.geotree.app.feature.navigation.GeoPoint
import com.geotree.app.testing.FakeLocationSource
import com.geotree.app.testing.FakeTreeDao
import com.geotree.app.testing.gpsFix
import com.geotree.app.testing.offsetMeters
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocatorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeTreeDao()
    private var ids = 0
    private val repository = TreeRepository(dao, clock = { 1_790_000_000_000L + ids }, newId = { "uuid-${++ids}" })
    private val location = FakeLocationSource()
    private val syncRequests = mutableListOf<Boolean>()

    private val here = gpsFix(15.2167, 120.6600)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = LocatorViewModel(
        repository = repository,
        locationSource = location,
        syncTrigger = { syncRequests += it },
        syncTracker = SyncStatusTracker(),
        navigation = FieldNavigationController(DirectRouteProvider(), clock = { here.capturedAt }),
        basemapSource = flowOf(resolveBasemap(MapSourceType.ONLINE_DEV, null)),
    )

    private suspend fun tree(code: String, north: Double, east: Double = 0.0): String {
        val (lat, lon) = offsetMeters(here.latitude, here.longitude, north, east)
        return (repository.createTree(TreeDraft(code, null, gpsFix(lat, lon))) as CreateTreeResult.Created).tree.id
    }

    /** Starts the location stream and delivers [fixes] one by one. */
    private suspend fun TestScope.tracking(vm: LocatorViewModel, vararg fixes: com.geotree.app.core.location.GpsFix) {
        vm.startTracking()
        advanceUntilIdle()
        fixes.forEach {
            location.fixes.emit(it)
            advanceUntilIdle()
        }
    }

    @Test
    fun `selecting a marker selects it and zooms to tree level`() = runTest {
        val id = tree("GEO-TAM-003", north = 80.0)
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectTree(id)

        assertEquals(id, vm.selectedTreeId.value)
        val command = vm.cameraCommand.value as CameraCommand.Center
        assertEquals(MapZoom.TREE, command.zoom!!, 0.0)
        assertEquals(vm.trees.value.single().latitude, command.latitude, 0.0)

        vm.selectTree(null)
        assertNull(vm.selectedTreeId.value)
        assertSame("deselecting must not move the camera", command, vm.cameraCommand.value)
    }

    @Test
    fun `tree focus stays within field zoom 17 to 18`() = runTest {
        val id = tree("GEO-TAM-003", north = 80.0)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onCameraIdle(MapCamera(15.2, 120.7, 19.5)) // user zoomed far in
        vm.focusTree(id)
        assertEquals(MapZoom.TREE_MAX, (vm.cameraCommand.value as CameraCommand.Center).zoom!!, 0.0)

        vm.onCameraIdle(MapCamera(15.2, 120.7, 14.0)) // user zoomed out
        vm.focusTree(id)
        assertEquals(MapZoom.TREE, (vm.cameraCommand.value as CameraCommand.Center).zoom!!, 0.0)
        assertTrue(MapZoom.TREE in 17.0..18.0 && MapZoom.TREE_MAX in 17.0..18.0)
    }

    @Test
    fun `map opens near the user at overview zoom and centers only once`() = runTest {
        val vm = viewModel()
        tracking(vm, here)

        val first = vm.cameraCommand.value as CameraCommand.Center
        assertEquals(MapZoom.OVERVIEW, first.zoom!!, 0.0)
        assertTrue(first.zoom!! in 15.0..16.0)

        location.fixes.emit(gpsFix(15.2170, 120.6603))
        advanceUntilIdle()
        assertSame("the camera must not fight the user on every fix", first, vm.cameraCommand.value)
    }

    @Test
    fun `without GPS the map opens on the PSAU deployment area, never a country view`() = runTest {
        tree("GEO-TAM-001", north = 50.0) // trees do not move the default camera
        val vm = viewModel()
        advanceUntilIdle()

        val camera = vm.initialCamera()
        assertEquals(OfflineMapRegion.DEFAULT_CENTER_LATITUDE, camera.latitude, 0.0)
        assertEquals(OfflineMapRegion.DEFAULT_CENTER_LONGITUDE, camera.longitude, 0.0)
        assertTrue("area overview zoom", camera.zoom in 14.5..15.5)
        assertTrue(OfflineMapRegion.contains(camera.latitude, camera.longitude))
    }

    @Test
    fun `with GPS the map opens on the user, and a saved camera wins on return`() = runTest {
        location.lastFix.value = here
        val vm = viewModel()
        val withFix = vm.initialCamera()
        assertEquals(here.latitude, withFix.latitude, 0.0)
        assertEquals(MapZoom.OVERVIEW, withFix.zoom, 0.0)

        vm.onCameraIdle(MapCamera(15.23, 120.70, 16.2))
        assertEquals(MapCamera(15.23, 120.70, 16.2), vm.initialCamera())
    }

    @Test
    fun `first fresh fix recentres even when the map opened at a stale cached location`() = runTest {
        location.lastFix.value = gpsFix(15.2167, 120.6600, capturedAt = here.capturedAt - 3_600_000) // cached, in town
        val vm = viewModel()
        val opening = vm.initialCamera()
        vm.onCameraIdle(opening) // MapLibre reports idle right after the opening position

        val psau = gpsFix(15.2178, 120.6942)
        tracking(vm, psau)

        val command = vm.cameraCommand.value as CameraCommand.Center
        assertEquals(psau.latitude, command.latitude, 0.0)
        assertEquals(psau.longitude, command.longitude, 0.0)
    }

    @Test
    fun `first fix does not override a map the user already moved`() = runTest {
        val vm = viewModel()
        vm.onUserGesture()
        tracking(vm, here)
        assertNull(vm.cameraCommand.value)
    }

    @Test
    fun `outside the offline map is reported without touching GPS state`() = runTest {
        val offline = resolveBasemap(
            MapSourceType.OFFLINE_PSAU,
            OfflineMapPackage(File("psau.mbtiles"), OfflinePackageFormat.MBTILES, TileEncoding.WEBP, OfflineMapRegion.bounds),
        )
        val vm = LocatorViewModel(
            repository, location, { syncRequests += it }, SyncStatusTracker(),
            FieldNavigationController(DirectRouteProvider(), clock = { here.capturedAt }), flowOf(offline),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.coverage.collect {} }
        tracking(vm, here)
        assertEquals(MapCoverage.INSIDE, vm.coverage.value)

        location.fixes.emit(gpsFix(15.1449, 120.5887)) // Angeles, outside the package
        advanceUntilIdle()

        assertEquals(MapCoverage.OUTSIDE, vm.coverage.value)
        assertEquals("GPS keeps tracking outside coverage", TrackingState.TRACKING, vm.tracking.value)
        assertEquals(15.1449, vm.currentFix.value!!.latitude, 0.0)
    }

    @Test
    fun `my location zooms to street level, also when the fix arrives later`() = runTest {
        val vm = viewModel()
        vm.centerOnCurrentLocation() // no fix yet: request is remembered
        advanceUntilIdle()
        location.fixes.emit(here)
        advanceUntilIdle()
        assertEquals(MapZoom.MY_LOCATION, (vm.cameraCommand.value as CameraCommand.Center).zoom!!, 0.0)

        vm.selectTree(null)
        vm.centerOnCurrentLocation()
        val command = vm.cameraCommand.value as CameraCommand.Center
        assertEquals(MapZoom.MY_LOCATION, command.zoom!!, 0.0)
        assertTrue(command.zoom!! in 16.5..17.5)
    }

    @Test
    fun `follow mode tracks fixes until the user moves the map`() = runTest {
        val vm = viewModel()
        tracking(vm, here)

        vm.setFollowing(true)
        assertTrue(vm.following.value)
        val moved = gpsFix(15.2171, 120.6604)
        location.fixes.emit(moved)
        advanceUntilIdle()
        val follow = vm.cameraCommand.value as CameraCommand.Center
        assertEquals(moved.latitude, follow.latitude, 0.0)
        assertNull("follow keeps the user's zoom", follow.zoom)

        vm.onUserGesture()
        assertFalse(vm.following.value)
        location.fixes.emit(gpsFix(15.2175, 120.6608))
        advanceUntilIdle()
        assertSame(follow, vm.cameraCommand.value)
    }

    @Test
    fun `navigation switches GPS to the navigation profile and stopping switches back`() = runTest {
        val id = tree("GEO-TAM-008", north = 300.0, east = 300.0)
        val vm = viewModel()
        tracking(vm, here)
        assertEquals(LocationUpdateProfile.LOCATOR, vm.activeProfile.value)

        vm.selectTree(id)
        vm.startNavigation(id)
        advanceUntilIdle()

        assertEquals(LocationUpdateProfile.NAVIGATION, vm.activeProfile.value)
        assertEquals(LocationUpdateProfile.NAVIGATION, location.requestedProfiles.last())
        assertEquals("exactly one GPS stream at a time", 1, location.openStreams)
        val nav = vm.navigationState.value!!
        assertEquals(id, nav.destination.treeId)
        assertEquals(424.3, nav.distanceRemainingMeters!!, 1.0)
        assertNull("the sheet closes when guidance starts", vm.selectedTreeId.value)
        val fit = vm.cameraCommand.value as CameraCommand.Fit
        assertEquals(2, fit.points.size)
        assertEquals(GeoPoint(here.latitude, here.longitude), fit.points.first())
        assertEquals(MapZoom.NAVIGATION_MAX, fit.maxZoom, 0.0)

        // Walking closer updates guidance from the continuous stream.
        val (lat, lon) = offsetMeters(here.latitude, here.longitude, 200.0, 200.0)
        location.fixes.emit(gpsFix(lat, lon))
        advanceUntilIdle()
        assertEquals(141.4, vm.navigationState.value!!.distanceRemainingMeters!!, 1.0)

        vm.stopNavigation()
        advanceUntilIdle()
        assertNull(vm.navigationState.value)
        assertEquals(LocationUpdateProfile.LOCATOR, vm.activeProfile.value)
        assertEquals(1, location.openStreams)
    }

    @Test
    fun `destination change replaces the session`() = runTest {
        val a = tree("GEO-TAM-001", north = 100.0)
        val b = tree("GEO-TAM-002", north = -100.0)
        val vm = viewModel()
        tracking(vm, here)

        vm.startNavigation(a)
        advanceUntilIdle()
        assertEquals("N", vm.navigationState.value!!.cardinal)
        vm.startNavigation(b)
        advanceUntilIdle()

        assertEquals(b, vm.navigationState.value!!.destination.treeId)
        assertEquals("S", vm.navigationState.value!!.cardinal)
        assertEquals(1, location.openStreams)
    }

    @Test
    fun `leaving the map releases GPS even during navigation`() = runTest {
        val id = tree("GEO-TAM-008", north = 50.0)
        val vm = viewModel()
        tracking(vm, here)
        vm.startNavigation(id)
        advanceUntilIdle()
        assertEquals(1, location.openStreams)

        vm.stopTracking()
        advanceUntilIdle()

        assertEquals("no location-update leak", 0, location.openStreams)
        assertNull(vm.activeProfile.value)
        // Guidance resumes at the navigation rate when the map is shown again.
        vm.startTracking()
        advanceUntilIdle()
        assertEquals(LocationUpdateProfile.NAVIGATION, vm.activeProfile.value)
    }

    @Test
    fun `selected tree sheet shows distance from the current fix`() = runTest {
        val id = tree("GEO-TAM-004", north = 100.0)
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.selectedTreeDistanceMeters.collect {} }
        tracking(vm, here)

        vm.selectTree(id)
        advanceUntilIdle()

        assertEquals(100.0, vm.selectedTreeDistanceMeters.value!!, 0.5)
    }

    @Test
    fun `no permission means no stream is opened`() = runTest {
        location.permission = LocationPermission.NONE
        val vm = viewModel()
        vm.startTracking()
        advanceUntilIdle()

        assertEquals(TrackingState.PERMISSION_REQUIRED, vm.tracking.value)
        assertEquals(0, location.openStreams)
    }

    @Test
    fun `sync now replaces any queued sync`() = runTest {
        viewModel().syncNow()
        assertEquals(listOf(true), syncRequests)
    }
}
