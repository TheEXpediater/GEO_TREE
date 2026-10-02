package com.geotree.app.feature.locator

import com.geotree.app.core.location.GpsFix
import com.geotree.app.core.orientation.DeclinationSource
import com.geotree.app.core.orientation.HeadingAccuracy
import com.geotree.app.core.orientation.HeadingState
import com.geotree.app.core.orientation.NorthReference
import com.geotree.app.core.orientation.RawHeading
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.data.model.TreeDraft
import com.geotree.app.data.repository.CreateTreeResult
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.locator.map.CameraCommand
import com.geotree.app.feature.locator.map.MapSourceType
import com.geotree.app.feature.locator.map.resolveBasemap
import com.geotree.app.feature.navigation.DirectRouteProvider
import com.geotree.app.feature.navigation.FieldNavigationController
import com.geotree.app.testing.FakeHeadingSource
import com.geotree.app.testing.FakeLocationSource
import com.geotree.app.testing.FakeTreeDao
import com.geotree.app.testing.gpsFix
import com.geotree.app.testing.offsetMeters
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Compass, follow modes and the guidance panel on the Map, with no phone hardware involved. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocatorHeadingTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeTreeDao()
    private var ids = 0
    private val repository = TreeRepository(dao, clock = { 1_790_000_000_000L + ids }, newId = { "uuid-${++ids}" })
    private val location = FakeLocationSource()
    private val compass = FakeHeadingSource()
    private val here = gpsFix(15.2178, 120.6942)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(declination: DeclinationSource = DeclinationSource { _, _, _, _ -> -1.5 }) = LocatorViewModel(
        repository = repository,
        locationSource = location,
        syncTrigger = {},
        syncTracker = SyncStatusTracker(),
        navigation = FieldNavigationController(DirectRouteProvider(), clock = { here.capturedAt }),
        basemapSource = flowOf(resolveBasemap(MapSourceType.ONLINE_DEV, null)),
        headingSource = compass,
        declination = declination,
    )

    private suspend fun tree(code: String, north: Double, east: Double = 0.0): String {
        val (lat, lon) = offsetMeters(here.latitude, here.longitude, north, east)
        return (repository.createTree(TreeDraft(code, null, gpsFix(lat, lon))) as CreateTreeResult.Created).tree.id
    }

    private suspend fun TestScope.tracking(vm: LocatorViewModel, vararg fixes: GpsFix) {
        vm.startTracking()
        advanceUntilIdle()
        fixes.forEach {
            location.fixes.emit(it)
            advanceUntilIdle()
        }
    }

    private suspend fun TestScope.compassReads(degrees: Double, accuracy: HeadingAccuracy = HeadingAccuracy.HIGH) {
        compass.readingsFlow.emit(RawHeading(degrees, accuracy))
        advanceUntilIdle()
    }

    @Test
    fun `compass sensors run only while guidance needs them and the map is visible`() = runTest {
        val id = tree("GEO-TAM-008", north = 200.0, east = 200.0)
        val vm = viewModel()
        tracking(vm, here)
        assertEquals("plain map: no compass", 0, compass.openRegistrations)
        assertEquals(HeadingState.Inactive, vm.heading.value)

        vm.startNavigation(id)
        advanceUntilIdle()
        assertEquals(1, compass.openRegistrations)

        vm.stopTracking() // map left / app backgrounded
        advanceUntilIdle()
        assertEquals("no sensor leak", 0, compass.openRegistrations)

        vm.startTracking() // back on the map, guidance still active
        advanceUntilIdle()
        assertEquals(1, compass.openRegistrations)

        vm.stopNavigation()
        advanceUntilIdle()
        assertEquals("guidance over: compass off", 0, compass.openRegistrations)
        assertEquals(HeadingState.Inactive, vm.heading.value)
    }

    @Test
    fun `heading is corrected to true north once GPS gives a position`() = runTest {
        val id = tree("GEO-TAM-008", north = 200.0)
        val vm = viewModel()
        tracking(vm, here)
        vm.startNavigation(id)
        advanceUntilIdle()

        compassReads(100.0)

        val heading = vm.heading.value as HeadingState.Available
        assertEquals(98.5, heading.degrees, 1e-6)
        assertEquals(NorthReference.TRUE, heading.reference)
    }

    @Test
    fun `without declination the heading is labelled magnetic`() = runTest {
        val id = tree("GEO-TAM-008", north = 200.0)
        val vm = viewModel(declination = DeclinationSource.None)
        tracking(vm, here)
        vm.startNavigation(id)
        advanceUntilIdle()

        compassReads(100.0)

        val heading = vm.heading.value as HeadingState.Available
        assertEquals(100.0, heading.degrees, 1e-6)
        assertEquals(NorthReference.MAGNETIC, heading.reference)
    }

    @Test
    fun `follow states FREE to FOLLOW_LOCATION to HEADING_UP and back`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.headingUpCamera.collect {} }
        tracking(vm, here)
        assertEquals(MapFollowMode.FREE, vm.mapMode.value)

        vm.centerOnCurrentLocation()
        assertEquals(MapFollowMode.FOLLOW_LOCATION, vm.mapMode.value)

        vm.toggleHeadingUp()
        advanceUntilIdle()
        assertEquals(MapFollowMode.HEADING_UP, vm.mapMode.value)
        assertEquals("Heading Up turns the compass on", 1, compass.openRegistrations)
        compassReads(45.0)
        val camera = vm.headingUpCamera.value!!
        assertEquals(43.5, camera.bearing, 1e-6) // map bearing = true heading
        assertEquals(here.latitude, camera.latitude, 0.0)

        vm.toggleHeadingUp() // back to North Up
        advanceUntilIdle()
        assertEquals(MapFollowMode.FOLLOW_LOCATION, vm.mapMode.value)
        assertEquals(0.0, (vm.cameraCommand.value as CameraCommand.Center).bearing!!, 0.0)
        assertNull(vm.headingUpCamera.value)
        assertEquals("not navigating: compass off again", 0, compass.openRegistrations)
    }

    @Test
    fun `a map gesture leaves Heading Up for FREE`() = runTest {
        val vm = viewModel()
        tracking(vm, here)
        vm.toggleHeadingUp()
        advanceUntilIdle()

        vm.onUserGesture()
        advanceUntilIdle()

        assertEquals(MapFollowMode.FREE, vm.mapMode.value)
        assertEquals(0, compass.openRegistrations)
    }

    @Test
    fun `no compass sensor means no Heading Up and an honest message`() = runTest {
        compass.isSupported = false
        val vm = viewModel()
        tracking(vm, here)

        vm.toggleHeadingUp()

        assertEquals(MapFollowMode.FREE, vm.mapMode.value)
        assertEquals(HeadingState.Unsupported, vm.heading.value)
        assertTrue(vm.message.value!!.text.contains("no compass"))
    }

    @Test
    fun `collapsing the panel keeps the same guidance session running`() = runTest {
        val id = tree("GEO-TAM-008", north = 300.0)
        val vm = viewModel()
        tracking(vm, here)
        vm.startNavigation(id)
        advanceUntilIdle()
        assertTrue("guidance opens expanded", vm.panelExpanded.value)
        val session = vm.navigationState.value!!.sessionId

        vm.setPanelExpanded(false)
        val (lat, lon) = offsetMeters(here.latitude, here.longitude, 150.0, 0.0)
        location.fixes.emit(gpsFix(lat, lon))
        advanceUntilIdle()

        assertFalse(vm.panelExpanded.value)
        assertEquals("GPS keeps updating while collapsed", 150.0, vm.navigationState.value!!.distanceRemainingMeters!!, 1.0)
        assertEquals(1, compass.openRegistrations)

        vm.togglePanel()
        assertTrue(vm.panelExpanded.value)
        assertEquals("same session after expanding", session, vm.navigationState.value!!.sessionId)
    }

    @Test
    fun `arrival still triggers while the panel is collapsed`() = runTest {
        val id = tree("GEO-TAM-008", north = 50.0)
        val vm = viewModel()
        tracking(vm, here)
        vm.startNavigation(id)
        advanceUntilIdle()
        vm.setPanelExpanded(false)

        val (lat, lon) = offsetMeters(here.latitude, here.longitude, 45.0, 0.0)
        location.fixes.emit(gpsFix(lat, lon))
        advanceUntilIdle()

        assertTrue(vm.navigationState.value!!.arrived)
        assertTrue(vm.navigationState.value!!.arrivalAlertPending)
    }
}
