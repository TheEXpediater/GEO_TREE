package com.geotree.app.feature.navigation

import com.geotree.app.testing.gpsFix
import com.geotree.app.testing.offsetMeters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldNavigationControllerTest {
    private val config = NavigationConfig(arrivalRadiusMeters = 10.0, walkingSpeedMps = 1.4)
    private val now = 1_790_000_000_000L // gpsFix() default capture time
    private val controller = FieldNavigationController(DirectRouteProvider(config), config, clock = { now })
    private val tree = NavigationDestination("uuid-8", "GEO-TAM-008", 15.2167, 120.6600)

    /** A fix [south] meters south and [west] meters west of the tree, so the tree lies north-east. */
    private fun fixAway(south: Double, west: Double = 0.0, speed: Float? = null, accuracy: Float = 4.8f) =
        offsetMeters(tree.latitude, tree.longitude, -south, -west).let { (lat, lon) -> gpsFix(lat, lon, accuracy, speedMps = speed) }

    @Test
    fun `start without a fix waits for GPS`() = runTest {
        controller.start(tree, currentFix = null)

        val state = controller.state.value!!
        assertTrue(controller.isActive)
        assertFalse(state.hasFix)
        assertNull(state.distanceRemainingMeters)
        assertEquals(tree.latitude, state.destinationLatitude, 0.0)
        assertEquals(tree.longitude, state.destinationLongitude, 0.0)
        assertEquals("Waiting for GPS fix…", state.display(config).distance)
    }

    @Test
    fun `fix produces distance, bearing, route and accuracy`() = runTest {
        controller.start(tree, fixAway(south = 300.0, west = 300.0))

        val state = controller.state.value!!
        assertEquals(424.3, state.distanceRemainingMeters!!, 0.5)
        assertEquals(45.0, state.bearingDegrees!!, 0.5)
        assertEquals("NE", state.cardinal)
        assertEquals(4.8f, state.gpsAccuracy)
        assertEquals(RouteType.DIRECT, state.route!!.routeType)
        assertEquals(tree.point, state.route!!.geometry.last())
        assertEquals("424 m away", state.display(config).distance)
    }

    @Test
    fun `distance and bearing update as the user walks`() = runTest {
        controller.start(tree, fixAway(south = 400.0))
        assertEquals(400.0, controller.state.value!!.distanceRemainingMeters!!, 0.5)
        assertEquals("N", controller.state.value!!.cardinal)

        controller.onLocation(fixAway(south = 0.0, west = 120.0))
        assertEquals(120.0, controller.state.value!!.distanceRemainingMeters!!, 0.5)
        assertEquals("E", controller.state.value!!.cardinal)
    }

    @Test
    fun `moving user gets an eta from measured speed`() = runTest {
        controller.start(tree, fixAway(south = 420.0, speed = 1.3f))

        val state = controller.state.value!!
        assertEquals(EtaSource.CURRENT_SPEED, state.etaSource)
        assertEquals(NavigationMath.etaSeconds(state.distanceRemainingMeters!!, 1.3f.toDouble()), state.etaSeconds)
        assertEquals(1.3, state.currentSpeedMps!!, 1e-6)
        assertEquals(ArrivalTiming.Measured(speed = "4.7 km/h", eta = "6 min"), state.display(config).timing)
    }

    @Test
    fun `stationary user gets a labelled walking estimate, not a fake speed`() = runTest {
        controller.start(tree, fixAway(south = 420.0, speed = 0f))

        val state = controller.state.value!!
        assertEquals(SpeedReading.Stationary, state.speed)
        assertEquals(0.0, state.currentSpeedMps!!, 0.0)
        assertEquals(EtaSource.WALKING_ESTIMATE, state.etaSource)
        assertEquals(300L, state.etaSeconds) // 420 m / 1.4 m/s
        assertEquals(
            ArrivalTiming.WalkingEstimate(eta = "≈ 5 min", reason = "You are stationary", pace = "at 5.0 km/h walking pace"),
            state.display(config).timing,
        )
    }

    @Test
    fun `unknown speed is shown as unavailable`() = runTest {
        controller.start(tree, fixAway(south = 100.0, speed = null))
        val state = controller.state.value!!
        assertNull(state.currentSpeedMps)
        val timing = state.display(config).timing as ArrivalTiming.WalkingEstimate
        assertEquals("GPS is not reporting your speed", timing.reason)
        assertEquals(EtaSource.WALKING_ESTIMATE, state.etaSource)
    }

    @Test
    fun `arrival triggers once per session at the arrival radius`() = runTest {
        controller.start(tree, fixAway(south = 10.5))
        assertFalse(controller.state.value!!.arrived)

        controller.onLocation(fixAway(south = 9.9))
        assertTrue(controller.state.value!!.arrived)
        assertTrue(controller.state.value!!.arrivalAlertPending)

        controller.acknowledgeArrival()
        controller.onLocation(fixAway(south = 3.0))
        controller.onLocation(fixAway(south = 15.0))
        controller.onLocation(fixAway(south = 2.0))
        assertTrue(controller.state.value!!.arrived)
        assertFalse("arrival must not re-alert on later fixes", controller.state.value!!.arrivalAlertPending)
    }

    @Test
    fun `stop clears the session and ignores later fixes`() = runTest {
        controller.start(tree, fixAway(south = 50.0))
        controller.stop()

        assertNull(controller.state.value)
        assertFalse(controller.isActive)
        controller.onLocation(fixAway(south = 40.0))
        assertNull(controller.state.value)
    }

    @Test
    fun `changing destination starts a new session and re-arms arrival`() = runTest {
        controller.start(tree, fixAway(south = 5.0))
        assertTrue(controller.state.value!!.arrived)
        val firstSession = controller.state.value!!.sessionId

        val other = NavigationDestination("uuid-9", "GEO-TAM-009", 15.2200, 120.6600)
        controller.start(other, fixAway(south = 5.0))

        val state = controller.state.value!!
        assertEquals("GEO-TAM-009", state.destination.treeCode)
        assertTrue(state.sessionId != firstSession)
        assertFalse(state.arrived)
        assertTrue(state.distanceRemainingMeters!! > 300)
    }

    @Test
    fun `a fix computed for a stopped session is discarded`() = runTest {
        lateinit var stopping: FieldNavigationController
        val slowProvider = RouteProvider { from, to ->
            stopping.stop() // the user taps Stop while this route is being computed
            DirectRouteProvider(config).route(from, to)
        }
        stopping = FieldNavigationController(slowProvider, config, clock = { now })
        stopping.start(tree, currentFix = null)

        stopping.onLocation(fixAway(south = 30.0))

        assertNull(stopping.state.value)
    }

    @Test
    fun `follow flag is part of navigation state`() = runTest {
        controller.start(tree, null, following = true)
        assertTrue(controller.state.value!!.isFollowingLocation)
        controller.setFollowing(false)
        assertFalse(controller.state.value!!.isFollowingLocation)
    }

    @Test
    fun `a stale fix does not seed a new session or fake an arrival`() = runTest {
        // Last fix was taken at the tree minutes ago (e.g. right after tagging it), then the user walked away.
        val stale = fixAway(south = 0.0).copy(capturedAt = now - 5 * 60_000)
        controller.start(tree, stale)

        val waiting = controller.state.value!!
        assertFalse(waiting.hasFix)
        assertFalse(waiting.arrived)

        controller.onLocation(fixAway(south = 232.0))
        assertEquals(232.0, controller.state.value!!.distanceRemainingMeters!!, 0.5)
        assertFalse(controller.state.value!!.arrived)
        assertFalse(controller.state.value!!.arrivalAlertPending)
    }

    @Test
    fun `a measured speed expires when GPS stops delivering fixes`() = runTest {
        var now = this@FieldNavigationControllerTest.now
        val ticking = FieldNavigationController(DirectRouteProvider(config), config, clock = { now })
        ticking.start(tree, fixAway(south = 420.0, speed = 1.3f))
        assertEquals(EtaSource.CURRENT_SPEED, ticking.state.value!!.etaSource)

        now += config.maxSpeedAgeMillis // exactly at the limit: still fresh
        ticking.expireStaleSpeed()
        assertEquals(EtaSource.CURRENT_SPEED, ticking.state.value!!.etaSource)

        now += 1
        ticking.expireStaleSpeed()
        val state = ticking.state.value!!
        assertEquals(SpeedReading.Unavailable, state.speed)
        assertEquals(EtaSource.WALKING_ESTIMATE, state.etaSource)
        assertEquals(300L, state.etaSeconds) // 420 m at 1.4 m/s, not the stale 1.3 m/s
        assertTrue(state.display(config).timing is ArrivalTiming.WalkingEstimate)
        assertEquals("distance is unaffected", 420.0, state.distanceRemainingMeters!!, 0.5)
    }

    @Test
    fun `walking estimate is never labelled as a measured speed`() = runTest {
        controller.start(tree, fixAway(south = 420.0, speed = 0f))
        val stationary = controller.state.value!!.display(config).timing
        assertTrue(stationary is ArrivalTiming.WalkingEstimate)
        assertTrue((stationary as ArrivalTiming.WalkingEstimate).eta.startsWith("≈"))

        controller.onLocation(fixAway(south = 400.0, speed = 1.3f))
        assertTrue(controller.state.value!!.display(config).timing is ArrivalTiming.Measured)
    }

    @Test
    fun `no fix yet shows no arrival time`() = runTest {
        controller.start(tree, currentFix = null)
        assertEquals(ArrivalTiming.Unavailable, controller.state.value!!.display(config).timing)
    }
}
